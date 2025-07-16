package org.pd.gateway;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.eclipse.paho.client.mqttv3.*;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

public class Gateway {
    private final String mqttBroker = "tcp://localhost:1883";
    private MqttClient clienteMqttEntrada;
    private MqttClient clienteMqttSaida;

    private final String rabbitHost = "localhost";
    private Connection rabbitConnection;
    private Channel rabbitChannel;
    private static final String RABBIT_EXCHANGE_NAME = "gateway_dados_topic";

    private int totalMensagensProcessadas = 0;

    public Gateway() {
    }

    public void iniciar() {
        conectarMqttEntrada();
        conectarMqttSaida();
        conectarRabbitMQ();
    }

    private void conectarRabbitMQ() {
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(rabbitHost);
            this.rabbitConnection = factory.newConnection();
            this.rabbitChannel = this.rabbitConnection.createChannel();

            this.rabbitChannel.exchangeDeclare(RABBIT_EXCHANGE_NAME, "topic");
            System.out.println("Gateway conectado ao RabbitMQ e exchange '" + RABBIT_EXCHANGE_NAME + "' declarada.");
        } catch (IOException | TimeoutException e) {
            System.err.println("Erro ao conectar ao RabbitMQ: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void conectarMqttEntrada() {
        try {
            String idCliente = "gateway-consumidor-" + System.currentTimeMillis();
            clienteMqttEntrada = new MqttClient(mqttBroker, idCliente);
            MqttConnectOptions opcoesConexao = new MqttConnectOptions();
            opcoesConexao.setCleanSession(true);

            clienteMqttEntrada.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    System.err.println("Gateway (entrada) perdeu a conexão com o broker. Causa: " + cause.getMessage());
                }

                @Override
                public void messageArrived(String topico, MqttMessage mensagem) throws Exception {
                    String payload = new String(mensagem.getPayload());
                    String regiao = topico.split("/")[1];

                    System.out.println("📡 Gateway recebeu de [" + regiao + "]: " + payload);
                    DadosClimaticos dados = processarPayload(regiao, payload);

                    if (dados != null) {
                        totalMensagensProcessadas++;
                        System.out.println(
                                "   ✅ Dados Processados (#" + totalMensagensProcessadas + "): " + dados.toString());

                        boolean mqttOk = publicarViaMqtt(dados);
                        boolean rabbitOk = publicarViaRabbitMQ(dados);

                        if (mqttOk && rabbitOk) {
                            System.out.println("   🟢 Dados publicados com sucesso em ambos os protocolos");
                        } else if (!mqttOk && !rabbitOk) {
                            System.err.println("   🔴 Falha ao publicar em ambos os protocolos");
                        } else {
                            System.out.println("   🟡 Publicação parcial - verificar logs acima");
                        }
                    } else {
                        System.err.println("   ❌ Falha no processamento dos dados de [" + regiao + "]");
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });

            System.out.println("Gateway (entrada) conectando ao broker: " + mqttBroker);
            clienteMqttEntrada.connect(opcoesConexao);
            System.out.println("Gateway (entrada) conectado!");

            final String topicoDrones = "drones/#";
            clienteMqttEntrada.subscribe(topicoDrones, 0);
            System.out.println("Gateway inscrito no tópico: " + topicoDrones);

        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    private void conectarMqttSaida() {
        try {
            String idCliente = "gateway-publicador-" + System.currentTimeMillis();
            clienteMqttSaida = new MqttClient(mqttBroker, idCliente);
            MqttConnectOptions opcoesConexao = new MqttConnectOptions();
            opcoesConexao.setCleanSession(true);

            System.out.println("Gateway (saída) conectando ao broker: " + mqttBroker);
            clienteMqttSaida.connect(opcoesConexao);
            System.out.println("Gateway (saída) conectado!");
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    public boolean publicarViaMqtt(DadosClimaticos dados) {
        if (clienteMqttSaida == null || !clienteMqttSaida.isConnected()) {
            System.err.println("Gateway (saída) não está conectado, impossível publicar.");
            return false;
        }

        try {
            String topicoSaida = "gateway/dados_processados/" + dados.getRegiao();
            String payloadSaida = dados.toString();

            MqttMessage mensagem = new MqttMessage(payloadSaida.getBytes());
            mensagem.setQos(0);

            clienteMqttSaida.publish(topicoSaida, mensagem);
            System.out.println("   >> 📤 Publicado em [MQTT]: " + topicoSaida + " -> " + payloadSaida);
            return true;

        } catch (MqttException e) {
            System.err.println("Erro ao publicar via MQTT: " + e.getMessage());
            return false;
        }
    }

    private DadosClimaticos processarPayload(String regiao, String payload) {
        String[] valores;
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
                System.err.println("Região desconhecida: " + regiao);
                return null;
        }
        try {
            double pressao = Double.parseDouble(valores[0].replace(",", "."));
            double radiacao = Double.parseDouble(valores[1].replace(",", "."));
            double temperatura = Double.parseDouble(valores[2].replace(",", "."));
            double umidade = Double.parseDouble(valores[3].replace(",", "."));
            return new DadosClimaticos(regiao, pressao, radiacao, temperatura, umidade);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            System.err.println("Erro ao fazer parsing: " + payload);
            return null;
        }
    }

    public boolean publicarViaRabbitMQ(DadosClimaticos dados) {
        if (rabbitChannel == null || !rabbitChannel.isOpen()) {
            System.err.println("Gateway (RabbitMQ) não está conectado, impossível publicar.");
            return false;
        }
        try {
            String routingKey = dados.getRegiao();
            String mensagemJson = dados.toJson();

            rabbitChannel.basicPublish(
                    RABBIT_EXCHANGE_NAME,
                    routingKey,
                    null,
                    mensagemJson.getBytes("UTF-8"));

            System.out.println("   >> 🐰 Publicado em [RabbitMQ]: " + routingKey + " -> " + mensagemJson);
            return true;

        } catch (IOException e) {
            System.err.println("Erro ao publicar via RabbitMQ: " + e.getMessage());
            return false;
        }
    }

    public void parar() {
        try {
            System.out.println("Encerrando Gateway...");
            if (clienteMqttEntrada != null && clienteMqttEntrada.isConnected())
                clienteMqttEntrada.disconnect();
            if (clienteMqttSaida != null && clienteMqttSaida.isConnected())
                clienteMqttSaida.disconnect();
            if (rabbitChannel != null && rabbitChannel.isOpen())
                rabbitChannel.close();
            if (rabbitConnection != null && rabbitConnection.isOpen())
                rabbitConnection.close();
            System.out.println("Gateway finalizado.");
        } catch (MqttException | IOException | TimeoutException e) {
            System.err.println("Erro ao encerrar o Gateway: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        Gateway gateway = new Gateway();
        gateway.iniciar();
        System.out.println("\nGateway em operação. Aguardando e re-publicando dados...");
    }
}
