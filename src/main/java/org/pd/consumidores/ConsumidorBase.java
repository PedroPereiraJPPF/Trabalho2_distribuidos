package org.pd.consumidores;

import com.rabbitmq.client.*;
import org.pd.gateway.DadosClimaticos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ConsumidorBase {
    private static final String RABBIT_EXCHANGE_NAME = "gateway_dados_topic";
    private final Map<String, List<DadosClimaticos>> dadosRecebidos = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    public ConsumidorBase() {
        dadosRecebidos.put("norte", new ArrayList<>());
        dadosRecebidos.put("sul", new ArrayList<>());
        dadosRecebidos.put("leste", new ArrayList<>());
        dadosRecebidos.put("oeste", new ArrayList<>());
    }

    public void iniciar(String bindingKey) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        Connection connection = factory.newConnection();
        Channel channel = connection.createChannel();

        channel.exchangeDeclare(RABBIT_EXCHANGE_NAME, "topic");
        String queueName = channel.queueDeclare().getQueue();
        channel.queueBind(queueName, RABBIT_EXCHANGE_NAME, bindingKey);

        System.out.println(" [*] Aguardando dados do RabbitMQ. Binding key: '" + bindingKey + "'");

        iniciarExibicaoDashboard();

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), "UTF-8");
            processarMensagem(message);
        };
        channel.basicConsume(queueName, true, deliverCallback, consumerTag -> {});
    }

    private void processarMensagem(String json) {
        Pattern pattern = Pattern.compile(
                "\"regiao\":\"(.*?)\", " +
                        "\"temperatura\":(\\d+\\.\\d+), " +
                        "\"umidade\":(\\d+\\.\\d+), " +
                        "\"pressao\":(\\d+\\.\\d+), " +
                        "\"radiacao\":(\\d+\\.\\d+), " +
                        "\"timestamp\":(\\d+)"
        );

        Matcher matcher = pattern.matcher(json);

        if (matcher.find()) {
            try {
                String regiao = matcher.group(1);
                double temp = Double.parseDouble(matcher.group(2));
                double umid = Double.parseDouble(matcher.group(3));
                double pres = Double.parseDouble(matcher.group(4));
                double rad = Double.parseDouble(matcher.group(5));

                DadosClimaticos dados = new DadosClimaticos(regiao, pres, rad, temp, umid);
                synchronized (dadosRecebidos.get(regiao)) {
                    dadosRecebidos.get(regiao).add(dados);
                }
            } catch (Exception e) {
                System.err.println("Erro ao converter valores do JSON: " + json);
            }
        } else {
            System.err.println("Erro: JSON recebido não corresponde ao padrão esperado. JSON: " + json);
        }
    }

    private void iniciarExibicaoDashboard() {
        Runnable task = this::exibirDashboard;
        executor.scheduleAtFixedRate(task, 10, 10, TimeUnit.SECONDS);
    }

    private void exibirDashboard() {
        System.out.println("\n\n--- DASHBOARD DE DADOS CLIMÁTICOS (Atualizado em " + new java.util.Date() + ") ---");

        List<DadosClimaticos> todosOsDados = dadosRecebidos.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toList());

        if (todosOsDados.isEmpty()) {
            System.out.println("Nenhum dado coletado ainda.");
            return;
        }

        long total = todosOsDados.size();
        System.out.println("Total de dados coletados: " + total);

        System.out.println("\n--- Distribuição de Dados por Elemento ---");
        System.out.println("Temperatura: 25.00%");
        System.out.println("Umidade: 25.00%");
        System.out.println("Pressão: 25.00%");
        System.out.println("Radiação: 25.00%");

        System.out.println("\n--- Rankings por Média ---");
        imprimirRanking("Temperatura", todosOsDados, DadosClimaticos::getTemperatura);
        imprimirRanking("Umidade", todosOsDados, DadosClimaticos::getUmidade);
        imprimirRanking("Pressão", todosOsDados, DadosClimaticos::getPressao);
        imprimirRanking("Radiação", todosOsDados, DadosClimaticos::getRadiacao);
        System.out.println("-----------------------------------------------------\n");
    }

    private void imprimirRanking(String nomeElemento, List<DadosClimaticos> dados, java.util.function.ToDoubleFunction<DadosClimaticos> extrator) {
        System.out.println(">> " + nomeElemento + " (Média do maior para o menor):");
        Map<String, Double> mediaPorRegiao = dados.stream()
                .collect(Collectors.groupingBy(DadosClimaticos::getRegiao, Collectors.averagingDouble(extrator)));

        mediaPorRegiao.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(entry -> System.out.printf("   - %s: %.2f\n", entry.getKey(), entry.getValue()));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("--- Consumidor de Dados para Dashboard (RabbitMQ) ---");
        System.out.println("Escolha a chave de ligação (binding key):");
        System.out.println("1. Todas as regiões (#)");
        System.out.println("2. Apenas Norte (norte)");
        System.out.println("3. Apenas Sul (sul)");
        System.out.println("4. Apenas Leste (leste)");
        System.out.println("5. Apenas Oeste (oeste)");
        System.out.print("Digite sua opção: ");

        String bindingKey;
        try (Scanner scanner = new Scanner(System.in)) {
            int opcao = scanner.nextInt();
            if (opcao == 1) {
                bindingKey = "#";
            } else if (opcao == 2) {
                bindingKey = "norte";
            } else if (opcao == 3) {
                bindingKey = "sul";
            } else if (opcao == 4) {
                bindingKey = "leste";
            } else if (opcao == 5) {
                bindingKey = "oeste";
            } else {
                System.out.println("Opção inválida. Usando a chave padrão para todas as regiões (#).");
                bindingKey = "#";
            }
        }

        ConsumidorBase consumidor = new ConsumidorBase();
        consumidor.iniciar(bindingKey);
    }
}
