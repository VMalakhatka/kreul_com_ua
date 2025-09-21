package org.example.proect.lavka.property;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "wp.datasource")
public class WpDataSourceProperties {
    private String url;
    private String username;
    private String password;
    private String driverClassName;
}