package org.example.proect.lavka.dao.folio;

import org.example.proect.lavka.dto.folio.FolioCustomerDocumentDetailResponse;
import org.example.proect.lavka.dto.folio.FolioCustomerDocumentType;
import org.example.proect.lavka.dto.folio.FolioCustomerDocumentsResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class FolioCustomerDocumentDao {

    private final JdbcTemplate jdbc;

    public FolioCustomerDocumentDao(@Qualifier("folioJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PartnerRow> findPartner(String partnerShortName) {
        List<PartnerRow> rows = jdbc.query("""
                SELECT N_USER, NAME_USER
                FROM dbo._PARTNER WITH (NOLOCK)
                WHERE N_USER = ?
                """, (rs, rowNum) -> new PartnerRow(
                trimToNull(rs.getString("N_USER")),
                firstNonBlank(rs.getString("NAME_USER"), rs.getString("N_USER"))
        ), partnerShortName);
        return rows.stream().findFirst();
    }

    public List<FolioCustomerDocumentsResponse.DocumentSummary> findDocuments(
            String partnerShortName,
            LocalDate dateFrom,
            LocalDate dateTo,
            List<FolioCustomerDocumentType> types,
            int fetchLimit,
            DocumentCursor cursor) {
        StringBuilder union = new StringBuilder();
        List<Object> params = new ArrayList<>();
        Timestamp from = Timestamp.valueOf(dateFrom.atStartOfDay());
        Timestamp toExclusive = Timestamp.valueOf(dateTo.plusDays(1).atStartOfDay());

        for (FolioCustomerDocumentType type : types) {
            if (!union.isEmpty()) {
                union.append(" UNION ALL ");
            }
            if (type == FolioCustomerDocumentType.PAYMENT) {
                union.append(paymentSummarySql());
                params.add(partnerShortName);
                params.add(from);
                params.add(toExclusive);
            } else {
                union.append(stockDocumentSummarySql(type));
                params.add(partnerShortName);
                params.add(type.folioType());
                params.add(from);
                params.add(toExclusive);
            }
        }

        StringBuilder sql = new StringBuilder("SELECT TOP ")
                .append(fetchLimit)
                .append(" * FROM (")
                .append(union)
                .append(") d");

        if (cursor != null) {
            sql.append("""
                    WHERE d.DOCUMENT_DATE < ?
                       OR (d.DOCUMENT_DATE = ? AND d.TYPE_RANK > ?)
                       OR (d.DOCUMENT_DATE = ? AND d.TYPE_RANK = ? AND d.DOCUMENT_ID < ?)
                    """);
            Timestamp cursorDate = Timestamp.valueOf(cursor.documentDate());
            params.add(cursorDate);
            params.add(cursorDate);
            params.add(cursor.typeRank());
            params.add(cursorDate);
            params.add(cursor.typeRank());
            params.add(cursor.documentId());
        }
        sql.append(" ORDER BY d.DOCUMENT_DATE DESC, d.TYPE_RANK ASC, d.DOCUMENT_ID DESC");

        return jdbc.query(sql.toString(), (rs, rowNum) -> {
            FolioCustomerDocumentType type = FolioCustomerDocumentType.valueOf(rs.getString("DOCUMENT_TYPE"));
            Boolean returnDocument = nullableBoolean(rs.getObject("RETURN_DOCUMENT"));
            return new FolioCustomerDocumentsResponse.DocumentSummary(
                    type,
                    rs.getLong("DOCUMENT_ID"),
                    formatFolioNumber(rs.getDouble("DOCUMENT_NUMBER")),
                    trimToNull(rs.getString("DOCUMENT_SUFFIX")),
                    toLocalDateTime(rs.getTimestamp("DOCUMENT_DATE")),
                    money(rs.getBigDecimal("TOTAL_AMOUNT")),
                    moneyNullable(rs.getBigDecimal("CURRENCY_AMOUNT")),
                    trimToNull(rs.getString("CURRENCY_CODE")),
                    nullableInteger(rs.getObject("WAREHOUSE_ID")),
                    nullableBoolean(rs.getObject("ACCOUNTED")),
                    nullableBoolean(rs.getObject("NON_CASH")),
                    returnDocument,
                    nullableBoolean(rs.getObject("PAYMENT_DIRECTION_RAW")),
                    trimToNull(rs.getString("OPERATION_KIND")),
                    trimToNull(rs.getString("ADDITIONAL_INFO")),
                    rs.getInt("LINE_COUNT"),
                    moneyNullable(rs.getBigDecimal("ALLOCATED_AMOUNT")),
                    type.repeatable() && !Boolean.TRUE.equals(returnDocument) && rs.getInt("LINE_COUNT") > 0,
                    "ACTIVE_LEDGER"
            );
        }, params.toArray());
    }

    public Optional<FolioCustomerDocumentDetailResponse.Document> findStockDocument(
            String partnerShortName,
            FolioCustomerDocumentType type,
            long documentId) {
        List<StockHeader> headers = jdbc.query("""
                SELECT n.UNICUM_NUM, n.N_PLAT_POR, n.DOPN_SCHET, n.DATE_P_POR,
                       n.SUM_POR, n.SUM_VALUT, n.COD_VALUT, n.ID_SCLAD,
                       n.STND_UCHET, n.NOT_NAL, n.VOZVRAT_PR, n.VID_DOC,
                       n.CONTR_POR, n.OSNOVANIE, n.PRIMECH_NC,
                       n.ORGANIZNKL, n.MY_ORGANIZ, n.BRIEFORG, n.FAMILY,
                       n.L_CP1_PLAT, n.L_CP2_PLAT,
                       n.CREATEDATE, n.CORRDATE, n.WHO_CORR
                FROM dbo.SCL_NAKL n WITH (NOLOCK)
                WHERE n.UNICUM_NUM = ?
                  AND n.BRIEFORG = ?
                  AND n.TYPE_DOC = ?
                """, (rs, rowNum) -> new StockHeader(
                rs.getLong("UNICUM_NUM"),
                formatFolioNumber(rs.getDouble("N_PLAT_POR")),
                trimToNull(rs.getString("DOPN_SCHET")),
                toLocalDateTime(rs.getTimestamp("DATE_P_POR")),
                money(rs.getBigDecimal("SUM_POR")),
                moneyNullable(rs.getBigDecimal("SUM_VALUT")),
                trimToNull(rs.getString("COD_VALUT")),
                nullableInteger(rs.getObject("ID_SCLAD")),
                nullableBoolean(rs.getObject("STND_UCHET")),
                nullableBoolean(rs.getObject("NOT_NAL")),
                nullableBoolean(rs.getObject("VOZVRAT_PR")),
                trimToNull(rs.getString("VID_DOC")),
                trimToNull(rs.getString("CONTR_POR")),
                trimToNull(rs.getString("OSNOVANIE")),
                trimToNull(rs.getString("PRIMECH_NC")),
                trimToNull(rs.getString("ORGANIZNKL")),
                trimToNull(rs.getString("MY_ORGANIZ")),
                trimToNull(rs.getString("BRIEFORG")),
                trimToNull(rs.getString("FAMILY")),
                trimToNull(rs.getString("L_CP1_PLAT")),
                trimToNull(rs.getString("L_CP2_PLAT")),
                toLocalDateTime(rs.getTimestamp("CREATEDATE")),
                toLocalDateTime(rs.getTimestamp("CORRDATE")),
                trimToNull(rs.getString("WHO_CORR"))
        ), documentId, partnerShortName, type.folioType());
        if (headers.isEmpty()) {
            return Optional.empty();
        }

        StockHeader header = headers.get(0);
        var requisites = findDocumentRequisites(documentId).orElse(null);
        List<FolioCustomerDocumentDetailResponse.Item> items = findDocumentItems(documentId, header.returnDocument());
        List<FolioCustomerDocumentDetailResponse.LinkedPayment> payments = findLinkedPayments(documentId);
        List<FolioCustomerDocumentDetailResponse.RepeatItem> repeatItems = items.stream()
                .filter(FolioCustomerDocumentDetailResponse.Item::repeatable)
                .map(item -> new FolioCustomerDocumentDetailResponse.RepeatItem(
                        item.sku(), item.name(), item.quantity(), item.price(), item.currencyCode()
                ))
                .toList();
        boolean repeatAllowed = type.repeatable()
                && !Boolean.TRUE.equals(header.returnDocument())
                && !repeatItems.isEmpty();
        String repeatReason = repeatAllowed ? null
                : Boolean.TRUE.equals(header.returnDocument()) ? "RETURN_DOCUMENT"
                : repeatItems.isEmpty() ? "NO_REPEATABLE_ITEMS" : "DOCUMENT_TYPE_NOT_REPEATABLE";

        return Optional.of(new FolioCustomerDocumentDetailResponse.Document(
                type,
                header.documentId(),
                header.documentNumber(),
                header.documentSuffix(),
                header.documentDate(),
                header.totalAmount(),
                header.currencyAmount(),
                header.currencyCode(),
                header.warehouseId(),
                header.accounted(),
                header.nonCash(),
                header.returnDocument(),
                null,
                header.operationKind(),
                header.contractCode(),
                header.basis(),
                header.note(),
                header.payerName(),
                header.receiverName(),
                header.payerShortName(),
                header.folioUser(),
                header.sourceInfo(),
                header.additionalInfo(),
                header.createdAt(),
                header.correctedAt(),
                header.correctedBy(),
                null,
                null,
                requisites,
                null,
                items,
                payments,
                List.of(),
                new FolioCustomerDocumentDetailResponse.RepeatOrder(repeatAllowed, repeatReason, repeatItems),
                "ACTIVE_LEDGER"
        ));
    }

    public Optional<FolioCustomerDocumentDetailResponse.Document> findPayment(
            String partnerShortName,
            long paymentId) {
        List<PaymentHeader> headers = jdbc.query("""
                SELECT p.UNICUM_PLT, p.N_PLAT_POR, p.DATE_P_POR,
                       p.SUM_POR, p.SUMVAL_POR, p.COD_VALUT, p.ID_SCLAD,
                       p.STND_UCHET, p.NOT_NAL, p.TYPE_POR, p.VID_DOC,
                       p.CONTR_POR, p.OSNOVANIE, p.DOCUMN_POR,
                       p.L_NAME_POR, p.ORG_PREDM, p.FAMILY, p.IST_INF,
                       p.CREATEDATE, p.CORRDATE, p.WHO_CORR,
                       p.NOTOVAROST, p.NOTOVARVAL
                FROM dbo.SCL_PLAT p WITH (NOLOCK)
                WHERE p.UNICUM_PLT = ?
                  AND p.ORG_PREDM = ?
                """, (rs, rowNum) -> new PaymentHeader(
                rs.getLong("UNICUM_PLT"),
                formatFolioNumber(rs.getDouble("N_PLAT_POR")),
                toLocalDateTime(rs.getTimestamp("DATE_P_POR")),
                money(rs.getBigDecimal("SUM_POR")),
                moneyNullable(rs.getBigDecimal("SUMVAL_POR")),
                trimToNull(rs.getString("COD_VALUT")),
                nullableInteger(rs.getObject("ID_SCLAD")),
                nullableBoolean(rs.getObject("STND_UCHET")),
                nullableBoolean(rs.getObject("NOT_NAL")),
                nullableBoolean(rs.getObject("TYPE_POR")),
                trimToNull(rs.getString("VID_DOC")),
                trimToNull(rs.getString("CONTR_POR")),
                trimToNull(rs.getString("OSNOVANIE")),
                trimToNull(rs.getString("DOCUMN_POR")),
                trimToNull(rs.getString("L_NAME_POR")),
                trimToNull(rs.getString("ORG_PREDM")),
                trimToNull(rs.getString("FAMILY")),
                trimToNull(rs.getString("IST_INF")),
                toLocalDateTime(rs.getTimestamp("CREATEDATE")),
                toLocalDateTime(rs.getTimestamp("CORRDATE")),
                trimToNull(rs.getString("WHO_CORR")),
                moneyNullable(rs.getBigDecimal("NOTOVAROST")),
                moneyNullable(rs.getBigDecimal("NOTOVARVAL"))
        ), paymentId, partnerShortName);
        if (headers.isEmpty()) {
            return Optional.empty();
        }

        PaymentHeader header = headers.get(0);
        var requisites = findPaymentRequisites(paymentId).orElse(null);
        List<FolioCustomerDocumentDetailResponse.PaymentAllocation> allocations = findPaymentAllocations(paymentId);

        return Optional.of(new FolioCustomerDocumentDetailResponse.Document(
                FolioCustomerDocumentType.PAYMENT,
                header.paymentId(),
                header.paymentNumber(),
                null,
                header.paymentDate(),
                header.amount(),
                header.currencyAmount(),
                header.currencyCode(),
                header.warehouseId(),
                header.accounted(),
                header.nonCash(),
                null,
                header.paymentDirectionRaw(),
                header.operationKind(),
                header.contractCode(),
                header.basis(),
                header.note(),
                header.payerName(),
                null,
                header.partnerShortName(),
                header.folioUser(),
                header.sourceInfo(),
                null,
                header.createdAt(),
                header.correctedAt(),
                header.correctedBy(),
                header.unallocatedAmount(),
                header.unallocatedCurrencyAmount(),
                null,
                requisites,
                List.of(),
                List.of(),
                allocations,
                new FolioCustomerDocumentDetailResponse.RepeatOrder(
                        false, "PAYMENT_NOT_REPEATABLE", List.of()
                ),
                "ACTIVE_LEDGER"
        ));
    }

    private List<FolioCustomerDocumentDetailResponse.Item> findDocumentItems(
            long documentId,
            Boolean returnDocument) {
        return jdbc.query("""
                SELECT m.RECNO, m.NUM_PREDMT, m.NAME_PREDM, m.ID_SCLAD,
                       m.KOLTREB_PR, m.KOLC_PREDM, m.CENA_PREDM, m.SUM_PREDM,
                       m.VALUT_CENA, m.SUM_VALUT, m.COD_VALUT, m.SUM_ROZN,
                       m.STND_UCHET, m.VOZVRAT_PR, m.PARTIA, m.SROK,
                       (SELECT MIN(a.NAME_ARTIC)
                          FROM dbo.SCL_ARTC a WITH (NOLOCK)
                         WHERE a.COD_ARTIC = m.NAME_PREDM
                           AND a.ID_SCLAD = m.ID_SCLAD) AS CURRENT_NAME
                FROM dbo.SCL_MOVE m WITH (NOLOCK)
                WHERE m.UNICUM_NUM = ?
                ORDER BY m.NUM_PREDMT, m.RECNO
                """, (rs, rowNum) -> {
            String sku = trimToNull(rs.getString("NAME_PREDM"));
            BigDecimal quantity = rs.getBigDecimal("KOLC_PREDM");
            Boolean returnLine = nullableBoolean(rs.getObject("VOZVRAT_PR"));
            boolean repeatable = sku != null
                    && quantity != null
                    && quantity.signum() > 0
                    && !Boolean.TRUE.equals(returnDocument)
                    && !Boolean.TRUE.equals(returnLine);
            return new FolioCustomerDocumentDetailResponse.Item(
                    rs.getLong("RECNO"),
                    rs.getInt("NUM_PREDMT"),
                    sku,
                    trimToNull(rs.getString("CURRENT_NAME")),
                    nullableInteger(rs.getObject("ID_SCLAD")),
                    rs.getBigDecimal("KOLTREB_PR"),
                    quantity,
                    moneyNullable(rs.getBigDecimal("CENA_PREDM")),
                    moneyNullable(rs.getBigDecimal("SUM_PREDM")),
                    moneyNullable(rs.getBigDecimal("VALUT_CENA")),
                    moneyNullable(rs.getBigDecimal("SUM_VALUT")),
                    trimToNull(rs.getString("COD_VALUT")),
                    moneyNullable(rs.getBigDecimal("SUM_ROZN")),
                    nullableBoolean(rs.getObject("STND_UCHET")),
                    returnLine,
                    trimToNull(rs.getString("PARTIA")),
                    toLocalDateTime(rs.getTimestamp("SROK")),
                    repeatable
            );
        }, documentId);
    }

    private List<FolioCustomerDocumentDetailResponse.LinkedPayment> findLinkedPayments(long documentId) {
        return jdbc.query("""
                SELECT p.UNICUM_PLT, p.N_PLAT_POR, p.DATE_P_POR, p.SUM_POR,
                       p.NOT_NAL, p.TYPE_POR, SUM(ISNULL(a.SUM_PREDM, 0)) AS ALLOCATED_AMOUNT
                FROM dbo.SCL_PMOV a WITH (NOLOCK)
                JOIN dbo.SCL_PLAT p WITH (NOLOCK) ON p.UNICUM_PLT = a.UNICUM_PLT
                WHERE a.UNICUM_NUM = ?
                GROUP BY p.UNICUM_PLT, p.N_PLAT_POR, p.DATE_P_POR,
                         p.SUM_POR, p.NOT_NAL, p.TYPE_POR
                ORDER BY p.DATE_P_POR, p.UNICUM_PLT
                """, (rs, rowNum) -> new FolioCustomerDocumentDetailResponse.LinkedPayment(
                rs.getLong("UNICUM_PLT"),
                formatFolioNumber(rs.getDouble("N_PLAT_POR")),
                toLocalDateTime(rs.getTimestamp("DATE_P_POR")),
                money(rs.getBigDecimal("SUM_POR")),
                money(rs.getBigDecimal("ALLOCATED_AMOUNT")),
                nullableBoolean(rs.getObject("NOT_NAL")),
                nullableBoolean(rs.getObject("TYPE_POR"))
        ), documentId);
    }

    private List<FolioCustomerDocumentDetailResponse.PaymentAllocation> findPaymentAllocations(long paymentId) {
        return jdbc.query("""
                SELECT a.RECNO, a.UNICUM_NUM, a.NAME_PREDM, a.ID_SCLAD,
                       a.KOLC_PREDM, a.CENA_PREDM, a.SUM_PREDM,
                       a.COD_VALUT, a.SUM_VALUT,
                       h.TYPE_DOC, h.N_PLAT_POR, h.DATE_P_POR,
                       (SELECT MIN(s.NAME_ARTIC)
                          FROM dbo.SCL_ARTC s WITH (NOLOCK)
                         WHERE s.COD_ARTIC = a.NAME_PREDM
                           AND s.ID_SCLAD = a.ID_SCLAD) AS CURRENT_NAME
                FROM dbo.SCL_PMOV a WITH (NOLOCK)
                LEFT JOIN dbo.SCL_NAKL h WITH (NOLOCK) ON h.UNICUM_NUM = a.UNICUM_NUM
                WHERE a.UNICUM_PLT = ?
                ORDER BY a.UNICUM_NUM, a.RECNO
                """, (rs, rowNum) -> new FolioCustomerDocumentDetailResponse.PaymentAllocation(
                rs.getLong("RECNO"),
                nullableLong(rs.getObject("UNICUM_NUM")),
                fromFolioType(trimToNull(rs.getString("TYPE_DOC"))),
                rs.getObject("N_PLAT_POR") == null ? null : formatFolioNumber(rs.getDouble("N_PLAT_POR")),
                toLocalDateTime(rs.getTimestamp("DATE_P_POR")),
                trimToNull(rs.getString("NAME_PREDM")),
                trimToNull(rs.getString("CURRENT_NAME")),
                nullableInteger(rs.getObject("ID_SCLAD")),
                rs.getBigDecimal("KOLC_PREDM"),
                moneyNullable(rs.getBigDecimal("CENA_PREDM")),
                moneyNullable(rs.getBigDecimal("SUM_PREDM")),
                trimToNull(rs.getString("COD_VALUT")),
                moneyNullable(rs.getBigDecimal("SUM_VALUT"))
        ), paymentId);
    }

    private Optional<FolioCustomerDocumentDetailResponse.DocumentRequisites> findDocumentRequisites(long documentId) {
        return jdbc.query("""
                SELECT L_TOWN_POR, DIRCT_POR, FINDIR_POR, L_TEL1_PLA, G_POL_POR
                FROM dbo.SCL_ADDN WITH (NOLOCK)
                WHERE UNICUM_NUM = ?
                """, (rs, rowNum) -> new FolioCustomerDocumentDetailResponse.DocumentRequisites(
                trimToNull(rs.getString("L_TOWN_POR")),
                trimToNull(rs.getString("DIRCT_POR")),
                trimToNull(rs.getString("FINDIR_POR")),
                trimToNull(rs.getString("L_TEL1_PLA")),
                trimToNull(rs.getString("G_POL_POR"))
        ), documentId).stream().findFirst();
    }

    private Optional<FolioCustomerDocumentDetailResponse.PaymentRequisites> findPaymentRequisites(long paymentId) {
        return jdbc.query("""
                SELECT PLATEL_POR, POLCH_POR, L_BANK_POR, C_BANK_POR, NAME_POR
                FROM dbo.SCL_ADDP WITH (NOLOCK)
                WHERE UNICUM_PLT = ?
                """, (rs, rowNum) -> new FolioCustomerDocumentDetailResponse.PaymentRequisites(
                trimToNull(rs.getString("PLATEL_POR")),
                trimToNull(rs.getString("POLCH_POR")),
                trimToNull(rs.getString("L_BANK_POR")),
                trimToNull(rs.getString("C_BANK_POR")),
                trimToNull(rs.getString("NAME_POR"))
        ), paymentId).stream().findFirst();
    }

    private static String stockDocumentSummarySql(FolioCustomerDocumentType type) {
        return """
                SELECT '%s' AS DOCUMENT_TYPE, %d AS TYPE_RANK,
                       n.UNICUM_NUM AS DOCUMENT_ID, n.N_PLAT_POR AS DOCUMENT_NUMBER,
                       n.DOPN_SCHET AS DOCUMENT_SUFFIX, n.DATE_P_POR AS DOCUMENT_DATE,
                       n.SUM_POR AS TOTAL_AMOUNT, n.SUM_VALUT AS CURRENCY_AMOUNT,
                       n.COD_VALUT AS CURRENCY_CODE, n.ID_SCLAD AS WAREHOUSE_ID,
                       n.STND_UCHET AS ACCOUNTED, n.NOT_NAL AS NON_CASH,
                       n.VOZVRAT_PR AS RETURN_DOCUMENT,
                       CAST(NULL AS bit) AS PAYMENT_DIRECTION_RAW,
                       n.VID_DOC AS OPERATION_KIND,
                       n.L_CP2_PLAT AS ADDITIONAL_INFO,
                       (SELECT COUNT(*) FROM dbo.SCL_MOVE m WITH (NOLOCK)
                         WHERE m.UNICUM_NUM = n.UNICUM_NUM) AS LINE_COUNT,
                       CAST(NULL AS float) AS ALLOCATED_AMOUNT
                FROM dbo.SCL_NAKL n WITH (NOLOCK)
                WHERE n.BRIEFORG = ?
                  AND n.TYPE_DOC = ?
                  AND n.DATE_P_POR >= ?
                  AND n.DATE_P_POR < ?
                """.formatted(type.name(), type.sortRank());
    }

    private static String paymentSummarySql() {
        return """
                SELECT 'PAYMENT' AS DOCUMENT_TYPE, 3 AS TYPE_RANK,
                       p.UNICUM_PLT AS DOCUMENT_ID, p.N_PLAT_POR AS DOCUMENT_NUMBER,
                       CAST(NULL AS varchar(5)) AS DOCUMENT_SUFFIX, p.DATE_P_POR AS DOCUMENT_DATE,
                       p.SUM_POR AS TOTAL_AMOUNT, p.SUMVAL_POR AS CURRENCY_AMOUNT,
                       p.COD_VALUT AS CURRENCY_CODE, p.ID_SCLAD AS WAREHOUSE_ID,
                       p.STND_UCHET AS ACCOUNTED, p.NOT_NAL AS NON_CASH,
                       CAST(NULL AS bit) AS RETURN_DOCUMENT,
                       p.TYPE_POR AS PAYMENT_DIRECTION_RAW,
                       p.VID_DOC AS OPERATION_KIND,
                       CAST(NULL AS varchar(30)) AS ADDITIONAL_INFO,
                       (SELECT COUNT(*) FROM dbo.SCL_PMOV a WITH (NOLOCK)
                         WHERE a.UNICUM_PLT = p.UNICUM_PLT) AS LINE_COUNT,
                       (SELECT SUM(ISNULL(a.SUM_PREDM, 0)) FROM dbo.SCL_PMOV a WITH (NOLOCK)
                         WHERE a.UNICUM_PLT = p.UNICUM_PLT) AS ALLOCATED_AMOUNT
                FROM dbo.SCL_PLAT p WITH (NOLOCK)
                WHERE p.ORG_PREDM = ?
                  AND p.DATE_P_POR >= ?
                  AND p.DATE_P_POR < ?
                """;
    }

    private static FolioCustomerDocumentType fromFolioType(String type) {
        if ("С".equals(type)) {
            return FolioCustomerDocumentType.ACCOUNT;
        }
        if ("Р".equals(type)) {
            return FolioCustomerDocumentType.EXPENSE;
        }
        return null;
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal moneyNullable(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String formatFolioNumber(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        String value = trimToNull(preferred);
        return value == null ? trimToNull(fallback) : value;
    }

    private static LocalDateTime toLocalDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private static Boolean nullableBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return ((Number) value).intValue() != 0;
    }

    private static Integer nullableInteger(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    public record PartnerRow(String shortName, String name) {
    }

    public record DocumentCursor(LocalDateTime documentDate, int typeRank, long documentId) {
    }

    private record StockHeader(
            long documentId,
            String documentNumber,
            String documentSuffix,
            LocalDateTime documentDate,
            BigDecimal totalAmount,
            BigDecimal currencyAmount,
            String currencyCode,
            Integer warehouseId,
            Boolean accounted,
            Boolean nonCash,
            Boolean returnDocument,
            String operationKind,
            String contractCode,
            String basis,
            String note,
            String payerName,
            String receiverName,
            String payerShortName,
            String folioUser,
            String sourceInfo,
            String additionalInfo,
            LocalDateTime createdAt,
            LocalDateTime correctedAt,
            String correctedBy
    ) {
    }

    private record PaymentHeader(
            long paymentId,
            String paymentNumber,
            LocalDateTime paymentDate,
            BigDecimal amount,
            BigDecimal currencyAmount,
            String currencyCode,
            Integer warehouseId,
            Boolean accounted,
            Boolean nonCash,
            Boolean paymentDirectionRaw,
            String operationKind,
            String contractCode,
            String basis,
            String note,
            String payerName,
            String partnerShortName,
            String folioUser,
            String sourceInfo,
            LocalDateTime createdAt,
            LocalDateTime correctedAt,
            String correctedBy,
            BigDecimal unallocatedAmount,
            BigDecimal unallocatedCurrencyAmount
    ) {
    }
}
