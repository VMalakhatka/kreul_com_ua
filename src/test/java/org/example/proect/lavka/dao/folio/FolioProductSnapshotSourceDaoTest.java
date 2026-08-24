package org.example.proect.lavka.dao.folio;

import org.example.proect.lavka.service.folio.FolioAccountingModeUnsupportedException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FolioProductSnapshotSourceDaoTest {

    @Test
    void acceptsBothVerifiedAverageModes() {
        FolioProductSnapshotSourceDao.validateAccountingMode(warehouse("1000"));
        FolioProductSnapshotSourceDao.validateAccountingMode(warehouse("1100"));
    }

    @Test
    void unsupportedModeContainsActionableDiagnostics() {
        assertThatThrownBy(() -> FolioProductSnapshotSourceDao
                .validateAccountingMode(warehouse("1001")))
                .isInstanceOfSatisfying(
                        FolioAccountingModeUnsupportedException.class,
                        error -> {
                            assertThat(error.getCode())
                                    .isEqualTo("PRODUCT_SNAPSHOT_ACCOUNTING_MODE_UNSUPPORTED");
                            assertThat(error.rawCode()).isEqualTo(1001);
                            assertThat(error.modeName()).isEqualTo("LIFO");
                            assertThat(error.recommendation()).contains("Exclude this warehouse");
                        });
    }

    @Test
    void monthlyAggregationSeparatesRegularAndOneOffSalesFromTransfers() {
        var regular = movement(1, "*\u041f\u0420\u0415\u0414\u041e\u041f\u041b\u0410\u0422\u0410", "REGULAR",
                "SALE", "2", "40", "20");
        var oneOff = movement(2, "*\u0420\u0410\u0417\u041e\u0412\u0410\u042f", "ONE_OFF_ORDER",
                "SALE", "5", "100", "50");
        var transfer = movement(3, "*\u041f\u0415\u0420\u0415\u041c\u0415\u0429\u0415\u041d\u0418\u0415", "NOT_APPLICABLE",
                "TRANSFER_OUT", "3", "0", "30");

        var result = FolioProductSnapshotSourceDao.aggregateMonthlyActivity(
                List.of(regular, oneOff, transfer));

        assertThat(result).singleElement().satisfies(row -> {
            assertThat(row.salesQuantity()).isEqualByComparingTo("7");
            assertThat(row.regularSalesQuantity()).isEqualByComparingTo("2");
            assertThat(row.oneOffSalesQuantity()).isEqualByComparingTo("5");
            assertThat(row.netQuantity()).isEqualByComparingTo("-10");
        });
    }

    @Test
    void organizationTypeAcceptsOnlyTheSingleCharacterPartnerCode() {
        assertThat(FolioProductSnapshotSourceDao.partnerOrganizationType(" Д ")).isEqualTo("Д");
        assertThat(FolioProductSnapshotSourceDao.partnerOrganizationType("КИЕВ ОПТ")).isEmpty();
        assertThat(FolioProductSnapshotSourceDao.partnerOrganizationType(null)).isEmpty();
    }

    private static FolioProductSnapshotSourceDao.MovementFact movement(
            long recno, String operation, String demandMode, String movementClass,
            String quantity, String revenue, String cost) {
        BigDecimal qty = new BigDecimal(quantity);
        BigDecimal accounting = new BigDecimal(cost);
        boolean sale = "SALE".equals(movementClass);
        boolean planning = sale && "REGULAR".equals(demandMode);
        return new FolioProductSnapshotSourceDao.MovementFact(
                recno, 100L + recno, BigDecimal.valueOf(10 + recno),
                LocalDate.of(2026, 7, 10), "SKU-1", qty, qty.negate(),
                new BigDecimal(revenue), accounting, accounting.negate(),
                "\u0420", "\u0420", operation, true, false, movementClass,
                "OUT", demandMode, "NOT_SPECIFIED", "NON_RETAIL",
                "CLIENT", "Client", "\u0414", "Supplier", "CURRENT",
                true, sale, planning);
    }

    private static FolioProductSnapshotSourceDao.Warehouse warehouse(String rawCode) {
        return new FolioProductSnapshotSourceDao.Warehouse(
                "Paint_Rus", 22, "Деливери Ялта", new BigDecimal(rawCode), null);
    }
}
