package org.example.proect.lavka.dao.folio;

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
        if (warehouse.rawAccountingCode() == null
                || warehouse.rawAccountingCode().compareTo(new BigDecimal("1000")) != 0) {
            throw new IllegalArgumentException(
                    "Product snapshot v1 supports only Folio average accounting mode N_2=1000");
        }
        if (warehouse.accountingGroup() != null) {
            throw new IllegalArgumentException(
                    "Product snapshot v1 supports only ungrouped Folio warehouses N_4=NULL");
        }

        Map<String, MutableCard> cards = readCards(warehouse, queryTimeoutSeconds);
        long movementRows = readMovementFingerprints(
                warehouseId, cards, queryTimeoutSeconds);
        readPriceRuleFingerprints(warehouseId, cards, queryTimeoutSeconds);
        readOpeningDeltas(warehouseId, horizonStart, cards, queryTimeoutSeconds);
        List<MonthlyActivity> monthly = readMonthlyActivity(
                warehouseId, horizonStart, asOfDate.plusDays(1), queryTimeoutSeconds);

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
                monthly,
                movementRows
        );
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
                    SELECT a.COD_ARTIC, a.NAME_ARTIC, a.NACH_KOLCH, a.KON_KOLCH,
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

    private List<MonthlyActivity> readMonthlyActivity(int warehouseId,
                                                      LocalDate start,
                                                      LocalDate endExclusive,
                                                      int timeout) {
        return jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT a.COD_ARTIC, YEAR(m.DATE_PREDM) AS YR, MONTH(m.DATE_PREDM) AS MN,
                           SUM(CASE WHEN m.TYPDOCM_PR=? AND ISNULL(m.VOZVRAT_PR,0)=0
                                    THEN ISNULL(m.KOLC_PREDM,0) ELSE 0 END) AS RECEIPT_QTY,
                           SUM(CASE WHEN m.TYPDOCM_PR=? AND ISNULL(m.VOZVRAT_PR,0)=0
                                    THEN ISNULL(m.SUM_UCHET,0) ELSE 0 END) AS RECEIPT_COST,
                           SUM(CASE WHEN m.TYPDOCM_PR=? AND ISNULL(m.VOZVRAT_PR,0)=0
                                         AND ISNULL(o.MY_ORGANIZ,'')<>?
                                    THEN ISNULL(m.KOLC_PREDM,0) ELSE 0 END) AS SALES_QTY,
                           SUM(CASE WHEN m.TYPDOCM_PR=? AND ISNULL(m.VOZVRAT_PR,0)=0
                                         AND ISNULL(o.MY_ORGANIZ,'')<>?
                                    THEN ISNULL(m.SUM_PREDM,0) ELSE 0 END) AS SALES_REVENUE,
                           SUM(CASE WHEN m.TYPDOCM_PR=? AND ISNULL(m.VOZVRAT_PR,0)=0
                                         AND ISNULL(o.MY_ORGANIZ,'')<>?
                                    THEN ISNULL(m.SUM_UCHET,0) ELSE 0 END) AS SALES_COGS,
                           SUM(CASE WHEN ISNULL(m.VOZVRAT_PR,0)=1
                                    THEN ISNULL(m.KOLC_PREDM,0) ELSE 0 END) AS RETURN_QTY,
                           SUM(CASE WHEN ISNULL(m.VOZVRAT_PR,0)=1
                                    THEN ISNULL(m.SUM_PREDM,0) ELSE 0 END) AS RETURN_REVENUE,
                           MAX(CASE WHEN m.TYPDOCM_PR=? THEN m.DATE_PREDM ELSE NULL END)
                               AS LAST_RECEIPT,
                           MAX(CASE WHEN m.TYPDOCM_PR=? AND ISNULL(m.VOZVRAT_PR,0)=0
                                         AND ISNULL(o.MY_ORGANIZ,'')<>?
                                    THEN m.DATE_PREDM ELSE NULL END) AS LAST_SALE,
                           SUM(CASE m.TYPDOCM_PR WHEN ? THEN ISNULL(m.KOLC_PREDM,0)
                                                WHEN ? THEN -ISNULL(m.KOLC_PREDM,0)
                                                ELSE 0 END) AS NET_QTY,
                           SUM(CASE m.TYPDOCM_PR WHEN ? THEN ISNULL(m.SUM_UCHET,0)
                                                WHEN ? THEN -ISNULL(m.SUM_UCHET,0)
                                                ELSE 0 END) AS NET_VALUE
                      FROM dbo.SCL_MOVE m WITH (HOLDLOCK)
                      JOIN dbo.SCL_ARTC a WITH (HOLDLOCK)
                        ON a.ID_SCLAD=m.ID_SCLAD AND a.COD_ARTIC=m.NAME_PREDM
                      LEFT JOIN dbo._PARTNER o WITH (HOLDLOCK) ON o.N_USER=m.ORG_PREDM
                     WHERE m.ID_SCLAD=? AND m.STND_UCHET=1
                       AND m.TYPDOCM_PR IN (?,?)
                       AND m.DATE_PREDM>=? AND m.DATE_PREDM<?
                     GROUP BY a.COD_ARTIC,YEAR(m.DATE_PREDM),MONTH(m.DATE_PREDM)
                     ORDER BY a.COD_ARTIC,YEAR(m.DATE_PREDM),MONTH(m.DATE_PREDM)
                    """);
            int p = 1;
            ps.setQueryTimeout(timeout);
            ps.setString(p++, RECEIPT); ps.setString(p++, RECEIPT);
            ps.setString(p++, EXPENSE); ps.setString(p++, "\u042f");
            ps.setString(p++, EXPENSE); ps.setString(p++, "\u042f");
            ps.setString(p++, EXPENSE); ps.setString(p++, "\u042f");
            ps.setString(p++, RECEIPT);
            ps.setString(p++, EXPENSE); ps.setString(p++, "\u042f");
            ps.setString(p++, RECEIPT); ps.setString(p++, EXPENSE);
            ps.setString(p++, RECEIPT); ps.setString(p++, EXPENSE);
            ps.setInt(p++, warehouseId);
            ps.setString(p++, RECEIPT); ps.setString(p++, EXPENSE);
            ps.setTimestamp(p++, Timestamp.valueOf(start.atStartOfDay()));
            ps.setTimestamp(p, Timestamp.valueOf(endExclusive.atStartOfDay()));
            return ps;
        }, (rs, n) -> new MonthlyActivity(
                trim(rs.getString("COD_ARTIC")),
                LocalDate.of(rs.getInt("YR"), rs.getInt("MN"), 1),
                decimal(rs, "RECEIPT_QTY"), decimal(rs, "RECEIPT_COST"),
                decimal(rs, "SALES_QTY"), decimal(rs, "SALES_REVENUE"),
                decimal(rs, "SALES_COGS"), decimal(rs, "RETURN_QTY"),
                decimal(rs, "RETURN_REVENUE"), date(rs, "LAST_RECEIPT"),
                date(rs, "LAST_SALE"), decimal(rs, "NET_QTY"),
                decimal(rs, "NET_VALUE")
        ));
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
                          List<MonthlyActivity> monthlyActivity,
                          long movementRows) {
    }

    public record Warehouse(String databaseName, int warehouseId, String warehouseName,
                            BigDecimal rawAccountingCode, BigDecimal accountingGroup) {
    }

    public record ProductCard(
            String sku, String productName, String sourceDigest,
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
            BigDecimal returnQuantity, BigDecimal returnRevenue,
            LocalDate lastReceiptDate, LocalDate lastSaleDate,
            BigDecimal netQuantity, BigDecimal netValue) {
    }

    private static final class MutableCard {
        private final List<String> digestValues = new ArrayList<>();
        private final String sku;
        private final String productName;
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
}
