package org.example.proect.lavka.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsCapabilitiesRequest;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsCapabilitiesResponse;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryRequest;
import org.example.proect.lavka.dto.folio.FolioProductAnalyticsQueryResponse;
import org.example.proect.lavka.service.folio.FolioProductAnalyticsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/folio/product-analytics")
@Tag(name = "folio-product-analytics-controller",
        description = "Сценарии товарной аналитики по активным снимкам MariaDB")
public class FolioProductAnalyticsController {

    private final FolioProductAnalyticsService service;

    @PostMapping("/capabilities")
    @Operation(
            summary = "Получить доступные аналитические фильтры и справочники",
            description = "Не обращается к ФОЛІО: проверяет активные snapshot schema v4 и строит справочники по MariaDB.")
    public ResponseEntity<FolioProductAnalyticsCapabilitiesResponse> capabilities(
            @Valid @RequestBody FolioProductAnalyticsCapabilitiesRequest request) {
        return ResponseEntity.ok(service.capabilities(request));
    }

    @PostMapping("/query")
    @Operation(
            summary = "Построить многоскладской отчёт товарной аналитики",
            description = "Фильтрует активные snapshot schema v4 в MariaDB, возвращает итоги всей совокупности, SKU, GTIN, товар в пути, разбивку по складам, ABC и cursor pagination.")
    public ResponseEntity<FolioProductAnalyticsQueryResponse> query(
            @Valid @RequestBody FolioProductAnalyticsQueryRequest request) {
        return ResponseEntity.ok(service.query(request));
    }
}
