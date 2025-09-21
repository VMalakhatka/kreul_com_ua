package org.example.proect.lavka.config;


import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.example.proect.lavka.property.WpDataSourceProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;
import org.springframework.lang.NonNull;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.net.URI;
import java.util.List;



@Configuration
public class WooCommerceConfig {

    @Bean
    public RestTemplate wooRestTemplate(
            @Value("${woocommerce.timeoutMs:15000}") int timeoutMs,
            @Value("${woocommerce.key}") String key,
            @Value("${woocommerce.secret}") String secret
    ) {
        var rt = new RestTemplate();

        var rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(timeoutMs);
        rf.setReadTimeout(timeoutMs);
        rt.setRequestFactory(rf);

        // Перехватчик: добавляет consumer_key/consumer_secret в query
        ClientHttpRequestInterceptor auth = (request, body, execution) -> {
            URI u = request.getURI();
            String sep = (u.getQuery() == null || u.getQuery().isEmpty()) ? "?" : "&";
            URI withAuth = URI.create(u + sep + "consumer_key=" + key + "&consumer_secret=" + secret);

            var wrapped = new HttpRequestWrapper(request) {
                @Override
                @NonNull
                public URI getURI() {
                    return withAuth;
                }
            };

            return execution.execute(wrapped, body);
        };

        rt.setInterceptors(List.of(auth));
        return rt;
    }
    @FlywayDataSource
    // === WordPress (MariaDB/MySQL) ===
    @Bean(name = "wpDataSource")
    public DataSource wpDataSource(WpDataSourceProperties props) {
        HikariDataSource ds = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName(props.getDriverClassName())
                .url(props.getUrl() + (props.getUrl().contains("?") ? "&" : "?") +
                        "useUnicode=true&characterEncoding=utf8")
                .username(props.getUsername())
                .password(props.getPassword())
                .build();
        ds.setConnectionInitSql("SET NAMES utf8mb4");
        return ds;
    }

    @Bean(name = "wpJdbcTemplate")
    public JdbcTemplate wpJdbcTemplate(@Qualifier("wpDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}