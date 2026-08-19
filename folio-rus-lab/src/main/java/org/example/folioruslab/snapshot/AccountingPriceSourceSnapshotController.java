package org.example.folioruslab.snapshot;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.example.folioruslab.config.OpenApiConfig;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounting-prices/source-snapshot")
@Tag(
        name = "Paint_Rus accounting-price source snapshot",
        description = "Read-only SHA-256 snapshot of the Folio inputs that affect accounting prices"
)
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
public final class AccountingPriceSourceSnapshotController {

    private final AccountingPriceSourceSnapshotService service;

    public AccountingPriceSourceSnapshotController(
            AccountingPriceSourceSnapshotService service
    ) {
        this.service = service;
    }

    @PostMapping
    @Operation(
            summary = "Снять или сравнить полный снимок источников учётной цены",
            description = "Первый запуск сохраняет baseline в памяти лаборатории. "
                    + "Следующий запуск сравнивает SHA-256 каждого SKU и сообщает "
                    + "изменённые, новые и удалённые карточки. Данные ФОЛІО не изменяются."
    )
    public ResponseEntity<AccountingPriceSourceSnapshotStatus> start(
            @Valid @RequestBody AccountingPriceSourceSnapshotRequest request
    ) {
        return ResponseEntity.accepted().body(service.start(request));
    }

    @GetMapping("/status")
    @Operation(summary = "Получить состояние построения снимка")
    public AccountingPriceSourceSnapshotStatus status() {
        return service.status();
    }
}
