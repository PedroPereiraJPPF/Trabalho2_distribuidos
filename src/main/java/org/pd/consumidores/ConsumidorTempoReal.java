package org.pd.consumidores;

import org.eclipse.paho.client.mqttv3.*;
import java.util.Scanner;

public class ConsumidorTempoReal {

    private final String broker = "tcp://localhost:1883";
    private MqttClient clienteMqtt;

    private String getRegiaoEmoji(String regiao) {
        switch (regiao.toLowerCase()) {
            case "norte":
                return "[NORTE]";
            case "sul":
                return "[SUL]";
            case "leste":
                return "[LESTE]";
            case "oeste":
                return "[OESTE]";
            default:
                return "[REGIAO]";
        }
    }

    public void iniciar(String filtroTopico) {
        try {
            String idCliente = "consumidor-tempo-real-" + System.currentTimeMillis();
            clienteMqtt = new MqttClient(broker, idCliente);

            MqttConnectOptions opcoesConexao = new MqttConnectOptions();
            opcoesConexao.setCleanSession(true);

            clienteMqtt.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    System.err.println("Conexão com o broker perdida! " + cause.getMessage());
                }

                @Override
                public void messageArrived(String topico, MqttMessage mensagem) throws Exception {
                    String timestamp = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
                    String payload = new String(mensagem.getPayload());
                    String regiao = topico.split("/")[2];

                    System.out.println(
                            "[" + timestamp + "] Tempo Real - " + ConsumidorTempoReal.this.getRegiaoEmoji(regiao) + " "
                                    + regiao.toUpperCase() + ": " + payload);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });

            System.out.println("Conectando ao broker MQTT: " + broker);
            clienteMqtt.connect(opcoesConexao);
            System.out.println("Conectado!");

            clienteMqtt.subscribe(filtroTopico, 0);
            System.out.println("Inscrito no tópico: " + filtroTopico);
            System.out.println("\nAguardando dados em tempo real... Pressione CTRL+C para sair.");

        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        System.out.println("--- Consumidor de Dados em Tempo Real ---");
        System.out.println("Escolha o tópico para assinar:");
        System.out.println("1. Todas as regiões (gateway/dados_processados/#)");
        System.out.println("2. Região Norte (gateway/dados_processados/norte)");
        System.out.println("3. Região Sul (gateway/dados_processados/sul)");
        System.out.println("4. Região Leste (gateway/dados_processados/leste)");
        System.out.println("5. Região Oeste (gateway/dados_processados/oeste)");
        System.out.print("Digite sua opção: ");

        String topicoEscolhido;
        try (Scanner scanner = new Scanner(System.in)) {
            int opcao = scanner.nextInt();
            switch (opcao) {
                case 1:
                    topicoEscolhido = "gateway/dados_processados/#";
                    break;
                case 2:
                    topicoEscolhido = "gateway/dados_processados/norte";
                    break;
                case 3:
                    topicoEscolhido = "gateway/dados_processados/sul";
                    break;
                case 4:
                    topicoEscolhido = "gateway/dados_processados/leste";
                    break;
                case 5:
                    topicoEscolhido = "gateway/dados_processados/oeste";
                    break;
                default:
                    System.out.println("Opção inválida. Usando o tópico padrão para todas as regiões.");
                    topicoEscolhido = "gateway/dados_processados/#";
            }
        }

        ConsumidorTempoReal consumidor = new ConsumidorTempoReal();
        consumidor.iniciar(topicoEscolhido);
    }
}
