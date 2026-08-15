package org.example.proect.lavka.dto.folio;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record FolioCustomerDocumentsResponse(
        boolean ok,
        Partner partner,
        Filters filters,
        List<DocumentSummary> documents,
        String nextCursor,
        boolean hasMore,
        List<Issue> warnings
) {
    public record Partner(String shortName, String name) {
    }

    public record Filters(
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate dateFrom,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate dateTo,
            List<FolioCustomerDocumentType> types,
            int limit
    ) {
    }

    public record DocumentSummary(
            FolioCustomerDocumentType documentType,
            long documentId,
            String documentNumber,
            String documentNumberSuffix,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
            LocalDateTime documentDate,
            BigDecimal totalAmount,
            BigDecimal currencyAmount,
            String currencyCode,
            Integer warehouseId,
            Boolean accounted,
            Boolean nonCash,
            Boolean returnDocument,
            Boolean paymentDirectionRaw,
            String operationKind,
            String additionalInfo,
            int lineCount,
            BigDecimal allocatedAmount,
            boolean canRepeatOrder,
            String source
    ) {
    }

    public record Issue(String code, String message, Map<String, Object> details) {
    }
}
