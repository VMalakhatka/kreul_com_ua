package org.example.proect.lavka.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DbPingScheduler {
    private static final Logger log = LoggerFactory.getLogger(DbPingScheduler.class);
    private final JdbcTemplate jdbc;

    public DbPingScheduler(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Каждые 30 секунд. Первое выполнение через 5 сек после старта.
    @Scheduled(initialDelay = 5000, fixedDelay = 30000)
    public void ping() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            log.info("[sync.db.health] MSSQL ping=OK");
        } catch (Exception e) {
            log.error("[sync.db.health] MSSQL ping=FAIL {}", e.toString());
        }
    }
}