package org.example.proect.lavka.service.folio;

import lombok.extern.slf4j.Slf4j;
import org.example.proect.lavka.dao.folio.FolioCustomerBalanceDao;
import org.example.proect.lavka.dto.folio.FolioCustomerBalanceResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
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

        FolioCustomerBalanceResponse response = new FolioCustomerBalanceResponse(
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
                                Map.of("dateTo", currentDate.toString())
                        )
                )
        );

        if (snapshotService != null
                && normalizedDateFrom.equals(FOLIO_MIN_DATE)
                && normalizedWarehouseIds.isEmpty()
                && normalizedShowServicePayments) {
            try {
                snapshotService.updateActiveClient(
                        currentDate,
                        procedure.partnerId(),
                        procedure.partnerName(),
                        calculation.summary()
                );
            } catch (RuntimeException e) {
                // Snapshot persistence must not turn a successful canonical Folio report into an API error.
                log.warn("[folio.balance.snapshot] cannot refresh partner={} after live report: {}",
                        procedure.partnerId(), e.getMessage());
            }
        }

        return response;
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
