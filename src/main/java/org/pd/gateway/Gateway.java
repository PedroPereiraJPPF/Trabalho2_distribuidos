package org.pd.gateway;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.paho.client.mqttv3.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

public class Gateway {
    // --- Configuração ---
    private static final String MQTT_BROKER = "tcp://localhost:1883";
    private static final String RABBIT_HOST = "localhost";
    private static final String RABBIT_EXCHANGE_NAME = "gateway_dados_topic";
    private static final int DASHBOARD_PORT = 8082;

    // --- Clientes e Canais ---
    private MqttClient clienteMqttEntrada;
    private MqttClient clienteMqttSaida;
    private Channel rabbitChannel;

    // --- Armazenamento em Memória para Dashboard ---
    private final Map<String, List<DadosClimaticos>> dadosEmMemoria = new ConcurrentHashMap<>();

    public Gateway() {
        dadosEmMemoria.put("norte", new ArrayList<>());
        dadosEmMemoria.put("sul", new ArrayList<>());
        dadosEmMemoria.put("leste", new ArrayList<>());
        dadosEmMemoria.put("oeste", new ArrayList<>());
    }

    public void iniciar() throws Exception {
        conectarMqttEntrada();
        conectarMqttSaida();
        conectarRabbitMQ();
        iniciarHttpServer(); // Nova funcionalidade
        System.out.println("\n✅ Gateway em operação. Aguardando e re-publicando dados...");
    }

    private void conectarRabbitMQ() throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT_HOST);
        Connection rabbitConnection = factory.newConnection();
        this.rabbitChannel = rabbitConnection.createChannel();
        this.rabbitChannel.exchangeDeclare(RABBIT_EXCHANGE_NAME, "topic");
        System.out.println("🔗 Gateway conectado ao RabbitMQ.");
    }

    private void conectarMqttEntrada() throws MqttException {
        String idCliente = "gateway-consumidor-" + System.currentTimeMillis();
        clienteMqttEntrada = new MqttClient(MQTT_BROKER, idCliente);
        MqttConnectOptions opcoesConexao = new MqttConnectOptions();
        opcoesConexao.setCleanSession(true);

        clienteMqttEntrada.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                System.err.println("❌ Conexão de entrada com MQTT perdida.");
            }

            @Override
            public void messageArrived(String topico, MqttMessage mensagem) {
                String payload = new String(mensagem.getPayload(), StandardCharsets.UTF_8);
                String regiao = topico.split("/")[1];

                System.out.println("📡 Gateway recebeu de [" + regiao + "]: " + payload);
                DadosClimaticos dados = processarPayload(regiao, payload);

                if (dados != null) {
                    armazenarDados(dados);
                    publicarViaMqtt(dados);
                    publicarViaRabbitMQ(dados);
                } else {
                    System.err.println("  ❌ Falha no processamento dos dados de [" + regiao + "]");
                }
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {}
        });

        clienteMqttEntrada.connect(opcoesConexao);
        clienteMqttEntrada.subscribe("drones/#", 0);
        System.out.println("📥 Gateway (entrada) conectado e subscrito a drones/#");
    }

    private void conectarMqttSaida() throws MqttException {
        String idCliente = "gateway-publicador-" + System.currentTimeMillis();
        clienteMqttSaida = new MqttClient(MQTT_BROKER, idCliente);
        MqttConnectOptions opcoesConexao = new MqttConnectOptions();
        opcoesConexao.setCleanSession(true);
        clienteMqttSaida.connect(opcoesConexao);
        System.out.println("📤 Gateway (saída) conectado.");
    }

    private DadosClimaticos processarPayload(String regiao, String payload) {
        String[] valores;
        // **Este é o seu código de parsing original, que funcionava**
        switch (regiao.toLowerCase()) {
            case "norte":
                valores = payload.split("-");
                break;
            case "sul":
                valores = payload.replace("(", "").replace(")", "").split("; ");
                break;
            case "leste":
                valores = payload.replace("{", "").replace("}", "").split(", ");
                break;
            case "oeste":
                valores = payload.split("#");
                break;
            default:
                return null;
        }
        try {
            double pressao = Double.parseDouble(valores[0].trim().replace(",", "."));
            double radiacao = Double.parseDouble(valores[1].trim().replace(",", "."));
            double temperatura = Double.parseDouble(valores[2].trim().replace(",", "."));
            double umidade = Double.parseDouble(valores[3].trim().replace(",", "."));
            return new DadosClimaticos(regiao, pressao, radiacao, temperatura, umidade);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            System.err.println("  ❌ Erro ao fazer parsing do payload: " + payload);
            return null;
        }
    }
    
    private void armazenarDados(DadosClimaticos dados) {
        synchronized (dadosEmMemoria.get(dados.getRegiao())) {
            dadosEmMemoria.get(dados.getRegiao()).add(dados);
        }
    }

    private void publicarViaMqtt(DadosClimaticos dados) {
        if (clienteMqttSaida == null || !clienteMqttSaida.isConnected()) return;
        try {
            String topicoSaida = "gateway/dados_processados/" + dados.getRegiao();
            clienteMqttSaida.publish(topicoSaida, dados.toString().getBytes(), 0, false);
        } catch (MqttException e) {
            System.err.println("  ❌ Erro ao publicar via MQTT: " + e.getMessage());
        }
    }

    private void publicarViaRabbitMQ(DadosClimaticos dados) {
        if (rabbitChannel == null || !rabbitChannel.isOpen()) return;
        try {
            String routingKey = "dados." + dados.getRegiao(); // Routing key mais específica
            rabbitChannel.basicPublish(RABBIT_EXCHANGE_NAME, routingKey, null, dados.toJson().getBytes("UTF-8"));
        } catch (IOException e) {
            System.err.println("  ❌ Erro ao publicar via RabbitMQ: " + e.getMessage());
        }
    }

    private void iniciarHttpServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(DASHBOARD_PORT), 0);
        server.createContext("/dashboard", (exchange) -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                String response = gerarRelatorioDashboard();
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        });
        server.setExecutor(null);
        server.start();
        System.out.println("📈 Dashboard disponível em http://localhost:" + DASHBOARD_PORT + "/dashboard");
    }

    private String gerarRelatorioDashboard() {
        StringBuilder sb = new StringBuilder();
        List<DadosClimaticos> todosOsDados = dadosEmMemoria.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());
        long total = todosOsDados.size();

        sb.append("--- Dashboard de Dados (Servido pelo Gateway Principal) ---\n\n");
        sb.append("Total de dados coletados: ").append(total).append("\n\n");

        if (total > 0) {
            Map<String, Long> contagemPorRegiao = todosOsDados.stream()
                .collect(Collectors.groupingBy(DadosClimaticos::getRegiao, Collectors.counting()));
            
            contagemPorRegiao.forEach((regiao, count) -> {
                double percentual = (count * 100.0) / total;
                sb.append(String.format("- %s: %.2f%% (%d dados)\n", regiao.toUpperCase(), percentual, count));
            });
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        try {
            new Gateway().iniciar();
        } catch (Exception e) {
            System.err.println("❌ Erro fatal ao iniciar o Gateway: " + e.getMessage());
            e.printStackTrace();
        }
    }
}