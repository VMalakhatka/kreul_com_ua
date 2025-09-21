package org.example.proect.lavka.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class WpPing {
    private final JdbcTemplate wpJdbc;
    WpPing(@Qualifier("wpJdbcTemplate") JdbcTemplate wpJdbc) { this.wpJdbc = wpJdbc; }

    @PostConstruct
    void ping() {
        Long cnt = wpJdbc.queryForObject("SELECT COUNT(*) FROM wp_posts", Long.class);
        System.out.println("WP posts count = " + cnt);
    }
}