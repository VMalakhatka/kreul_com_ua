package org.example.proect.lavka.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class HealthzController {
    @GetMapping({"/", "/healthz"})
    public Map<String, String> ok() {
        return Map.of("status","UP");
    }
}