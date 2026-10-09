package org.example.proect.lavka.controller;

import org.example.proect.lavka.dao.folio.FolioAssemblyDao;
import org.example.proect.lavka.property.LavkaApiProperties;
import org.example.proect.lavka.service.folio.FolioAssemblyGraph;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@RestController
@RequestMapping("/admin/folio/product-analytics")
public class FolioAssemblyController {
    private final FolioAssemblyDao dao;
    private final LavkaApiProperties properties;
    public FolioAssemblyController(FolioAssemblyDao dao, LavkaApiProperties properties) { this.dao = dao; this.properties = properties; }
    public record Request(String sourceDatabase, List<Integer> warehouseIds, List<String> rootSkus) {}

    @PostMapping("/assembly-graph")
    public ResponseEntity<FolioAssemblyGraph.Graph> graph(@RequestBody Request request,
            @RequestHeader(value = "X-Auth-Token", required = false) String token) {
        String expected = properties.getToken();
        if (expected == null || expected.isBlank()) return ResponseEntity.status(503).build();
        if (token == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8)))
            return ResponseEntity.status(401).build();
        if (request.sourceDatabase() == null || !List.of("Paint_Ua", "Paint_Rus").contains(request.sourceDatabase())
                || request.warehouseIds() == null || request.warehouseIds().isEmpty() || request.warehouseIds().size() > 100
                || request.warehouseIds().stream().anyMatch(id -> id == null || id <= 0)
                || request.rootSkus() == null || request.rootSkus().isEmpty() || request.rootSkus().size() > 10000
                || request.rootSkus().stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 100))
            return ResponseEntity.badRequest().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(dao.read(request.sourceDatabase(), request.warehouseIds(), request.rootSkus()));
    }
}
