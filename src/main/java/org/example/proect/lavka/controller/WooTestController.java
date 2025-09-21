package org.example.proect.lavka.controller;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.service.StockSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class WooTestController {

    private final RestTemplate wooRestTemplate; // бин мы сделали в WooCommerceConfig
    private final String baseUrl = "https://kreul.com.ua/wp-json/wc/v3";

    @GetMapping("/test/woo-products")
    public ResponseEntity<?> getProducts() {
        // тянем первые 5 товаров
        String url = baseUrl + "/products?per_page=5&page=1";

        ResponseEntity<Object[]> response = wooRestTemplate.getForEntity(url, Object[].class);

        // вернём как есть, просто массив JSON объектов
        return ResponseEntity.ok(Map.of(
                "count", response.getBody() != null ? response.getBody().length : 0,
                "data", response.getBody()
        ));
    }

    @GetMapping("/test/woo-ping")
    public Map<String, Object> ping() {
        var resp = wooRestTemplate.getForEntity("https://kreul.com.ua/wp-json/", Map.class);
        return Map.of(
                "ok", resp.getStatusCode().is2xxSuccessful(),
                "namespacesCount", ((java.util.Map<?,?>)resp.getBody()).get("namespaces") != null
        );
    }

    @GetMapping("/test/woo-by-sku")
    public Object[] findBySku(@RequestParam String sku) {
        String url = baseUrl + "/products?sku={sku}";
        return wooRestTemplate.getForObject(url, Object[].class, sku);
    }
}