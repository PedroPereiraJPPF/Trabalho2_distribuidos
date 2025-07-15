# 🛰️ Simulador de Drones com MQTT e RabbitMQ

Este projeto simula uma rede de drones que coletam dados climáticos e os enviam via **MQTT** (Mosquitto) para posterior consumo. Um gateway (saída) conecta o sistema a uma exchange do **RabbitMQ**, integrando os dados com outros sistemas.

## 📦 Tecnologias utilizadas

- Java 17
- Eclipse Paho MQTT Client (`org.eclipse.paho.client.mqttv3`)
- Docker + Docker Compose
- Mosquitto (MQTT broker)
- RabbitMQ (mensageria)
- SLF4J (logging)

## 📁 Estrutura do Projeto

```
trabalho2distribuidos/
├── docker-compose.yml
├── mosquitto.conf
├── src/
│   └── main/
│       └── java/
│           └── org/
│               └── pd/
│                   └── drone/
│                       └── Drone.java
├── pom.xml
└── README.md
```

## 🚀 Como executar o projeto

### 1. Clone o repositório

```bash
git clone https://github.com/PedroPereiraJPPF/Trabalho2_distribuidos.git
cd trabalho2distribuidos
```

### 2. Inicie os serviços com Docker

```bash
docker-compose up -d
```

Isso iniciará:
- 📡 **Mosquitto** (broker MQTT na porta `1883`)
- 📨 **RabbitMQ** (gerenciador de mensagens com painel em `http://localhost:15672`)

> Usuário/padrão para o painel RabbitMQ (se configurado): `guest` / `guest`

### 3. Compile e execute o drone (fora do Docker)

```bash
mvn clean install
java -cp target/seu-jar-final.jar org.pd.drone.Drone
```

> Certifique-se de ter o Java 17+ instalado.

## 🧠 Como funciona

### 🛰️ Classe `Drone.java`

Cada drone representa uma região do país:

- `norte`, `sul`, `leste`, `oeste`

Cada um possui:
- Um **formato específico** para os dados (com delimitadores diferentes)
- Um gerador randômico de dados:
    - Pressão atmosférica
    - Radiação
    - Temperatura
    - Umidade

Os dados são enviados periodicamente (a cada 2–5 segundos) via MQTT para o tópico:

```
drones/{regiao}/dados
```

Exemplo: `drones/norte/dados`

### 🧩 Integração com RabbitMQ

- Um **gateway** (não detalhado aqui) pode se inscrever nos tópicos MQTT e redirecionar as mensagens para uma exchange `gateway_dados_topic` do RabbitMQ.
- Isso permite acoplamento com microserviços ou persistência de dados.

## 🔧 Arquivos de configuração

### `mosquitto.conf`

```conf
listener 1883
allow_anonymous true
```

Habilita conexões MQTT anônimas na porta 1883.

## 🐳 Docker Compose

```yaml
version: '3'
services:
  mosquitto:
    image: eclipse-mosquitto
    ports:
      - "1883:1883"
    volumes:
      - ./mosquitto.conf:/mosquitto/config/mosquitto.conf

  rabbitmq:
    image: rabbitmq:3.13-management
    ports:
      - "5672:5672"
      - "15672:15672"
```

## 🛠️ Troubleshooting

### ⚠️ SLF4J: No implementation bound

Se aparecer o aviso:

```
SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder".
```

Adicione a dependência de log no `pom.xml`:

```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-simple</artifactId>
    <version>1.7.36</version>
</dependency>
```
## 🧑‍💻 Autor

Desenvolvido por [João Pedro Pereira](https://github.com/PedroPereiraJPPF)

## 📄 Licença

Este projeto está licenciado sob a [MIT License](LICENSE).