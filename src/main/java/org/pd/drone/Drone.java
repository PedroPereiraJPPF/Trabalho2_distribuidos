package org.pd.drone;

import org.eclipse.paho.client.mqttv3.*;

import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Drone {

    private final String broker = "tcp://localhost:1883";
    private MqttClient clienteMqtt;
    private final Random random = new Random();

    private final String regiao;
    private final String delimitador;
    private final String formato;

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    public Drone(String regiao) {
        this.regiao = regiao;

        switch (regiao.toLowerCase()) {
            case "norte":
                this.delimitador = "-";
                this.formato = "%s-%s-%s-%s";
                break;
            case "sul":
                this.delimitador = "; ";
                this.formato = "(%s; %s; %s; %s)";
                break;
            case "leste":
                this.delimitador = ", ";
                this.formato = "{%s, %s, %s, %s}";
                break;
            case "oeste":
                this.delimitador = "#";
                this.formato = "%s#%s#%s#%s";
                break;
            default:
                throw new IllegalArgumentException("Região inválida: " + regiao);
        }
    }

    public void conectar() {
        try {
            String idCliente = "drone-" + this.regiao + "-" + System.currentTimeMillis();
            this.clienteMqtt = new MqttClient(broker, idCliente);
            MqttConnectOptions opcoesConexao = new MqttConnectOptions();
            opcoesConexao.setCleanSession(true);

            System.out.println("Drone [" + regiao + "] conectando ao broker: " + broker);
            this.clienteMqtt.connect(opcoesConexao);
            System.out.println("Drone [" + regiao + "] conectado!");

        } catch (MqttException e) {
            System.err.println("Erro ao conectar o Drone [" + regiao + "]: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private String gerarDadosClimaticos() {
        double pressao = 950 + (1050 - 950) * random.nextDouble();
        double radiacao = 100 + (1000 - 100) * random.nextDouble();
        double temperatura = 15 + (40 - 15) * random.nextDouble();
        double umidade = 30 + (80 - 30) * random.nextDouble();

        return String.format(formato,
                String.format("%.2f", pressao),
                String.format("%.2f", radiacao),
                String.format("%.2f", temperatura),
                String.format("%.2f", umidade)
        );
    }

    public void iniciarEnvioDeDados() {
        if (clienteMqtt == null || !clienteMqtt.isConnected()) {
            System.err.println("Drone [" + regiao + "] não está conectado. Não é possível enviar dados.");
            return;
        }

        Runnable envioTask = () -> {
            try {
                String dados = gerarDadosClimaticos();
                MqttMessage mensagem = new MqttMessage(dados.getBytes());
                mensagem.setQos(0);

                String topico = "drones/" + this.regiao + "/dados";
                clienteMqtt.publish(topico, mensagem);
                System.out.println("Drone [" + this.regiao + "] publicou em [" + topico + "]: " + dados);

            } catch (MqttException e) {
                System.err.println("Erro ao publicar mensagem do Drone [" + regiao + "]: " + e.getMessage());
                if (e.getReasonCode() == MqttException.REASON_CODE_CLIENT_NOT_CONNECTED) {
                    executor.shutdown();
                }
            }
        };

        int intervalo = 2000 + random.nextInt(3001);
        executor.scheduleAtFixedRate(envioTask, 0, intervalo, TimeUnit.MILLISECONDS);
    }

    public void parar() throws MqttException {
        System.out.println("Finalizando drone [" + regiao + "]...");
        executor.shutdown();
        if (clienteMqtt.isConnected()) {
            clienteMqtt.disconnect();
        }
        clienteMqtt.close();
        System.out.println("Drone [" + regiao + "] finalizado.");
    }

    public static void main(String[] args) {
        System.out.println("Iniciando simulação dos Drones...");
        Drone droneNorte = new Drone("norte");
        Drone droneSul = new Drone("sul");
        Drone droneLeste = new Drone("leste");
        Drone droneOeste = new Drone("oeste");

        droneNorte.conectar();
        droneSul.conectar();
        droneLeste.conectar();
        droneOeste.conectar();

        System.out.println("\nTodos os drones conectados. Pressione ENTER para iniciar a coleta de dados.");
        try (Scanner scanner = new Scanner(System.in)) {
            scanner.nextLine();
        }

        System.out.println("Iniciando envio de dados...\n");
        droneNorte.iniciarEnvioDeDados();
        droneSul.iniciarEnvioDeDados();
        droneLeste.iniciarEnvioDeDados();
        droneOeste.iniciarEnvioDeDados();

        try {
            System.out.println("\nSimulação rodando por 3 minutos. Pressione CTRL+C para parar antes.");
            Thread.sleep(180000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                droneNorte.parar();
                droneSul.parar();
                droneLeste.parar();
                droneOeste.parar();
            } catch (MqttException e) {
                e.printStackTrace();
            }
        }
    }
}