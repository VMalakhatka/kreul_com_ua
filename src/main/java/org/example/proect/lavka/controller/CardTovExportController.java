package org.example.proect.lavka.controller;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.service.CardTovExportService;
import org.example.proect.lavka.service.CardTovExportService.PageResult;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/admin/export/card-tov")
public class CardTovExportController {

    private final CardTovExportService service;

    /**
     * Пейджинг по вьюхе card_tov (у тебя уже отсортировано по sku).
     * Пример: GET /admin/export/card-tov?limit=500&after=ABC-001
     */
    @GetMapping
    public ResponseEntity<PageResult> page(
            @RequestParam(name = "after", required = false) String after,
            @RequestParam(name = "limit", required = false, defaultValue = "500") Integer limit
    ) {
        int safeLimit = (limit == null || limit <= 0) ? 500 : Math.min(limit, 1000);
        PageResult result = service.page(after, safeLimit);
        return ResponseEntity.ok(result);
    }
}