package org.example.proect.lavka.property;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "lavka")
public class LavkaApiProperties {
    private String apiBase;
    private String user;
    private String appPass;
    private String token;
    private int timeoutMs = 15000;
    private int skuChunkSize = 1000;
    private int mssqlMaxParams = 2100;
    private int movementPageSize = 500;
    private int movementMaxDays = 365;
}