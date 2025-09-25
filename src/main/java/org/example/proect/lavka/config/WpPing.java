package org.example.proect.lavka.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "wp.ping.enabled", havingValue = "true", matchIfMissing = true)
class WpPing {
    private final JdbcTemplate wpJdbc;
    WpPing(@Qualifier("wpJdbcTemplate") JdbcTemplate wpJdbc) { this.wpJdbc = wpJdbc; }

    @PostConstruct
    void ping() {
        Integer one = wpJdbc.queryForObject("SELECT 1", Integer.class);
        Long cnt = wpJdbc.queryForObject("SELECT COUNT(*) FROM wp_posts", Long.class);
        System.out.println("WP posts count = " + cnt);
    }
}