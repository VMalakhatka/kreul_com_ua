package org.example.folioruslab.procedure;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.folioruslab.config.OpenApiConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/procedure-fingerprints")
@Tag(
        name = "Paint_Rus procedure fingerprints",
        description = "Отпечатки разрешённых штатных процедур без публикации SQL-текста"
)
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
public final class ProcedureFingerprintController {

    private final ProcedureFingerprintService service;

    public ProcedureFingerprintController(ProcedureFingerprintService service) {
        this.service = service;
    }

    @GetMapping("/accounting-prices")
    @Operation(
            summary = "Получить отпечатки штатных процедур учётных цен",
            description = "Возвращает SHA-256 и размеры только I_UCHET_1_TOVAR/I_UCHET_TOVAR; "
                    + "исходный SQL наружу не передаётся."
    )
    public ProcedureFingerprintResponse accountingPrices() {
        return service.capture();
    }

    @GetMapping("/payments")
    @Operation(
            summary = "Получить отпечатки четырёх штатных платёжных процедур",
            description = "Только INSERT_PLAT, i_ins_plat, I_GET_LAST_PLAT_NUM, I_TEST_NUM_PLAT; "
                    + "исходный SQL не возвращается, процедуры не выполняются."
    )
    public ProcedureFingerprintResponse payments() {
        return service.capturePayments();
    }
}
