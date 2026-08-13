package org.example.proect.lavka.dto.folio;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record FolioCustomerBalanceResponse(
        boolean ok,
        Partner partner,
        Filters filters,
        Summary summary,
        List<Row> rows,
        List<Warning> warnings
) {
    public record Partner(String shortName, String name) {
    }

    public record Filters(
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate dateFrom,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate dateTo,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate asOfDate,
            List<Integer> warehouseIds,
            String warehouseMode,
            boolean includeServicePayments
    ) {
    }

    public record Summary(
            BigDecimal openingBalance,
            BigDecimal expenseTotal,
            BigDecimal receiptTotal,
            BigDecimal bankPaymentTotal,
            BigDecimal cashPaymentTotal,
            BigDecimal commonDebt,
            BigDecimal deferredAmount,
            BigDecimal overdueDeferredAmount,
            BigDecimal prepaymentAmount,
            BigDecimal payableNow
    ) {
    }

    /**
     * Поля до invoiceDate повторяют 14 колонок штатного 7-го отчёта.
     */
    public record Row(
            int sequence,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate controlDate,
            String documentType,
            String documentNumber,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate documentDate,
            String basis,
            BigDecimal balanceBefore,
            BigDecimal expenseAmount,
            BigDecimal receiptAmount,
            BigDecimal bankPayment,
            BigDecimal cashPayment,
            BigDecimal balanceAfter,
            String note,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
            LocalDate invoiceDate,
            boolean openingBalanceRow,
            boolean deferred,
            boolean overdueDeferred,
            boolean prepayment,
            BigDecimal deferredAmount,
            BigDecimal overdueDeferredAmount,
            BigDecimal prepaymentAmount,
            Long documentId,
            Integer warehouseId,
            String warehouseName,
            String folioDocumentKind
    ) {
    }

    public record Warning(String code, String message, Map<String, Object> details) {
    }
}
