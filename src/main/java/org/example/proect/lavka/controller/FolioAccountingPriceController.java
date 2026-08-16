package org.example.proect.lavka.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dto.folio.FolioAccountingPriceFullRecalculationRequest;
import org.example.proect.lavka.dto.folio.FolioAccountingPriceFullStatusResponse;
import org.example.proect.lavka.dto.folio.FolioAccountingPriceNativeFullRequest;
import org.example.proect.lavka.dto.folio.FolioAccountingPriceNativeFullStatusResponse;
import org.example.proect.lavka.dto.folio.FolioAccountingPriceRecalculationRequest;
import org.example.proect.lavka.dto.folio.FolioAccountingPriceRecalculationResponse;
import org.example.proect.lavka.service.folio.FolioAccountingPriceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/folio/accounting-prices")
@Tag(name = "folio-accounting-price-controller", description = "Контроль и штатный перерасчёт учётных цен ФОЛИО")
public class FolioAccountingPriceController {

    private final FolioAccountingPriceService service;

    @PostMapping("/recalculate")
    @Operation(
            summary = "Проверить или пересчитать учётную цену одного товара",
            description = "previewOnly=true выполняет только чтение. Apply вызывает штатный exact rebuild из сохранённых учётных сумм движений ФОЛИО в одной транзакции."
    )
    public ResponseEntity<FolioAccountingPriceRecalculationResponse> recalculate(
            @Valid @RequestBody FolioAccountingPriceRecalculationRequest request) {
        FolioAccountingPriceRecalculationResponse response = service.recalculate(request);
        if (!response.ok() && !request.previewOnly()) {
            return ResponseEntity.status(409).body(response);
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/recalculate/full")
    @Operation(
            summary = "Запустить полный rebuild учётных цен товаров склада",
            description = "Запускает фоновый проход exact rebuild по товарам и сразу возвращает 202. Это не I_UCHET_TOVAR: сохранённые учётные суммы приходов считаются исходными. Каждый SKU обрабатывается отдельно; отрицательная хронология может быть пропущена с warning."
    )
    public ResponseEntity<FolioAccountingPriceFullStatusResponse> recalculateFull(
            @Valid @RequestBody FolioAccountingPriceFullRecalculationRequest request) {
        FolioAccountingPriceFullStatusResponse response = service.requestFull(request);
        return response.accepted()
                ? ResponseEntity.accepted().body(response)
                : ResponseEntity.status(409).body(response);
    }

    @GetMapping("/recalculate/full/status")
    @Operation(summary = "Получить прогресс полного перерасчёта учётных цен")
    public ResponseEntity<FolioAccountingPriceFullStatusResponse> fullStatus() {
        return ResponseEntity.ok(service.status(false));
    }

    @PostMapping("/recalculate/native-full")
    @Operation(
            summary = "Запустить штатный полный перерасчёт I_UCHET_TOVAR",
            description = "Java сначала диагностирует проблемные SKU, сохраняет подробные warnings и безопасно пропускает их. previewOnly=true выполняет точную процедуру ФОЛИО для остальных товаров порциями с rollback. Apply повторяет проверку, затем фиксирует безопасные порции. Неожиданная проблема, отсутствовавшая в диагностике, откатывает текущую порцию и останавливает job."
    )
    public ResponseEntity<FolioAccountingPriceNativeFullStatusResponse> recalculateNativeFull(
            @Valid @RequestBody FolioAccountingPriceNativeFullRequest request) {
        FolioAccountingPriceNativeFullStatusResponse response =
                service.requestNativeFull(request);
        return response.accepted()
                ? ResponseEntity.accepted().body(response)
                : ResponseEntity.status(409).body(response);
    }

    @GetMapping("/recalculate/native-full/status")
    @Operation(summary = "Получить прогресс штатного полного перерасчёта I_UCHET_TOVAR")
    public ResponseEntity<FolioAccountingPriceNativeFullStatusResponse> nativeFullStatus() {
        return ResponseEntity.ok(service.nativeFullStatus(false));
    }
}
