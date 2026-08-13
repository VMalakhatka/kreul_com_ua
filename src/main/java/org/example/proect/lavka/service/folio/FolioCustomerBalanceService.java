package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioCustomerBalanceDao;
import org.example.proect.lavka.dao.folio.FolioCustomerBalanceDao.RawRow;
import org.example.proect.lavka.dto.folio.FolioCustomerBalanceResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class FolioCustomerBalanceService {

    static final LocalDate FOLIO_MIN_DATE = LocalDate.of(1753, 1, 1);
    private static final int MAX_PARTNER_ID_LENGTH = 8;
    private static final int MAX_WAREHOUSE_MEMBERSHIP_LENGTH = 255;
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final FolioCustomerBalanceDao dao;
    private final Clock clock;

    @Autowired
    public FolioCustomerBalanceService(FolioCustomerBalanceDao dao) {
        this(dao, Clock.systemDefaultZone());
    }

    FolioCustomerBalanceService(FolioCustomerBalanceDao dao, Clock clock) {
        this.dao = dao;
        this.clock = clock;
    }

    public FolioCustomerBalanceResponse get(String partnerId,
                                            LocalDate dateFrom,
                                            LocalDate dateTo,
                                            LocalDate asOfDate,
                                            List<Integer> warehouseIds,
                                            Boolean includeServicePayments) {
        String normalizedPartnerId = normalizePartnerId(partnerId);
        LocalDate normalizedDateFrom = dateFrom == null ? FOLIO_MIN_DATE : dateFrom;
        LocalDate normalizedDateTo = dateTo == null ? LocalDate.now(clock) : dateTo;
        LocalDate normalizedAsOfDate = asOfDate == null ? LocalDate.now(clock) : asOfDate;
        List<Integer> normalizedWarehouseIds = normalizeWarehouseIds(warehouseIds);
        boolean normalizedShowServicePayments = includeServicePayments == null || includeServicePayments;

        if (normalizedDateFrom.isAfter(normalizedDateTo)) {
            throw new FolioAccountValidationException(
                    "invalid_date_range",
                    "dateFrom must be before or equal to dateTo"
            );
        }

        var procedure = dao.load(
                normalizedPartnerId,
                normalizedDateFrom,
                normalizedDateTo,
                normalizedWarehouseIds,
                normalizedShowServicePayments
        );

        List<RawRow> details = procedure.rows().stream()
                .filter(row -> row.documentType() != null)
                .sorted(Comparator
                        .comparing(RawRow::documentDate, Comparator.nullsFirst(Comparator.naturalOrder()))
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

        List<FolioCustomerBalanceResponse.Row> rows = new ArrayList<>();
        rows.add(openingRow(openingBalance));

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
            boolean deferred = amounts.expense().signum() != 0
                    && controlDate != null
                    && controlDate.isAfter(normalizedAsOfDate);
            boolean overdueDeferred = amounts.expense().signum() != 0
                    && startsWith(raw.basis(), "111")
                    && !deferred;
            BigDecimal paymentAmount = amounts.bankPayment().add(amounts.cashPayment());
            boolean prepayment = startsWith(raw.note(), "222")
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

        // Exact formulas from the workbook: L3 and L6.
        BigDecimal commonDebt = openingBalance
                .add(expenseTotal)
                .subtract(receiptTotal)
                .subtract(bankPaymentTotal)
                .subtract(cashPaymentTotal);
        BigDecimal payableNow = commonDebt.subtract(deferredTotal).add(prepaymentTotal);

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

        String warehouseMode = normalizedWarehouseIds.isEmpty()
                ? "ALL_WAREHOUSES"
                : "ALL_DOCUMENT_LINES_IN_SELECTED_WAREHOUSES";

        return new FolioCustomerBalanceResponse(
                true,
                new FolioCustomerBalanceResponse.Partner(procedure.partnerId(), procedure.partnerName()),
                new FolioCustomerBalanceResponse.Filters(
                        normalizedDateFrom,
                        normalizedDateTo,
                        normalizedAsOfDate,
                        normalizedWarehouseIds,
                        warehouseMode,
                        normalizedShowServicePayments
                ),
                summary,
                List.copyOf(rows),
                List.of(
                        new FolioCustomerBalanceResponse.Warning(
                                "FOLIO_NOLOCK_READ",
                                "I_DOLG_DOC uses NOLOCK; concurrent Folio edits can make one response internally non-snapshot",
                                Map.of()
                        ),
                        new FolioCustomerBalanceResponse.Warning(
                                "ACTIVE_LEDGER_ONLY",
                                "The standard procedure does not include archived Folio documents",
                                Map.of()
                        ),
                        new FolioCustomerBalanceResponse.Warning(
                                "LEGACY_DATE_TO_MIDNIGHT",
                                "I_DOLG_DOC treats dateTo as an inclusive midnight boundary",
                                Map.of("dateTo", normalizedDateTo.toString())
                        )
                )
        );
    }

    private static FolioCustomerBalanceResponse.Row openingRow(BigDecimal openingBalance) {
        return new FolioCustomerBalanceResponse.Row(
                0,
                null,
                null,
                "НА НАЧАЛО",
                null,
                "Долг на начало",
                openingBalance,
                ZERO,
                ZERO,
                ZERO,
                ZERO,
                openingBalance,
                null,
                null,
                true,
                false,
                false,
                false,
                ZERO,
                ZERO,
                ZERO,
                null,
                null,
                null,
                null
        );
    }

    private static MappedAmounts mapAmounts(RawRow row) {
        boolean paymentRow = row.rawCashPayment().signum() != 0
                || row.rawBankPayment().signum() != 0
                || (row.documentType() != null && row.documentType().length() > 1);
        if (paymentRow) {
            return new MappedAmounts(
                    ZERO,
                    ZERO,
                    row.rawBankPayment().negate(),
                    row.rawCashPayment().negate()
            );
        }

        BigDecimal amount = row.amount();
        return amount.signum() >= 0
                ? new MappedAmounts(amount, ZERO, ZERO, ZERO)
                : new MappedAmounts(ZERO, amount.abs(), ZERO, ZERO);
    }

    private static String normalizePartnerId(String partnerId) {
        String value = partnerId == null ? "" : partnerId.trim();
        if (value.isEmpty()) {
            throw new FolioAccountValidationException("missing_partner_id", "partnerId is required");
        }
        if (value.length() > MAX_PARTNER_ID_LENGTH) {
            throw new FolioAccountValidationException(
                    "partner_id_too_long",
                    "partnerId must fit _PARTNER.N_USER varchar(8)"
            );
        }
        return value;
    }

    private static List<Integer> normalizeWarehouseIds(List<Integer> warehouseIds) {
        if (warehouseIds == null || warehouseIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Integer> unique = new LinkedHashSet<>();
        for (Integer warehouseId : warehouseIds) {
            if (warehouseId == null || warehouseId <= 0) {
                throw new FolioAccountValidationException(
                        "invalid_warehouse_id",
                        "warehouseIds must contain only positive integers"
                );
            }
            unique.add(warehouseId);
        }
        List<Integer> result = List.copyOf(unique);
        int membershipLength = 1;
        for (Integer warehouseId : result) {
            membershipLength += warehouseId.toString().length() + 1;
        }
        if (membershipLength > MAX_WAREHOUSE_MEMBERSHIP_LENGTH) {
            throw new FolioAccountValidationException(
                    "warehouse_filter_too_long",
                    "warehouseIds exceed the I_DOLG_DOC varchar(255) filter"
            );
        }
        return result;
    }

    private static boolean startsWith(String value, String prefix) {
        return value != null && value.trim().startsWith(prefix);
    }

    private static LocalDate toDate(java.time.LocalDateTime value) {
        return value == null ? null : value.toLocalDate();
    }

    private record MappedAmounts(
            BigDecimal expense,
            BigDecimal receipt,
            BigDecimal bankPayment,
            BigDecimal cashPayment
    ) {
    }
}
