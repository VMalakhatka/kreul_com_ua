package org.example.proect.lavka.controller;

import org.example.proect.lavka.dao.folio.FolioCurrentNetworkStockDao;
import org.example.proect.lavka.property.LavkaApiProperties;
import org.example.proect.lavka.service.folio.FolioAssemblyGraph;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController
@RequestMapping("/admin/folio/product-analytics")
public class FolioCurrentNetworkStockController {
    private final FolioCurrentNetworkStockDao dao;
    private final LavkaApiProperties properties;
    public FolioCurrentNetworkStockController(FolioCurrentNetworkStockDao dao, LavkaApiProperties properties) { this.dao = dao; this.properties = properties; }
    public record Request(String sourceDatabase, List<Integer> warehouseIds, List<Integer> analysisWarehouseIds, List<String> rootSkus) {}
    @PostMapping("/current-network-stock")
    public ResponseEntity<FolioCurrentNetworkStockDao.Capture> read(@RequestBody Request request,
            @RequestHeader(value = "X-Auth-Token", required = false) String token) {
        String expected = properties.getToken();
        if (expected == null || expected.isBlank()) return ResponseEntity.status(503).build();
        if (token == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8)))
            return ResponseEntity.status(401).build();
        if (request == null || !List.of("Paint_Ua", "Paint_Rus").contains(Objects.toString(request.sourceDatabase(), ""))
                || !validIds(request.warehouseIds()) || !validIds(request.analysisWarehouseIds())
                || !request.warehouseIds().containsAll(request.analysisWarehouseIds()) || !request.warehouseIds().contains(7)
                || request.rootSkus() == null || request.rootSkus().isEmpty() || request.rootSkus().size() > 10000
                || request.rootSkus().stream().anyMatch(s -> s == null || s.isBlank() || s.length() > 100)) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(dao.read(request.sourceDatabase(),
                request.warehouseIds().stream().distinct().sorted().toList(), request.analysisWarehouseIds().stream().distinct().sorted().toList(),
                request.rootSkus().stream().map(FolioAssemblyGraph::key).distinct().sorted().toList()));
    }
    private static boolean validIds(List<Integer> ids) { return ids != null && !ids.isEmpty() && ids.size() <= 100 && ids.stream().allMatch(id -> id != null && id > 0); }
}
