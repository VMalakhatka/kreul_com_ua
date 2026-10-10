package org.example.proect.lavka.controller;

import org.example.proect.lavka.dao.folio.FolioProductPackagingDao;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@RestController
@RequestMapping("/admin/folio/product-packaging")
public class FolioProductPackagingController {
    private final FolioProductPackagingDao dao;
    @Value("${folio.customer-import.token:${FOLIO_CUSTOMER_IMPORT_TOKEN:}}")
    private String token;
    public FolioProductPackagingController(FolioProductPackagingDao dao) { this.dao=dao; }
    public record Request(List<String> skus) {}
    @PostMapping
    public ResponseEntity<List<FolioProductPackagingDao.Item>> read(@RequestBody Request request,
            @RequestHeader(value="X-Auth-Token",required=false) String supplied) {
        if(token==null || token.isBlank())return ResponseEntity.status(503).build();
        if(supplied==null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
            return ResponseEntity.status(401).build();
        if(request==null || request.skus()==null || request.skus().isEmpty() || request.skus().size()>500
                || request.skus().stream().anyMatch(s->s==null || s.isBlank() || s.length()>100))
            return ResponseEntity.badRequest().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(dao.read(request.skus().stream().distinct().toList()));
    }
}
