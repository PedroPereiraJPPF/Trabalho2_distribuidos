package org.pd;

import org.eclipse.paho.client.mqttv3.*;

public class Main {
    public static void main(String[] args) throws Exception {
        String broker = "tcp://localhost:1883";
        String clientId = "testeJava";

        MqttClient client = new MqttClient(broker, clientId);
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);

        System.out.println("Conectando ao broker: " + broker);
        client.connect(options);
        System.out.println("Conectado com sucesso!");

        client.disconnect();
        System.out.println("Desconectado.");
    }
}