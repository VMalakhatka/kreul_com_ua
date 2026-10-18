package org.example.proect.lavka.controller;

import org.example.proect.lavka.dao.folio.FolioProductCardDao;
import org.example.proect.lavka.property.LavkaApiProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/folio/product-cards")
public class FolioProductCardController {
    private final FolioProductCardDao dao;
    private final LavkaApiProperties properties;
    public FolioProductCardController(FolioProductCardDao dao, LavkaApiProperties properties) { this.dao=dao; this.properties=properties; }
    public record Request(String mode, List<String> skus) {}
    @PostMapping("/read")
    public ResponseEntity<?> read(@RequestBody Request request,
            @RequestHeader(value="X-Auth-Token", required=false) String token) {
        String expected=properties.getToken();
        if (expected==null || expected.isBlank()) return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).build();
        if (token==null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))
            return ResponseEntity.status(401).cacheControl(CacheControl.noStore()).build();
        if (request==null || request.mode()==null || !List.of("direct","view","compare").contains(request.mode())
                || request.skus()==null || request.skus().size()>500
                || request.skus().stream().anyMatch(s -> s==null || s.isBlank() || s.length()>100 || s.chars().anyMatch(Character::isISOControl)))
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("ok",false,"error","INVALID_SELECTION"));
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(dao.read(request.mode(),request.skus().stream().distinct().toList()));
        } catch (RuntimeException e) {
            // Never return JDBC exceptions (SQL, server names or credentials) to the browser.
            for (Throwable cause=e; cause!=null; cause=cause.getCause()) {
                if (cause instanceof java.sql.SQLException sql && sql.getErrorCode()==229)
                    return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(Map.of("ok",false,"error","CARD_QUERY_ACCESS_DENIED"));
            }
            return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(Map.of("ok",false,"error","CARD_READ_FAILED"));
        }
    }
}
