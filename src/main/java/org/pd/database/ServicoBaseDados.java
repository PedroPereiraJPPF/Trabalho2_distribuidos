package org.pd.database;

import com.rabbitmq.client.*;
import org.pd.gateway.DadosClimaticos;

import java.io.IOException;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ServicoBaseDados {
    private static final String RABBIT_EXCHANGE_NAME = "gateway_dados_topic";
    private static final String DATABASE_QUEUE = "database_storage_queue";
    private static final String LOG_FILE = "base_dados.log";

    private final Map<String, List<DadosClimaticos>> baseDados = new ConcurrentHashMap<>();
    private Connection rabbitConnection;
    private Channel rabbitChannel;
    private PrintWriter logWriter;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public ServicoBaseDados() {
        baseDados.put("norte", new ArrayList<>());
        baseDados.put("sul", new ArrayList<>());
        baseDados.put("leste", new ArrayList<>());
        baseDados.put("oeste", new ArrayList<>());
    }

    public void iniciar() throws IOException, TimeoutException {
        inicializarLog();
        conectarRabbitMQ();
        configurarConsumidor();
        escreverLog("SISTEMA", "Serviço de Base de Dados iniciado e aguardando dados...");
        System.out.println("🗄️ Serviço de Base de Dados iniciado e aguardando dados...");
        System.out.println("📄 Log sendo gravado em: " + LOG_FILE);
    }

    private void conectarRabbitMQ() throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        this.rabbitConnection = factory.newConnection();
        this.rabbitChannel = this.rabbitConnection.createChannel();

        this.rabbitChannel.exchangeDeclare(RABBIT_EXCHANGE_NAME, "topic");

        this.rabbitChannel.queueDeclare(DATABASE_QUEUE, true, false, false, null);
        this.rabbitChannel.queueBind(DATABASE_QUEUE, RABBIT_EXCHANGE_NAME, "#");

        escreverLog("SISTEMA", "Conectado ao RabbitMQ - Exchange: " + RABBIT_EXCHANGE_NAME);
        System.out.println("🔌 Serviço de Base de Dados conectado ao RabbitMQ");
    }

    private void configurarConsumidor() throws IOException {
        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), "UTF-8");
            String routingKey = delivery.getEnvelope().getRoutingKey();

            DadosClimaticos dados = parseJson(message);
            if (dados != null) {
                armazenarDados(dados);
                escreverLog(routingKey.toUpperCase(), dados.toString());
                System.out.println("💾 [BD] Dados armazenados: " + routingKey + " -> " + dados.toString());
            } else {
                escreverLog("ERRO", "Falha ao processar JSON: " + message);
            }
        };

        this.rabbitChannel.basicConsume(DATABASE_QUEUE, true, deliverCallback, consumerTag -> {
        });
    }

    private DadosClimaticos parseJson(String json) {
        Pattern pattern = Pattern.compile(
                "\"regiao\":\"(.*?)\", " +
                        "\"temperatura\":(\\d+\\.\\d+), " +
                        "\"umidade\":(\\d+\\.\\d+), " +
                        "\"pressao\":(\\d+\\.\\d+), " +
                        "\"radiacao\":(\\d+\\.\\d+), " +
                        "\"timestamp\":(\\d+)");

        Matcher matcher = pattern.matcher(json);

        if (matcher.find()) {
            try {
                String regiao = matcher.group(1);
                double temp = Double.parseDouble(matcher.group(2));
                double umid = Double.parseDouble(matcher.group(3));
                double pres = Double.parseDouble(matcher.group(4));
                double rad = Double.parseDouble(matcher.group(5));

                return new DadosClimaticos(regiao, pres, rad, temp, umid);
            } catch (Exception e) {
                System.err.println("❌ [BD] Erro ao fazer parse do JSON: " + json);
                return null;
            }
        }

        System.err.println("❌ [BD] JSON inválido: " + json);
        return null;
    }

    private void armazenarDados(DadosClimaticos dados) {
        synchronized (baseDados.get(dados.getRegiao())) {
            baseDados.get(dados.getRegiao()).add(dados);
        }
    }

    private void inicializarLog() {
        try {
            logWriter = new PrintWriter(new FileWriter(LOG_FILE, true));
            escreverLog("SISTEMA", "=== NOVA SESSÃO INICIADA ===");
        } catch (IOException e) {
            System.err.println("❌ [BD] Erro ao inicializar arquivo de log: " + e.getMessage());
        }
    }

    private void escreverLog(String regiao, String mensagem) {
        if (logWriter != null) {
            String timestamp = dateFormat.format(new Date());
            String logEntry = String.format("[%s] [%s] %s", timestamp, regiao, mensagem);
            logWriter.println(logEntry);
            logWriter.flush();
        }
    }

    private void fecharLog() {
        if (logWriter != null) {
            escreverLog("SISTEMA", "=== SESSÃO FINALIZADA ===");
            logWriter.close();
        }
    }

    public List<DadosClimaticos> buscarPorRegiao(String regiao) {
        List<DadosClimaticos> lista = baseDados.get(regiao.toLowerCase());
        return lista != null ? new ArrayList<>(lista) : new ArrayList<>();
    }

    public List<DadosClimaticos> buscarTodos() {
        List<DadosClimaticos> todos = new ArrayList<>();
        baseDados.values().forEach(todos::addAll);
        return todos;
    }

    public int contarDadosPorRegiao(String regiao) {
        List<DadosClimaticos> lista = baseDados.get(regiao.toLowerCase());
        return lista != null ? lista.size() : 0;
    }

    public int contarTotalDados() {
        return baseDados.values().stream().mapToInt(List::size).sum();
    }

    public void exibirEstatisticas() {
        System.out.println("\n📊 === ESTATÍSTICAS DA BASE DE DADOS ===");
        System.out.println("Total de registros: " + contarTotalDados());
        System.out.println("Por região:");
        baseDados.forEach((regiao, dados) -> System.out
                .println("  - " + regiao.toUpperCase() + ": " + dados.size() + " registros"));
        System.out.println("=========================================\n");
    }

    public void parar() {
        try {
            escreverLog("SISTEMA", "Encerrando Serviço de Base de Dados...");
            System.out.println("🛑 Encerrando Serviço de Base de Dados...");
            if (rabbitChannel != null && rabbitChannel.isOpen()) {
                rabbitChannel.close();
            }
            if (rabbitConnection != null && rabbitConnection.isOpen()) {
                rabbitConnection.close();
            }
            fecharLog();
            System.out.println("✅ Serviço de Base de Dados finalizado.");
        } catch (IOException | TimeoutException e) {
            System.err.println("❌ Erro ao encerrar Serviço de Base de Dados: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        ServicoBaseDados servicoBD = new ServicoBaseDados();

        try {
            servicoBD.iniciar();

            Thread estatisticasThread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(15000);
                        servicoBD.exibirEstatisticas();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
            estatisticasThread.setDaemon(true);
            estatisticasThread.start();

            System.out.println("🗄️ Serviço de Base de Dados em execução. Pressione CTRL+C para parar.");

            Runtime.getRuntime().addShutdownHook(new Thread(servicoBD::parar));

            Object lock = new Object();
            synchronized (lock) {
                lock.wait();
            }

        } catch (Exception e) {
            System.err.println("❌ Erro no Serviço de Base de Dados: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
