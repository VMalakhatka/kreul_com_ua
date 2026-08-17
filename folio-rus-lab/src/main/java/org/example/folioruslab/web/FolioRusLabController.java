package org.example.folioruslab.web;

import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.folioruslab.config.OpenApiConfig;
import org.example.folioruslab.sql.SqlExecutionRequest;
import org.example.folioruslab.sql.SqlExecutionResponse;
import org.example.folioruslab.sql.SqlExecutionService;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Paint_Rus laboratory", description = "Диагностика и управляемое выполнение SQL")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
public final class FolioRusLabController {

    private final PreflightService preflightService;
    private final SqlExecutionService executionService;

    public FolioRusLabController(
            PreflightService preflightService,
            SqlExecutionService executionService
    ) {
        this.preflightService = preflightService;
        this.executionService = executionService;
    }

    @GetMapping("/preflight")
    @Operation(summary = "Проверить подключение, версию базы и ограничения login")
    public PreflightResponse preflight() {
        return preflightService.run();
    }

    @PostMapping("/sql/execute")
    @Operation(
            summary = "Выполнить SQL",
            description = "По умолчанию mode=ROLLBACK. COMMIT и SELF_MANAGED требуют явного подтверждения."
    )
    public ResponseEntity<SqlExecutionResponse> execute(
            @Valid @RequestBody SqlExecutionRequest request
    ) {
        SqlExecutionResponse response = executionService.execute(request);
        HttpStatusCode status = response.state().isSuccessful()
                ? HttpStatusCode.valueOf(200)
                : HttpStatusCode.valueOf(422);
        return ResponseEntity.status(status).body(response);
    }
}
