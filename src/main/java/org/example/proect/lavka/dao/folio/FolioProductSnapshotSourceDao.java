package org.example.proect.lavka.dao.folio;

import org.example.proect.lavka.service.folio.FolioAccountingMode;
import org.example.proect.lavka.service.folio.FolioAccountingModeUnsupportedException;
import org.example.proect.lavka.service.folio.FolioProductMovementClassifier;
import org.example.proect.lavka.service.folio.FolioProductMovementClassifier.Classification;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only, SQL Server 2000 compatible source capture for product snapshots. */
@Repository
public class FolioProductSnapshotSourceDao {

    private static final String RECEIPT = "\u041f";
    private static final String EXPENSE = "\u0420";

    private final JdbcTemplate jdbc;

    public FolioProductSnapshotSourceDao(
            @Qualifier("folioJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String currentDatabaseName() {
        return trim(jdbc.queryForObject("SELECT DB_NAME()", String.class));
    }

    public Capture capture(int warehouseId, LocalDate horizonStart, LocalDate asOfDate,
                           int queryTimeoutSeconds) {
        Warehouse warehouse = readWarehouse(warehouseId, queryTimeoutSeconds);
        validateAccountingMode(warehouse);
        if (warehouse.accountingGroup() != null) {
            throw new IllegalArgumentException(
                    "Product snapshot v1 supports only ungrouped Folio warehouses N_4=NULL");
        }

        Map<String, MutableCard> cards = readCards(warehouse, queryTimeoutSeconds);
        long movementRows = readMovementFingerprints(
                warehouseId, cards, queryTimeoutSeconds);
        readPriceRuleFingerprints(warehouseId, cards, queryTimeoutSeconds);
        readOpeningDeltas(warehouseId, horizonStart, cards, queryTimeoutSeconds);
        List<MovementFact> movements = readMovementFacts(
                warehouseId, horizonStart, asOfDate.plusDays(1), queryTimeoutSeconds);
        List<MonthlyActivity> monthly = aggregateMonthlyActivity(movements);

        List<ProductCard> products = cards.values().stream()
                .map(MutableCard::finish)
                .toList();
        MessageDigest warehouseDigest = digest();
        add(warehouseDigest, "folio-product-source/v1");
        add(warehouseDigest, warehouse.databaseName());
        add(warehouseDigest, Integer.toString(warehouseId));
        for (ProductCard product : products) {
            add(warehouseDigest, product.sku());
            add(warehouseDigest, product.sourceDigest());
        }
        return new Capture(
                warehouse,
                HexFormat.of().formatHex(warehouseDigest.digest()),
                products,
                movements,
                monthly,
                movementRows
        );
    }

    static void validateAccountingMode(Warehouse warehouse) {
        Integer rawCode = integerOrNull(warehouse.rawAccountingCode());
        FolioAccountingMode.Decoded mode = FolioAccountingMode.decode(rawCode);
        if (!FolioAccountingMode.supportsProductSnapshot(rawCode)) {
            String recommendation = "Exclude this warehouse from product snapshot until its "
                    + "SCLAD_R.N_2 mode has a separate verified implementation; do not change N_2 automatically";
            throw new FolioAccountingModeUnsupportedException(
                    "PRODUCT_SNAPSHOT_ACCOUNTING_MODE_UNSUPPORTED",
                    rawCode,
                    mode.name(),
                    recommendation,
                    "Unsupported Folio accounting mode: SCLAD_R.N_2=" + rawCode
                            + ", mode=" + mode.name()
                            + ", periodMode=" + mode.periodMode()
                            + ", includeTax=" + mode.includeTax()
                            + ". " + recommendation
            );
        }
    }

    private static Integer integerOrNull(BigDecimal value) {
        if (value == null) return null;
        try {
            return value.stripTrailingZeros().intValueExact();
        } catch (ArithmeticException ignored) {
            return null;
        }
    }

    /**
     * Captures the same technical fingerprint as the warehouse snapshot for
     * one canonical SKU. Recalculation calls this inside its MSSQL transaction
     * after postconditions, before commit.
     */
    public ProductFingerprint captureProductFingerprint(int warehouseId, String sku,
                                                        int queryTimeoutSeconds) {
        Warehouse warehouse = readWarehouse(warehouseId, queryTimeoutSeconds);
        Map<String, MutableCard> cards = readCards(
                warehouse, queryTimeoutSeconds, trim(sku));
        if (cards.size() != 1) {
            throw new IllegalArgumentException(
                    "Folio product does not exist in warehouse " + warehouseId + ": " + sku);
        }
        String canonicalSku = cards.keySet().iterator().next();
        readMovementFingerprints(
                warehouseId, cards, queryTimeoutSeconds, canonicalSku);
        readPriceRuleFingerprints(
                warehouseId, cards, queryTimeoutSeconds, canonicalSku);
        ProductCard card = cards.values().iterator().next().finish();
        return new ProductFingerprint(
                warehouse.databaseName(), warehouseId, card.sku(), card.sourceDigest(),
                card.productName(), card.movementCount(), card.minRecno(), card.maxRecno(),
                card.firstMovementDate(), card.lastMovementDate(), card.priceRuleCount());
    }

    private Warehouse readWarehouse(int warehouseId, int timeout) {
        List<Warehouse> rows = jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT DB_NAME() AS DB_NAME, ID_SCLAD, NAME_SCLAD, N_2, N_4
                      FROM dbo.SCLAD_R WITH (HOLDLOCK)
                     WHERE ID_SCLAD = ?
                    """);
            ps.setQueryTimeout(timeout);
            ps.setInt(1, warehouseId);
            return ps;
        }, (rs, n) -> new Warehouse(
                trim(rs.getString("DB_NAME")),
                rs.getInt("ID_SCLAD"),
                trim(rs.getString("NAME_SCLAD")),
                decimalOrNull(rs, "N_2"),
                decimalOrNull(rs, "N_4")
        ));
        if (rows.size() != 1) {
            throw new IllegalArgumentException("Folio warehouse does not exist: " + warehouseId);
        }
        return rows.get(0);
    }

    private Map<String, MutableCard> readCards(Warehouse warehouse, int timeout) {
        return readCards(warehouse, timeout, null);
    }

    private Map<String, MutableCard> readCards(Warehouse warehouse, int timeout,
                                               String skuFilter) {
        Map<String, MutableCard> result = new LinkedHashMap<>();
        jdbc.query(con -> {
            String sql = """
                    SELECT a.COD_ARTIC, a.NAME_ARTIC, a.DOP2_ARTIC,
                           a.NACH_KOLCH, a.KON_KOLCH,
                           a.REZ_KOLCH, a.KOL_SUM, a.UCHET_SUM, a.UCHET_CENA,
                           a.UCHET_0_C, a.UCHET_0_VL, a.TIP_TOVR, a.PRIZN_VALT,
                           a.FIX_NACEN, a.CENA_ARTIC, a.CENA_VALT, a.CENA_BZNAL,
                           a.CENA_V_BZN, a.NDS_ARTIC, a.COEF_BZNAL,
                           CASE WHEN EXISTS (
                               SELECT 1 FROM dbo.TIP_TOVR t
                                WHERE t.SIGNIFIC = a.TIP_TOVR
                                  AND t.CHECK_SAVE = 0 AND t.SHOW_OSTATOK = 0
                           ) THEN 1 ELSE 0 END AS HIDDEN_FOR_ACCOUNTING
                      FROM dbo.SCL_ARTC a WITH (HOLDLOCK)
                     WHERE a.ID_SCLAD = ?
                    """ + (skuFilter == null ? "" : " AND a.COD_ARTIC = ?\n") + """
                     ORDER BY a.COD_ARTIC
                    """;
            var ps = con.prepareStatement(sql);
            ps.setQueryTimeout(timeout);
            ps.setInt(1, warehouse.warehouseId());
            if (skuFilter != null) ps.setString(2, skuFilter);
            return ps;
        }, rs -> {
            String sku = trim(rs.getString("COD_ARTIC"));
            if (sku == null || sku.isBlank()) {
                throw new IllegalStateException("Folio contains a warehouse card without SKU");
            }
            MutableCard card = new MutableCard(
                    warehouse, sku, trim(rs.getString("NAME_ARTIC")),
                    trim(rs.getString("DOP2_ARTIC")),
                    decimal(rs, "NACH_KOLCH"), decimal(rs, "KON_KOLCH"),
                    decimal(rs, "REZ_KOLCH"), decimal(rs, "KOL_SUM"),
                    decimal(rs, "UCHET_SUM"), decimal(rs, "UCHET_CENA"),
                    decimal(rs, "UCHET_0_C"), decimal(rs, "UCHET_0_VL"),
                    trim(rs.getString("TIP_TOVR")), booleanOrNull(rs, "PRIZN_VALT"),
                    booleanOrNull(rs, "FIX_NACEN"), decimal(rs, "CENA_ARTIC"),
                    decimal(rs, "CENA_VALT"), decimal(rs, "CENA_BZNAL"),
                    decimal(rs, "CENA_V_BZN"), decimal(rs, "NDS_ARTIC"),
                    decimal(rs, "COEF_BZNAL"), rs.getBoolean("HIDDEN_FOR_ACCOUNTING")
            );
            if (result.put(sku, card) != null) {
                throw new IllegalStateException("Duplicate Folio warehouse card: " + sku);
            }
        });
        return result;
    }

    private long readMovementFingerprints(int warehouseId, Map<String, MutableCard> cards,
                                          int timeout) {
        return readMovementFingerprints(warehouseId, cards, timeout, null);
    }

    private long readMovementFingerprints(int warehouseId, Map<String, MutableCard> cards,
                                          int timeout, String skuFilter) {
        final long[] total = {0};
        jdbc.query(con -> {
            String sql = """
                    SELECT a.COD_ARTIC, COUNT(*) AS MOVEMENT_COUNT,
                           MIN(m.RECNO) AS MIN_RECNO, MAX(m.RECNO) AS MAX_RECNO,
                           MIN(m.DATE_PREDM) AS MIN_DATE, MAX(m.DATE_PREDM) AS MAX_DATE,
                           CHECKSUM_AGG(BINARY_CHECKSUM(
                               m.RECNO, m.DATE_PREDM, m.TYPDOCM_PR, m.NUMDOCM_PR,
                               m.ORG_PREDM, m.VOZVRAT_PR, m.KOLC_PREDM
                           )) AS IDENTITY_CHECKSUM,
                           CHECKSUM_AGG(BINARY_CHECKSUM(
                               m.RECNO, m.SUM_PREDM, m.SUM_VALUT,
                               m.NALOGMONEY, m.NALOGVALUT
                           )) AS SOURCE_CHECKSUM,
                           CHECKSUM_AGG(BINARY_CHECKSUM(
                               m.RECNO, m.PARTIA, m.SROK, m.SUM_UCHET, m.SUM_UCVAL
                           )) AS ACCOUNTING_CHECKSUM
                      FROM dbo.SCL_MOVE m WITH (HOLDLOCK)
                      JOIN dbo.SCL_ARTC a WITH (HOLDLOCK)
                        ON a.ID_SCLAD=m.ID_SCLAD AND a.COD_ARTIC=m.NAME_PREDM
                     WHERE m.ID_SCLAD=? AND m.STND_UCHET=1 AND m.TYPDOCM_PR<>?
                    """ + (skuFilter == null ? "" : " AND a.COD_ARTIC = ?\n") + """
                     GROUP BY a.COD_ARTIC
                    """;
            var ps = con.prepareStatement(sql);
            ps.setQueryTimeout(timeout);
            ps.setInt(1, warehouseId);
            ps.setString(2, "\u0421");
            if (skuFilter != null) ps.setString(3, skuFilter);
            return ps;
        }, rs -> {
            MutableCard card = cards.get(trim(rs.getString("COD_ARTIC")));
            if (card == null) {
                throw new IllegalStateException("Movement aggregate has no canonical card");
            }
            card.movementCount = rs.getLong("MOVEMENT_COUNT");
            card.minRecno = nullableLong(rs, "MIN_RECNO");
            card.maxRecno = nullableLong(rs, "MAX_RECNO");
            card.firstMovementDate = date(rs, "MIN_DATE");
            card.lastMovementDate = date(rs, "MAX_DATE");
            card.identityChecksum = nullableLong(rs, "IDENTITY_CHECKSUM");
            card.sourceChecksum = nullableLong(rs, "SOURCE_CHECKSUM");
            card.accountingChecksum = nullableLong(rs, "ACCOUNTING_CHECKSUM");
            total[0] += card.movementCount;
        });
        return total[0];
    }

    private void readPriceRuleFingerprints(int warehouseId,
                                           Map<String, MutableCard> cards,
                                           int timeout) {
        readPriceRuleFingerprints(warehouseId, cards, timeout, null);
    }

    private void readPriceRuleFingerprints(int warehouseId,
                                           Map<String, MutableCard> cards,
                                           int timeout, String skuFilter) {
        jdbc.query(con -> {
            String sql = """
                    SELECT a.COD_ARTIC, COUNT(*) AS RULE_COUNT,
                           MIN(p.ID) AS MIN_ID, MAX(p.ID) AS MAX_ID,
                           CHECKSUM_AGG(BINARY_CHECKSUM(p.ID,p.COEF_PRICE)) AS RULE_CHECKSUM
                      FROM dbo.SCL_PRIC p WITH (HOLDLOCK)
                      JOIN dbo.SCL_ARTC a WITH (HOLDLOCK)
                        ON a.ID_SCLAD=p.ID_SCLAD AND a.COD_ARTIC=p.COD_ARTIC
                     WHERE p.ID_SCLAD=?
                    """ + (skuFilter == null ? "" : " AND a.COD_ARTIC = ?\n") + """
                     GROUP BY a.COD_ARTIC
                    """;
            var ps = con.prepareStatement(sql);
            ps.setQueryTimeout(timeout);
            ps.setInt(1, warehouseId);
            if (skuFilter != null) ps.setString(2, skuFilter);
            return ps;
        }, rs -> {
            MutableCard card = cards.get(trim(rs.getString("COD_ARTIC")));
            if (card != null) {
                card.priceRuleCount = rs.getInt("RULE_COUNT");
                card.minPriceRuleId = nullableLong(rs, "MIN_ID");
                card.maxPriceRuleId = nullableLong(rs, "MAX_ID");
                card.priceRuleChecksum = nullableLong(rs, "RULE_CHECKSUM");
            }
        });
    }

    private void readOpeningDeltas(int warehouseId, LocalDate horizonStart,
                                   Map<String, MutableCard> cards, int timeout) {
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT a.COD_ARTIC,
                           SUM(CASE m.TYPDOCM_PR WHEN ? THEN ISNULL(m.KOLC_PREDM,0)
                                                WHEN ? THEN -ISNULL(m.KOLC_PREDM,0)
                                                ELSE 0 END) AS QTY_DELTA,
                           SUM(CASE m.TYPDOCM_PR WHEN ? THEN ISNULL(m.SUM_UCHET,0)
                                                WHEN ? THEN -ISNULL(m.SUM_UCHET,0)
                                                ELSE 0 END) AS VALUE_DELTA
                      FROM dbo.SCL_MOVE m WITH (HOLDLOCK)
                      JOIN dbo.SCL_ARTC a WITH (HOLDLOCK)
                        ON a.ID_SCLAD=m.ID_SCLAD AND a.COD_ARTIC=m.NAME_PREDM
                     WHERE m.ID_SCLAD=? AND m.STND_UCHET=1
                       AND m.TYPDOCM_PR IN (?,?) AND m.DATE_PREDM < ?
                     GROUP BY a.COD_ARTIC
                    """);
            ps.setQueryTimeout(timeout);
            ps.setString(1, RECEIPT);
            ps.setString(2, EXPENSE);
            ps.setString(3, RECEIPT);
            ps.setString(4, EXPENSE);
            ps.setInt(5, warehouseId);
            ps.setString(6, RECEIPT);
            ps.setString(7, EXPENSE);
            ps.setTimestamp(8, Timestamp.valueOf(horizonStart.atStartOfDay()));
            return ps;
        }, rs -> {
            MutableCard card = cards.get(trim(rs.getString("COD_ARTIC")));
            if (card != null) {
                card.openingQuantityDelta = decimal(rs, "QTY_DELTA");
                card.openingValueDelta = decimal(rs, "VALUE_DELTA");
            }
        });
    }

    private List<MovementFact> readMovementFacts(int warehouseId,
                                                  LocalDate start,
                                                  LocalDate endExclusive,
                                                  int timeout) {
        return jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT m.RECNO, m.UNICUM_NUM, m.NUMDOCM_PR, m.DATE_PREDM,
                           m.NAME_PREDM, m.KOLC_PREDM, m.SUM_PREDM, m.SUM_UCHET,
                           m.TYPDOCM_PR, n.TYPE_DOC,
                           CASE WHEN ISNULL(n.VID_DOC,'')<>'' THEN n.VID_DOC
                                ELSE m.VID_DOC END AS OPERATION_KIND,
                           m.STND_UCHET, m.VOZVRAT_PR,
                           CASE WHEN ISNULL(m.ORG_PREDM,'')<>'' THEN m.ORG_PREDM
                                ELSE n.BRIEFORG END AS COUNTERPARTY_ID,
                           o.NAME_USER AS COUNTERPARTY_NAME,
                           o.MY_ORGANIZ AS ORGANIZATION_TYPE,
                           a.DOP2_ARTIC AS CURRENT_SUPPLIER
                      FROM dbo.SCL_MOVE m WITH (HOLDLOCK)
                      LEFT JOIN dbo.SCL_NAKL n WITH (HOLDLOCK)
                        ON n.UNICUM_NUM=m.UNICUM_NUM
                      LEFT JOIN dbo._PARTNER o WITH (HOLDLOCK)
                        ON o.N_USER=CASE WHEN ISNULL(m.ORG_PREDM,'')<>''
                                       THEN m.ORG_PREDM ELSE n.BRIEFORG END
                      LEFT JOIN dbo.SCL_ARTC a WITH (HOLDLOCK)
                        ON a.ID_SCLAD=m.ID_SCLAD AND a.COD_ARTIC=m.NAME_PREDM
                     WHERE m.ID_SCLAD=? AND m.TYPDOCM_PR IN (?,?,?)
                       AND m.DATE_PREDM>=? AND m.DATE_PREDM<?
                     ORDER BY m.RECNO
                    """);
            ps.setQueryTimeout(timeout);
            ps.setInt(1, warehouseId);
            ps.setString(2, RECEIPT);
            ps.setString(3, EXPENSE);
            ps.setString(4, "\u0421");
            ps.setTimestamp(5, Timestamp.valueOf(start.atStartOfDay()));
            ps.setTimestamp(6, Timestamp.valueOf(endExclusive.atStartOfDay()));
            return ps;
        }, (rs, n) -> movementFact(rs));
    }

    private static MovementFact movementFact(ResultSet rs) throws SQLException {
        String movementType = trim(rs.getString("TYPDOCM_PR"));
        String documentType = trim(rs.getString("TYPE_DOC"));
        String operationKind = trim(rs.getString("OPERATION_KIND"));
        String organizationType = partnerOrganizationType(rs.getString("ORGANIZATION_TYPE"));
        boolean accounted = rs.getBoolean("STND_UCHET");
        boolean returnFlag = rs.getBoolean("VOZVRAT_PR");
        Classification classification = FolioProductMovementClassifier.classify(
                movementType, documentType, operationKind, organizationType,
                accounted, returnFlag);
        BigDecimal quantity = decimal(rs, "KOLC_PREDM");
        BigDecimal accountingValue = decimal(rs, "SUM_UCHET");
        BigDecimal signedQuantity = signed(
                quantity, classification.stockDirection(), classification.affectsStock());
        BigDecimal signedAccountingValue = signed(
                accountingValue, classification.stockDirection(), classification.affectsStock());
        String currentSupplier = trim(rs.getString("CURRENT_SUPPLIER"));
        return new MovementFact(
                rs.getLong("RECNO"),
                nullableLong(rs, "UNICUM_NUM"),
                decimalOrNull(rs, "NUMDOCM_PR"),
                date(rs, "DATE_PREDM"),
                trim(rs.getString("NAME_PREDM")),
                quantity,
                signedQuantity,
                decimal(rs, "SUM_PREDM"),
                accountingValue,
                signedAccountingValue,
                movementType,
                documentType,
                operationKind,
                accounted,
                returnFlag,
                classification.movementClass(),
                classification.stockDirection(),
                classification.demandMode(),
                classification.paymentTerms(),
                classification.customerSegment(),
                trim(rs.getString("COUNTERPARTY_ID")),
                trim(rs.getString("COUNTERPARTY_NAME")),
                organizationType,
                currentSupplier,
                supplierState(currentSupplier),
                classification.affectsStock(),
                classification.affectsFinancialSales(),
                classification.affectsPlanningDemand()
        );
    }

    static String partnerOrganizationType(String rawValue) {
        String value = trim(rawValue);
        return value != null && value.length() == 1 ? value : "";
    }

    static List<MonthlyActivity> aggregateMonthlyActivity(List<MovementFact> movements) {
        Map<MonthlyKey, MutableMonthlyActivity> rows = new LinkedHashMap<>();
        for (MovementFact movement : movements) {
            if (movement.sku() == null || movement.sku().isBlank()
                    || movement.documentDate() == null) continue;
            MonthlyKey key = new MonthlyKey(
                    movement.sku(), movement.documentDate().withDayOfMonth(1));
            rows.computeIfAbsent(key, ignored -> new MutableMonthlyActivity(key))
                    .add(movement);
        }
        return rows.values().stream()
                .map(MutableMonthlyActivity::finish)
                .sorted(Comparator.comparing(MonthlyActivity::sku)
                        .thenComparing(MonthlyActivity::monthStart))
                .toList();
    }

    private static BigDecimal signed(BigDecimal value, String direction, boolean affectsStock) {
        if (!affectsStock) return BigDecimal.ZERO;
        if ("IN".equals(direction)) return value;
        if ("OUT".equals(direction)) return value.negate();
        return BigDecimal.ZERO;
    }

    private static String supplierState(String supplier) {
        return supplier == null || supplier.isBlank() ? "MISSING" : "CURRENT";
    }

    private static LocalDate max(LocalDate first, LocalDate second) {
        return first == null || second.isAfter(first) ? second : first;
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "<null>" : value).getBytes(StandardCharsets.UTF_8);
        digest.update((byte) 0x1e);
        digest.update(bytes);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BigDecimal decimal(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal decimalOrNull(ResultSet rs, String column) throws SQLException {
        return rs.getBigDecimal(column);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Boolean booleanOrNull(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime().toLocalDate();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    public record Capture(Warehouse warehouse, String warehouseDigest,
                          List<ProductCard> products,
                          List<MovementFact> movements,
                          List<MonthlyActivity> monthlyActivity,
                          long movementRows) {
    }

    public record Warehouse(String databaseName, int warehouseId, String warehouseName,
                            BigDecimal rawAccountingCode, BigDecimal accountingGroup) {
    }

    public record ProductCard(
            String sku, String productName, String sourceDigest,
            String currentSupplier, String supplierState,
            BigDecimal initialQuantity, BigDecimal physicalQuantity,
            BigDecimal reservedQuantity, BigDecimal accountingQuantity,
            BigDecimal accountingAmount, BigDecimal accountingPrice,
            BigDecimal initialAccountingPrice, BigDecimal initialAccountingCurrencyPrice,
            BigDecimal openingQuantityAtHorizon, BigDecimal openingValueAtHorizon,
            long movementCount, Long minRecno, Long maxRecno,
            LocalDate firstMovementDate, LocalDate lastMovementDate,
            int priceRuleCount, boolean hiddenForAccounting) {
    }

    public record ProductFingerprint(
            String sourceDatabase, int warehouseId, String sku, String sourceDigest,
            String productName, long movementCount, Long minRecno, Long maxRecno,
            LocalDate firstMovementDate, LocalDate lastMovementDate,
            int priceRuleCount) {
    }

    public record MonthlyActivity(
            String sku, LocalDate monthStart,
            BigDecimal receiptQuantity, BigDecimal receiptCost,
            BigDecimal salesQuantity, BigDecimal salesRevenue, BigDecimal salesCogs,
            BigDecimal regularSalesQuantity, BigDecimal regularSalesRevenue,
            BigDecimal regularSalesCogs,
            BigDecimal oneOffSalesQuantity, BigDecimal oneOffSalesRevenue,
            BigDecimal oneOffSalesCogs,
            BigDecimal returnQuantity, BigDecimal returnRevenue,
            LocalDate lastReceiptDate, LocalDate lastSaleDate,
            LocalDate lastRegularSaleDate,
            BigDecimal netQuantity, BigDecimal netValue) {
    }

    public record MovementFact(
            long movementRecno,
            Long documentId,
            BigDecimal documentNumber,
            LocalDate documentDate,
            String sku,
            BigDecimal quantity,
            BigDecimal signedQuantity,
            BigDecimal saleAmount,
            BigDecimal accountingValue,
            BigDecimal signedAccountingValue,
            String movementType,
            String documentType,
            String operationKind,
            boolean accounted,
            boolean returnFlag,
            String movementClass,
            String stockDirection,
            String demandMode,
            String paymentTerms,
            String customerSegment,
            String counterpartyShortName,
            String counterpartyName,
            String organizationType,
            String currentSupplier,
            String supplierState,
            boolean affectsStock,
            boolean affectsFinancialSales,
            boolean affectsPlanningDemand) {
    }

    private static final class MutableCard {
        private final List<String> digestValues = new ArrayList<>();
        private final String sku;
        private final String productName;
        private final String currentSupplier;
        private final String supplierState;
        private final BigDecimal initialQuantity;
        private final BigDecimal physicalQuantity;
        private final BigDecimal reservedQuantity;
        private final BigDecimal accountingQuantity;
        private final BigDecimal accountingAmount;
        private final BigDecimal accountingPrice;
        private final BigDecimal initialAccountingPrice;
        private final BigDecimal initialAccountingCurrencyPrice;
        private final boolean hiddenForAccounting;
        private BigDecimal openingQuantityDelta = BigDecimal.ZERO;
        private BigDecimal openingValueDelta = BigDecimal.ZERO;
        private long movementCount;
        private Long minRecno;
        private Long maxRecno;
        private LocalDate firstMovementDate;
        private LocalDate lastMovementDate;
        private Long identityChecksum;
        private Long sourceChecksum;
        private Long accountingChecksum;
        private int priceRuleCount;
        private Long minPriceRuleId;
        private Long maxPriceRuleId;
        private Long priceRuleChecksum;

        private MutableCard(Warehouse warehouse, String sku, String productName,
                            String currentSupplier,
                            BigDecimal initialQuantity, BigDecimal physicalQuantity,
                            BigDecimal reservedQuantity, BigDecimal accountingQuantity,
                            BigDecimal accountingAmount, BigDecimal accountingPrice,
                            BigDecimal initialAccountingPrice,
                            BigDecimal initialAccountingCurrencyPrice,
                            String type, Boolean currency, Boolean fixedMarkup,
                            BigDecimal salePrice, BigDecimal currencyPrice,
                            BigDecimal nonCashPrice, BigDecimal nonCashCurrencyPrice,
                            BigDecimal tax, BigDecimal nonCashCoefficient,
                            boolean hiddenForAccounting) {
            this.sku = sku;
            this.productName = productName == null ? "" : productName;
            this.currentSupplier = currentSupplier;
            this.supplierState = supplierState(currentSupplier);
            this.initialQuantity = initialQuantity;
            this.physicalQuantity = physicalQuantity;
            this.reservedQuantity = reservedQuantity;
            this.accountingQuantity = accountingQuantity;
            this.accountingAmount = accountingAmount;
            this.accountingPrice = accountingPrice;
            this.initialAccountingPrice = initialAccountingPrice;
            this.initialAccountingCurrencyPrice = initialAccountingCurrencyPrice;
            this.hiddenForAccounting = hiddenForAccounting;
            addAll(warehouse.rawAccountingCode(), warehouse.accountingGroup(), sku,
                    initialQuantity, initialAccountingPrice, initialAccountingCurrencyPrice,
                    type, currency, fixedMarkup, salePrice, currencyPrice, nonCashPrice,
                    nonCashCurrencyPrice, tax, nonCashCoefficient, hiddenForAccounting);
        }

        private void addAll(Object... values) {
            for (Object value : values) {
                if (value instanceof BigDecimal decimal) {
                    digestValues.add(decimal.stripTrailingZeros().toPlainString());
                } else {
                    digestValues.add(String.valueOf(value));
                }
            }
        }

        private ProductCard finish() {
            addAll(movementCount, minRecno, maxRecno, firstMovementDate,
                    lastMovementDate, identityChecksum, sourceChecksum,
                    accountingChecksum, priceRuleCount, minPriceRuleId,
                    maxPriceRuleId, priceRuleChecksum);
            MessageDigest md = digest();
            digestValues.forEach(value -> add(md, value));
            return new ProductCard(
                    sku, productName, HexFormat.of().formatHex(md.digest()),
                    currentSupplier, supplierState,
                    initialQuantity, physicalQuantity, reservedQuantity,
                    accountingQuantity, accountingAmount, accountingPrice,
                    initialAccountingPrice, initialAccountingCurrencyPrice,
                    initialQuantity.add(openingQuantityDelta),
                    initialQuantity.multiply(initialAccountingPrice).add(openingValueDelta),
                    movementCount, minRecno, maxRecno, firstMovementDate,
                    lastMovementDate, priceRuleCount, hiddenForAccounting
            );
        }
    }

    private record MonthlyKey(String sku, LocalDate monthStart) {
    }

    private static final class MutableMonthlyActivity {
        private final MonthlyKey key;
        private BigDecimal receiptQuantity = BigDecimal.ZERO;
        private BigDecimal receiptCost = BigDecimal.ZERO;
        private BigDecimal salesQuantity = BigDecimal.ZERO;
        private BigDecimal salesRevenue = BigDecimal.ZERO;
        private BigDecimal salesCogs = BigDecimal.ZERO;
        private BigDecimal regularSalesQuantity = BigDecimal.ZERO;
        private BigDecimal regularSalesRevenue = BigDecimal.ZERO;
        private BigDecimal regularSalesCogs = BigDecimal.ZERO;
        private BigDecimal oneOffSalesQuantity = BigDecimal.ZERO;
        private BigDecimal oneOffSalesRevenue = BigDecimal.ZERO;
        private BigDecimal oneOffSalesCogs = BigDecimal.ZERO;
        private BigDecimal returnQuantity = BigDecimal.ZERO;
        private BigDecimal returnRevenue = BigDecimal.ZERO;
        private BigDecimal netQuantity = BigDecimal.ZERO;
        private BigDecimal netValue = BigDecimal.ZERO;
        private LocalDate lastReceiptDate;
        private LocalDate lastSaleDate;
        private LocalDate lastRegularSaleDate;

        private MutableMonthlyActivity(MonthlyKey key) {
            this.key = key;
        }

        private void add(MovementFact movement) {
            netQuantity = netQuantity.add(movement.signedQuantity());
            netValue = netValue.add(movement.signedAccountingValue());
            if (movement.affectsStock() && "IN".equals(movement.stockDirection())
                    && !movement.returnFlag()) {
                receiptQuantity = receiptQuantity.add(movement.quantity());
                receiptCost = receiptCost.add(movement.accountingValue());
                lastReceiptDate = max(lastReceiptDate, movement.documentDate());
            }
            if (movement.affectsFinancialSales()) {
                salesQuantity = salesQuantity.add(movement.quantity());
                salesRevenue = salesRevenue.add(movement.saleAmount());
                salesCogs = salesCogs.add(movement.accountingValue());
                lastSaleDate = max(lastSaleDate, movement.documentDate());
                if (movement.affectsPlanningDemand()) {
                    regularSalesQuantity = regularSalesQuantity.add(movement.quantity());
                    regularSalesRevenue = regularSalesRevenue.add(movement.saleAmount());
                    regularSalesCogs = regularSalesCogs.add(movement.accountingValue());
                    lastRegularSaleDate = max(lastRegularSaleDate, movement.documentDate());
                } else if ("ONE_OFF_ORDER".equals(movement.demandMode())) {
                    oneOffSalesQuantity = oneOffSalesQuantity.add(movement.quantity());
                    oneOffSalesRevenue = oneOffSalesRevenue.add(movement.saleAmount());
                    oneOffSalesCogs = oneOffSalesCogs.add(movement.accountingValue());
                }
            }
            if ("CUSTOMER_RETURN".equals(movement.movementClass()) && movement.accounted()) {
                returnQuantity = returnQuantity.add(movement.quantity());
                returnRevenue = returnRevenue.add(movement.saleAmount());
            }
        }

        private MonthlyActivity finish() {
            return new MonthlyActivity(
                    key.sku(), key.monthStart(), receiptQuantity, receiptCost,
                    salesQuantity, salesRevenue, salesCogs,
                    regularSalesQuantity, regularSalesRevenue, regularSalesCogs,
                    oneOffSalesQuantity, oneOffSalesRevenue, oneOffSalesCogs,
                    returnQuantity, returnRevenue, lastReceiptDate, lastSaleDate,
                    lastRegularSaleDate, netQuantity, netValue);
        }
    }
}
