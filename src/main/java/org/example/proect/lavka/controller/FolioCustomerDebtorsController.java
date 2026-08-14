package org.example.proect.lavka.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dto.folio.FolioBalanceSnapshotStatusResponse;
import org.example.proect.lavka.dto.folio.FolioCustomerDebtorsResponse;
import org.example.proect.lavka.service.folio.FolioCustomerBalanceSnapshotService;
import org.example.proect.lavka.service.folio.FolioCustomerDebtorsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/folio/customer-debtors")
public class FolioCustomerDebtorsController {

    private final FolioCustomerDebtorsService service;
    private final FolioCustomerBalanceSnapshotService snapshotService;

    @GetMapping
    @Operation(
            summary = "Список должников ФОЛИО",
            description = "Быстро читает активный MariaDB-снимок и возвращает клиентов, у которых payableNow строго больше minPayable"
    )
    public ResponseEntity<FolioCustomerDebtorsResponse> get(
            @Parameter(description = "Строгий порог payableNow; по умолчанию 100.00", example = "100.00")
            @RequestParam(required = false) BigDecimal minPayable,
            @Parameter(description = "Поиск по краткому или полному имени партнёра")
            @RequestParam(required = false) String q,
            @Parameter(description = "Коды типов через запятую или all", example = "П,Д,К")
            @RequestParam(required = false) String types,
            @Parameter(description = "Размер страницы от 1 до 200", example = "50")
            @RequestParam(required = false) Integer limit,
            @Parameter(description = "Смещение страницы от 0", example = "0")
            @RequestParam(required = false) Integer offset,
            @Parameter(description = "Поддерживается payableNow_desc", example = "payableNow_desc")
            @RequestParam(required = false) String sort) {
        return ResponseEntity.ok(service.get(minPayable, q, types, limit, offset, sort));
    }

    @GetMapping("/snapshot/status")
    @Operation(
            summary = "Статус снимка задолженности клиентов ФОЛИО",
            description = "Показывает состояние последней фоновой генерации; список продолжает использовать предыдущую активную генерацию до полного успеха новой"
    )
    public ResponseEntity<FolioBalanceSnapshotStatusResponse> snapshotStatus() {
        return ResponseEntity.ok(snapshotService.status(false));
    }

    @PostMapping("/snapshot/refresh")
    @Operation(
            summary = "Запустить полный пересчёт снимка задолженности",
            description = "Запускает фоновую задачу и сразу возвращает 202; текущий активный снимок не меняется до успешного завершения"
    )
    public ResponseEntity<FolioBalanceSnapshotStatusResponse> refreshSnapshot() {
        boolean accepted = snapshotService.requestRefresh("MANUAL");
        return ResponseEntity.accepted().body(snapshotService.status(accepted));
    }
}
