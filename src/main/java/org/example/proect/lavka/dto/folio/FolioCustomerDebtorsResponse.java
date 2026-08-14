package org.example.proect.lavka.dto.folio;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record FolioCustomerDebtorsResponse(
        boolean ok,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate asOfDate,
        DebtorsFilters filters,
        DebtorsSummary summary,
        List<DebtorItem> debtors,
        List<DebtorsIssue> warnings,
        List<DebtorsIssue> errors
) {
    public record DebtorsFilters(
            BigDecimal minPayable,
            String q,
            List<String> types,
            int limit,
            int offset,
            String sort
    ) {
    }

    public record DebtorsSummary(
            long matchedClients,
            int returnedClients,
            BigDecimal commonDebtTotal,
            BigDecimal deferredAmountTotal,
            BigDecimal overdueDeferredAmountTotal,
            BigDecimal prepaymentAmountTotal,
            BigDecimal payableNowTotal
    ) {
    }

    public record DebtorItem(
            DebtorPartner partner,
            BigDecimal commonDebt,
            BigDecimal deferredAmount,
            BigDecimal overdueDeferredAmount,
            BigDecimal prepaymentAmount,
            BigDecimal payableNow,
            List<DebtorsIssue> warnings
    ) {
    }

    public record DebtorPartner(
            String shortName,
            String name,
            String type,
            String city,
            String phone
    ) {
    }

    public record DebtorsIssue(
            String code,
            String message,
            Map<String, Object> details
    ) {
    }
}
