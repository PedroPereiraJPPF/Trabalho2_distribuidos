package org.pd.gateway;

import java.util.Locale;

public class DadosClimaticos {
    private final String regiao;
    private final double pressao;
    private final double radiacao;
    private final double temperatura;
    private final double umidade;
    private final long timestamp;

    public DadosClimaticos(String regiao, double pressao, double radiacao, double temperatura, double umidade) {
        this.regiao = regiao;
        this.pressao = pressao;
        this.radiacao = radiacao;
        this.temperatura = temperatura;
        this.umidade = umidade;
        this.timestamp = System.currentTimeMillis();
    }

    public String getRegiao() { return regiao; }
    public double getPressao() { return pressao; }
    public double getRadiacao() { return radiacao; }
    public double getTemperatura() { return temperatura; }
    public double getUmidade() { return umidade; }
    public long getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return String.format("[%s | %.2f | %.2f | %.2f | %.2f]",
                regiao, temperatura, umidade, pressao, radiacao);
    }

    public String toJson() {
        return String.format(Locale.US,
                "{\"regiao\":\"%s\", \"temperatura\":%.2f, \"umidade\":%.2f, \"pressao\":%.2f, \"radiacao\":%.2f, \"timestamp\":%d}",
                this.regiao, this.temperatura, this.umidade, this.pressao, this.radiacao, this.timestamp);
    }
}