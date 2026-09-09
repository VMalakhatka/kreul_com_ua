package org.example.proect.lavka.dto.folio;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Independent document-quality audit; never a replacement for the profit classifier. */
public record FolioDocumentAuditResponse(
        boolean ok, String status, String rulesVersion,
        @JsonFormat(shape = JsonFormat.Shape.STRING) OffsetDateTime calculatedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate dateFrom,
        @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate dateTo,
        Coverage coverage, Pagination page, Summary summary, List<Rule> rules, List<Item> items,
        String errorCode, String errorId) {
    public record Coverage(String source, String dateBasis, String consistency,
            boolean allWarehouses, List<String> directions, List<String> registers,
            boolean pageComplete, boolean rulesComplete, List<String> unsupported, List<String> warnings) {}
    public record Pagination(int pageSize, long afterPaymentId, long upperPaymentId,
            Long nextAfterPaymentId, boolean hasMore, long totalDocuments) {}
    public record Summary(String scope, int examined, Map<String, Long> byStatus, Map<String, Long> byCategory) {}
    public record Document(long paymentId, String documentNumber,
            @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate documentDate,
            Integer warehouseId, Boolean bank, String direction,
            @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount, String currencyCode,
            String organizationCode, String organizationName, String purposeCode, String operationType,
            String sourceInfo, String note, String periodEvidence, String resolvedPeriod,
            String amountField, String amountCurrencyStatus, boolean sensitiveValuesMasked) {}
    public record Category(String code, String label, String recognition, String profitTreatment) {}
    public record Item(Document document, Category category, String status, List<String> ruleIds, List<Finding> findings) {}
    public record Finding(String code, String severity, String field, String actual, String expected,
            String recommendation, String ruleId) {}
    public record Rule(String id, String categoryCode, String label, String sourceSheet, List<Integer> sourceRows,
            String evidence, String requiredSourceInfo, String periodRequirement,
            List<String> expectedOperationTypes, List<String> organizationCodes, List<String> limitations) {}
}
