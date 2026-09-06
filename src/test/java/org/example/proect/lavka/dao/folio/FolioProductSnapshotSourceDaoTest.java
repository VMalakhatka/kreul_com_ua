package org.example.proect.lavka.dao.folio;

import org.example.proect.lavka.service.folio.FolioAccountingModeUnsupportedException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Test
    void movementStreamingKeepsBatchesBoundedAndEmitsEmptyProducts() {
        Map<String, FolioProductSnapshotSourceDao.ProductCard> products = new LinkedHashMap<>();
        products.put("SKU-1", product("SKU-1"));
        products.put("SKU-2", product("SKU-2"));
        List<Integer> movementBatchSizes = new ArrayList<>();
        Map<String, Integer> activityRows = new LinkedHashMap<>();
        Map<String, Map<LocalDate, BigDecimal>> dailyRows = new LinkedHashMap<>();
        var accumulator = new FolioProductSnapshotSourceDao.MovementStreamAccumulator(
                products, new FolioProductSnapshotSourceDao.CaptureConsumer() {
            @Override
            public void acceptMovementBatch(
                    List<FolioProductSnapshotSourceDao.MovementFact> rows) {
                movementBatchSizes.add(rows.size());
            }

            @Override
            public void acceptProductActivity(
                    FolioProductSnapshotSourceDao.ProductCard product,
                    List<FolioProductSnapshotSourceDao.MonthlyActivity> rows) {
                activityRows.put(product.sku(), rows.size());
            }

            @Override
            public void acceptProductDailyStock(FolioProductSnapshotSourceDao.ProductCard product,
                                                Map<LocalDate, BigDecimal> daily) {
                dailyRows.put(product.sku(), daily);
            }
        });

        for (int i = 1; i <= 1_001; i++) accumulator.add(movement(i,
                "*\u041f\u0420\u0415\u0414\u041e\u041f\u041b\u0410\u0422\u0410", "REGULAR", "SALE", "1", "2", "1"));
        accumulator.finish();

        assertThat(accumulator.movementCount()).isEqualTo(1_001);
        assertThat(movementBatchSizes).containsExactly(300, 300, 300, 101);
        assertThat(activityRows).containsEntry("SKU-1", 1).containsEntry("SKU-2", 0);
        assertThat(dailyRows.get("SKU-1")).hasSize(1);
        assertThat(dailyRows.get("SKU-1").get(LocalDate.of(2026, 7, 10))).isEqualByComparingTo("-1001");
        assertThat(dailyRows.get("SKU-2")).isEmpty();
    }

    @Test
    void movementQueryUsesCanonicalCardSkuForGroupingAndOrdering() {
        assertThat(FolioProductSnapshotSourceDao.MOVEMENT_FACT_SQL)
                .contains("ISNULL(a.COD_ARTIC,m.NAME_PREDM) AS NAME_PREDM")
                .contains("ORDER BY ISNULL(a.COD_ARTIC,m.NAME_PREDM)");
    }

    @Test
    void movementStreamingRejectsARepeatedCanonicalSkuBeforeStagingDuplicates() {
        Map<String, FolioProductSnapshotSourceDao.ProductCard> products = new LinkedHashMap<>();
        products.put("SKU-1", product("SKU-1"));
        products.put("SKU-2", product("SKU-2"));
        var accumulator = new FolioProductSnapshotSourceDao.MovementStreamAccumulator(
                products, new FolioProductSnapshotSourceDao.CaptureConsumer() {
            @Override
            public void acceptMovementBatch(
                    List<FolioProductSnapshotSourceDao.MovementFact> rows) {
            }

            @Override
            public void acceptProductActivity(
                    FolioProductSnapshotSourceDao.ProductCard product,
                    List<FolioProductSnapshotSourceDao.MonthlyActivity> rows) {
            }
        });

        accumulator.add(movement(1, "SKU-1"));
        accumulator.add(movement(2, "SKU-2"));
        accumulator.add(movement(3, "SKU-1"));

        assertThatThrownBy(accumulator::finish)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-contiguous rows")
                .hasMessageContaining("SKU-1");
    }

    private static FolioProductSnapshotSourceDao.MovementFact movement(long recno, String sku) {
        var base = movement(recno, "*ПРЕДОПЛАТА", "REGULAR", "SALE",
                "1", "2", "1");
        return new FolioProductSnapshotSourceDao.MovementFact(
                base.movementRecno(), base.documentId(), base.documentNumber(),
                base.documentDate(), sku, base.quantity(), base.signedQuantity(),
                base.saleAmount(), base.accountingValue(), base.signedAccountingValue(),
                base.movementType(), base.documentType(), base.operationKind(),
                base.accounted(), base.returnFlag(), base.movementClass(),
                base.stockDirection(), base.demandMode(), base.paymentTerms(),
                base.customerSegment(), base.counterpartyShortName(),
                base.counterpartyName(), base.organizationType(), base.currentSupplier(),
                base.supplierState(), base.affectsStock(), base.affectsFinancialSales(),
                base.affectsPlanningDemand());
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

    private static FolioProductSnapshotSourceDao.ProductCard product(String sku) {
        return new FolioProductSnapshotSourceDao.ProductCard(
                sku, sku, "digest", null, "MISSING",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 0, null, null, null, null, 0, false);
    }
}
