package org.example.proect.lavka.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal")
public class InternalPingController {
    private final JdbcTemplate jdbc;
    public InternalPingController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/db-ping")
    public String dbPing() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        return "OK";
    }
}
