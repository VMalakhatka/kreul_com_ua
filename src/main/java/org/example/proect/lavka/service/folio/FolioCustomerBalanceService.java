package org.example.proect.lavka.service.folio;

import lombok.extern.slf4j.Slf4j;
import org.example.proect.lavka.dao.folio.FolioCustomerBalanceDao;
import org.example.proect.lavka.dto.folio.FolioCustomerBalanceResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class FolioCustomerBalanceService {

    static final LocalDate FOLIO_MIN_DATE = LocalDate.of(1753, 1, 1);
    private static final int MAX_PARTNER_ID_LENGTH = 8;
    private static final int MAX_WAREHOUSE_MEMBERSHIP_LENGTH = 255;
    private final FolioCustomerBalanceDao dao;
    private final FolioCustomerBalanceSnapshotService snapshotService;
    private final Clock clock;

    @Autowired
    public FolioCustomerBalanceService(FolioCustomerBalanceDao dao,
                                       FolioCustomerBalanceSnapshotService snapshotService,
                                       @Qualifier("folioBalanceClock") Clock clock) {
        this.dao = dao;
        this.snapshotService = snapshotService;
        this.clock = clock;
    }

    FolioCustomerBalanceService(FolioCustomerBalanceDao dao, Clock clock) {
        this(dao, null, clock);
    }

    public FolioCustomerBalanceResponse get(String partnerShortName,
                                            LocalDate dateFrom,
                                            List<Integer> warehouseIds,
                                            Boolean includeServicePayments) {
        String normalizedPartnerId = normalizePartnerShortName(partnerShortName);
        LocalDate currentDate = LocalDate.now(clock);
        LocalDate normalizedDateFrom = dateFrom == null ? FOLIO_MIN_DATE : dateFrom;
        List<Integer> normalizedWarehouseIds = normalizeWarehouseIds(warehouseIds);
        boolean normalizedShowServicePayments = includeServicePayments == null || includeServicePayments;

        if (normalizedDateFrom.isAfter(currentDate)) {
            throw new FolioAccountValidationException(
                    "invalid_date_range",
                    "dateFrom must be before or equal to the current date"
            );
        }

        var procedure = dao.load(
                normalizedPartnerId,
                normalizedDateFrom,
                currentDate,
                normalizedWarehouseIds,
                normalizedShowServicePayments
        );

        var calculation = FolioCustomerBalanceCalculator.calculate(procedure, currentDate, true);

        String warehouseMode = normalizedWarehouseIds.isEmpty()
                ? "ALL_WAREHOUSES"
                : "ALL_DOCUMENT_LINES_IN_SELECTED_WAREHOUSES";

        var warnings = new ArrayList<FolioCustomerBalanceResponse.Warning>();
        refreshCanonicalSnapshot(normalizedDateFrom, normalizedWarehouseIds,
                normalizedShowServicePayments, currentDate, procedure, calculation.summary(), warnings);

        return new FolioCustomerBalanceResponse(
                true,
                new FolioCustomerBalanceResponse.Partner(procedure.partnerId(), procedure.partnerName()),
                new FolioCustomerBalanceResponse.Filters(
                        normalizedDateFrom,
                        currentDate,
                        currentDate,
                        normalizedWarehouseIds,
                        warehouseMode,
                        normalizedShowServicePayments
                ),
                calculation.summary(),
                calculation.rows(),
                standardWarnings(currentDate, warnings)
        );
    }

    private static List<FolioCustomerBalanceResponse.Warning> standardWarnings(
            LocalDate currentDate, List<FolioCustomerBalanceResponse.Warning> refreshWarnings) {
        var warnings = new ArrayList<>(List.of(
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
                                Map.of("dateTo", currentDate.toString())
                        )
                ));
        warnings.addAll(refreshWarnings);
        return List.copyOf(warnings);
    }

    private void refreshCanonicalSnapshot(LocalDate dateFrom, List<Integer> warehouseIds,
                                          boolean includeServicePayments, LocalDate asOfDate,
                                          FolioCustomerBalanceDao.ProcedureResult procedure,
                                          FolioCustomerBalanceResponse.Summary summary,
                                          List<FolioCustomerBalanceResponse.Warning> warnings) {
        if (snapshotService != null
                && warehouseIds.isEmpty()
                && includeServicePayments) {
            try {
                // Period totals (especially deferred amounts and PRD payments) cannot be
                // copied into the full-history projection. Read only this client's canonical ledger.
                var canonical = dateFrom.equals(FOLIO_MIN_DATE) ? procedure
                        : dao.load(procedure.partnerId(), FOLIO_MIN_DATE, asOfDate, List.of(), true);
                var canonicalSummary = dateFrom.equals(FOLIO_MIN_DATE) ? summary
                        : FolioCustomerBalanceCalculator.calculate(canonical, asOfDate, false).summary();
                int updated = snapshotService.updateActiveClient(
                        asOfDate,
                        canonical.partnerId(),
                        canonical.partnerName(),
                        canonicalSummary
                );
                if (updated == 0) {
                    warnings.add(new FolioCustomerBalanceResponse.Warning(
                            "BALANCE_SNAPSHOT_CLIENT_REFRESH_DEFERRED",
                            "Canonical balance saved, but no matching current-day snapshot row was updated",
                            Map.of("partnerShortName", procedure.partnerId(), "asOfDate", asOfDate.toString(),
                                    "recommendation", "Check snapshot status; a full snapshot refresh may be required")
                    ));
                }
            } catch (RuntimeException e) {
                // Neither a second Folio read nor projection persistence may discard the requested report.
                log.warn("[folio.balance.snapshot] cannot refresh partner={} after live report: {}",
                        procedure.partnerId(), e.getMessage());
                warnings.add(new FolioCustomerBalanceResponse.Warning(
                        "BALANCE_SNAPSHOT_CLIENT_REFRESH_FAILED",
                        "The requested report is available, but the debtors snapshot could not be refreshed",
                        Map.of("partnerShortName", procedure.partnerId(), "asOfDate", asOfDate.toString(),
                                "recommendation", "Retry the individual report or refresh the full snapshot")
                ));
            }
        }
    }

    private static String normalizePartnerShortName(String partnerShortName) {
        String value = partnerShortName == null ? "" : partnerShortName.trim();
        if (value.isEmpty()) {
            throw new FolioAccountValidationException(
                    "missing_partner_short_name",
                    "partnerShortName is required"
            );
        }
        if (value.length() > MAX_PARTNER_ID_LENGTH) {
            throw new FolioAccountValidationException(
                    "partner_short_name_too_long",
                    "partnerShortName must fit _PARTNER.N_USER varchar(8)"
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

}
