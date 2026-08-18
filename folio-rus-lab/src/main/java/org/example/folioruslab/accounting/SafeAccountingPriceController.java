package org.example.folioruslab.accounting;

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
@RequestMapping("/api/v1/accounting-prices/safe-preview")
@Tag(
        name = "Paint_Rus safe accounting-price preview",
        description = "Полный проход отдельной LAVKA-процедурой: один SKU и один rollback"
)
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
public final class SafeAccountingPriceController {

    private final SafeAccountingPricePreviewService service;

    public SafeAccountingPriceController(SafeAccountingPricePreviewService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(
            summary = "Запустить полный безопасный preview склада Paint_Rus",
            description = "Перечисляет SKU склада и вызывает LAVKA_I_UCHET_TOVAR_SAFE "
                    + "для каждого SKU в отдельной транзакции с обязательным rollback."
    )
    public ResponseEntity<SafeAccountingPricePreviewStatus> start(
            @Valid @RequestBody SafeAccountingPricePreviewRequest request
    ) {
        return ResponseEntity.accepted().body(service.start(request));
    }

    @GetMapping("/status")
    @Operation(summary = "Получить состояние полного безопасного preview Paint_Rus")
    public SafeAccountingPricePreviewStatus status() {
        return service.status();
    }
}
