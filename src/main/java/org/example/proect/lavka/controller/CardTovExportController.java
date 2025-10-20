package org.example.proect.lavka.controller;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.service.CardTovExportService;
import org.example.proect.lavka.service.CardTovExportService.PageResult;
import org.example.proect.lavka.service.CardTovExportService.DiffResult;
import org.example.proect.lavka.service.CardTovExportService.ItemHash;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/admin/export/card-tov")
public class CardTovExportController {

    private final CardTovExportService service;

    /** Стандартная постраничная выдача (старая версия) */
    @GetMapping
    public ResponseEntity<PageResult> page(
            @RequestParam(name = "after", required = false) String after,
            @RequestParam(name = "limit", required = false, defaultValue = "500") Integer limit
    ) {
        int safeLimit = (limit == null || limit <= 0) ? 500 : Math.min(limit, 1000);
        PageResult result = service.page(after, safeLimit);
        return ResponseEntity.ok(result);
    }

    /** Новый дифф-эндпоинт: сверка Woo ⟷ MSSQL */
    @PostMapping("/diff")
    public ResponseEntity<DiffResult> diffPage(
            @RequestParam(name = "after", required = false) String after,
            @RequestParam(name = "limit", required = false, defaultValue = "500") Integer limit,
            @RequestBody(required = false) List<ItemHash> items
    ) {
        int safeLimit = (limit == null || limit <= 0) ? 500 : Math.min(limit, 1000);
        DiffResult result = service.diffPage(after, safeLimit, items);
        return ResponseEntity.ok(result);
    }
}