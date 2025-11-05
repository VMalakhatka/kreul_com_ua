package org.example.proect.lavka.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class MssqlHealthIndicator implements HealthIndicator {
    private static final Logger log = LoggerFactory.getLogger(MssqlHealthIndicator.class);
    private final JdbcTemplate jdbc;

    public MssqlHealthIndicator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Health health() {
        try {
            Integer v = jdbc.queryForObject("SELECT 1", Integer.class);
            return Health.up()
                    .withDetail("ping", v)
                    .withDetail("driver", jdbc.getDataSource() != null
                            ? jdbc.getDataSource().getClass().getSimpleName() : "unknown")
                    .build();
        } catch (Exception e) {
            log.warn("[sync.db.health] actuator=DOWN cause={}", e.toString());
            return Health.down(e).build();
        }
    }
}