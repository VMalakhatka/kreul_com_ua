package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioCustomerBalanceDao.ProcedureResult;
import org.example.proect.lavka.dao.folio.FolioCustomerBalanceDao.RawRow;
import org.example.proect.lavka.dto.folio.FolioCustomerBalanceResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Canonical calculation shared by the single-customer and debtors reports.
 */
final class FolioCustomerBalanceCalculator {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final String DEFERRED_MARKER = "РЕЛ";
    private static final String PREPAYMENT_MARKER = "ПРД";

    private FolioCustomerBalanceCalculator() {
    }

    static Calculation calculate(ProcedureResult procedure, LocalDate asOfDate, boolean includeRows) {
        List<RawRow> details = procedure.rows().stream()
                .filter(row -> row.documentType() != null)
                .sorted(Comparator
                        .comparing(RawRow::documentDate, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparingInt(row -> isPaymentRow(row) ? 0 : 1)
                        .thenComparingInt(RawRow::sourceOrder))
                .toList();

        BigDecimal openingBalance = procedure.openingBalance();
        BigDecimal runningBalance = openingBalance;
        BigDecimal expenseTotal = ZERO;
        BigDecimal receiptTotal = ZERO;
        BigDecimal bankPaymentTotal = ZERO;
        BigDecimal cashPaymentTotal = ZERO;
        BigDecimal deferredTotal = ZERO;
        BigDecimal overdueDeferredTotal = ZERO;
        BigDecimal prepaymentTotal = ZERO;

        List<FolioCustomerBalanceResponse.Row> rows = includeRows ? new ArrayList<>() : List.of();
        if (includeRows) {
            rows.add(openingRow(openingBalance));
        }

        int sequence = 1;
        for (RawRow raw : details) {
            MappedAmounts amounts = mapAmounts(raw);
            BigDecimal balanceBefore = runningBalance;
            runningBalance = runningBalance
                    .add(amounts.expense())
                    .subtract(amounts.receipt())
                    .subtract(amounts.bankPayment())
                    .subtract(amounts.cashPayment());

            LocalDate controlDate = toDate(raw.controlDate());
            boolean deferredMarker = startsWith(raw.basis(), DEFERRED_MARKER);
            boolean deferred = amounts.expense().signum() != 0
                    && deferredMarker
                    && controlDate != null
                    && controlDate.isAfter(asOfDate);
            boolean overdueDeferred = amounts.expense().signum() != 0
                    && deferredMarker
                    && !deferred;
            BigDecimal paymentAmount = amounts.bankPayment().add(amounts.cashPayment());
            boolean prepayment = startsWith(raw.note(), PREPAYMENT_MARKER)
                    && paymentAmount.signum() != 0;

            BigDecimal deferredAmount = deferred ? amounts.expense() : ZERO;
            BigDecimal overdueDeferredAmount = overdueDeferred ? amounts.expense() : ZERO;
            BigDecimal prepaymentAmount = prepayment ? paymentAmount : ZERO;

            expenseTotal = expenseTotal.add(amounts.expense());
            receiptTotal = receiptTotal.add(amounts.receipt());
            bankPaymentTotal = bankPaymentTotal.add(amounts.bankPayment());
            cashPaymentTotal = cashPaymentTotal.add(amounts.cashPayment());
            deferredTotal = deferredTotal.add(deferredAmount);
            overdueDeferredTotal = overdueDeferredTotal.add(overdueDeferredAmount);
            prepaymentTotal = prepaymentTotal.add(prepaymentAmount);

            if (includeRows) {
                rows.add(new FolioCustomerBalanceResponse.Row(
                        sequence++,
                        controlDate,
                        raw.documentType(),
                        raw.documentNumber(),
                        toDate(raw.documentDate()),
                        raw.basis(),
                        balanceBefore,
                        amounts.expense(),
                        amounts.receipt(),
                        amounts.bankPayment(),
                        amounts.cashPayment(),
                        runningBalance,
                        raw.note(),
                        toDate(raw.invoiceDate()),
                        false,
                        deferred,
                        overdueDeferred,
                        prepayment,
                        deferredAmount,
                        overdueDeferredAmount,
                        prepaymentAmount,
                        raw.documentId(),
                        raw.warehouseId(),
                        raw.warehouseName(),
                        raw.folioDocumentKind()
                ));
            }
        }

        BigDecimal accountingBalance = openingBalance
                .add(expenseTotal)
                .subtract(receiptTotal)
                .subtract(bankPaymentTotal)
                .subtract(cashPaymentTotal);
        // A marked prepayment is reserved for a future delivery and must not pay
        // existing debt. Keep it as a separate amount and exclude its effect from debt.
        BigDecimal commonDebt = accountingBalance.add(prepaymentTotal);
        BigDecimal payableNow = commonDebt.subtract(deferredTotal);

        var summary = new FolioCustomerBalanceResponse.Summary(
                openingBalance,
                expenseTotal,
                receiptTotal,
                bankPaymentTotal,
                cashPaymentTotal,
                commonDebt,
                deferredTotal,
                overdueDeferredTotal,
                prepaymentTotal,
                payableNow
        );
        return new Calculation(summary, List.copyOf(rows));
    }

    private static FolioCustomerBalanceResponse.Row openingRow(BigDecimal openingBalance) {
        return new FolioCustomerBalanceResponse.Row(
                0, null, null, "НА НАЧАЛО", null, "Долг на начало",
                openingBalance, ZERO, ZERO, ZERO, ZERO, openingBalance,
                null, null, true, false, false, false,
                ZERO, ZERO, ZERO, null, null, null, null
        );
    }

    private static MappedAmounts mapAmounts(RawRow row) {
        if (isPaymentRow(row)) {
            return new MappedAmounts(ZERO, ZERO, row.rawBankPayment(), row.rawCashPayment());
        }
        BigDecimal amount = row.amount();
        return amount.signum() >= 0
                ? new MappedAmounts(amount, ZERO, ZERO, ZERO)
                : new MappedAmounts(ZERO, amount.abs(), ZERO, ZERO);
    }

    private static boolean isPaymentRow(RawRow row) {
        return row.rawCashPayment().signum() != 0
                || row.rawBankPayment().signum() != 0
                || (row.documentType() != null && row.documentType().length() > 1);
    }

    private static boolean startsWith(String value, String prefix) {
        return value != null && value.trim().startsWith(prefix);
    }

    private static LocalDate toDate(java.time.LocalDateTime value) {
        return value == null ? null : value.toLocalDate();
    }

    record Calculation(
            FolioCustomerBalanceResponse.Summary summary,
            List<FolioCustomerBalanceResponse.Row> rows
    ) {
    }

    private record MappedAmounts(
            BigDecimal expense,
            BigDecimal receipt,
            BigDecimal bankPayment,
            BigDecimal cashPayment
    ) {
    }
}
