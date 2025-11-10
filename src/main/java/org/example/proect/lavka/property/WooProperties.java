package org.example.proect.lavka.property;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "woocommerce")
public class WooProperties {
    private String baseUrl;
    private String baseLavkaUrl;
    private String key;
    private String secret;
    private String lang;
    private int perPage = 100;
    private int timeoutMs = 15000;
    private int maxBatchSize =25;
}