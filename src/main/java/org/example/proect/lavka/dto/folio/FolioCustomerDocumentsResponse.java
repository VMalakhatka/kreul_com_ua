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
            int limit,
            boolean preordersOnly
    ) {
        public Filters(LocalDate dateFrom, LocalDate dateTo, List<FolioCustomerDocumentType> types, int limit) {
            this(dateFrom, dateTo, types, limit, false);
        }
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
            String source,
            String sourceInfo
    ) {
        public DocumentSummary(FolioCustomerDocumentType type,long id,String number,String suffix,LocalDateTime date,
                BigDecimal amount,BigDecimal currencyAmount,String currency,Integer warehouse,Boolean accounted,
                Boolean nonCash,Boolean returned,Boolean paymentDirection,String operation,String additional,
                int lines,BigDecimal allocated,boolean repeat,String source) {
            this(type,id,number,suffix,date,amount,currencyAmount,currency,warehouse,accounted,nonCash,returned,
                    paymentDirection,operation,additional,lines,allocated,repeat,source,null);
        }
    }

    public record Issue(String code, String message, Map<String, Object> details) {
    }
}
