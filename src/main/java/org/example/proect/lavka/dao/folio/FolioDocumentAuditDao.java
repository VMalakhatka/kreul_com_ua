package org.example.proect.lavka.dao.folio;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.ArgumentPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

@Repository
public class FolioDocumentAuditDao {
    private final JdbcTemplate jdbc;
    public FolioDocumentAuditDao(@Qualifier("folioJdbcTemplate") JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long upperId(LocalDate from, LocalDate until) {
        return query("""
                SELECT ISNULL(MAX(p.UNICUM_PLT), 0) AS UPPER_ID
                FROM dbo.SCL_PLAT p WITH (NOLOCK)
                WHERE p.DATE_P_POR >= ? AND p.DATE_P_POR < ?
                """, (rs, i) -> rs.getLong("UPPER_ID"), timestamp(from), timestamp(until)).get(0);
    }

    public long count(LocalDate from, LocalDate until, long upperId) {
        return query("""
                SELECT COUNT(*) AS DOCUMENT_COUNT
                FROM dbo.SCL_PLAT p WITH (NOLOCK)
                WHERE p.DATE_P_POR >= ? AND p.DATE_P_POR < ? AND p.UNICUM_PLT <= ?
                """, (rs, i) -> rs.getLong("DOCUMENT_COUNT"), timestamp(from), timestamp(until), upperId).get(0);
    }

    public List<Row> page(LocalDate from, LocalDate until, long afterId, long upperId, int limit) {
        if (limit < 1 || limit > 501) throw new IllegalArgumentException("Page limit out of range");
        // Only validated integer TOP is interpolated: SQL Server 2000 has no OFFSET/FETCH.
        return query("""
                SELECT TOP %d p.UNICUM_PLT, p.N_PLAT_POR, p.DATE_P_POR,
                       p.ID_SCLAD, p.NOT_NAL, p.TYPE_POR, p.SUM_POR, p.COD_VALUT,
                       p.ORG_PREDM, p.L_NAME_POR, p.CODCEL_POR, p.VID_DOC, p.IST_INF, p.DOCUMN_POR
                FROM dbo.SCL_PLAT p WITH (NOLOCK)
                WHERE p.DATE_P_POR >= ? AND p.DATE_P_POR < ?
                  AND p.UNICUM_PLT > ? AND p.UNICUM_PLT <= ?
                ORDER BY p.UNICUM_PLT
                """.formatted(limit), (rs, i) -> {
                    BigDecimal number = rs.getBigDecimal("N_PLAT_POR");
                    return new Row(rs.getLong("UNICUM_PLT"), number == null ? null : number.stripTrailingZeros().toPlainString(),
                            rs.getTimestamp("DATE_P_POR").toLocalDateTime().toLocalDate(),
                            rs.getObject("ID_SCLAD") == null ? null : rs.getInt("ID_SCLAD"),
                            rs.getObject("NOT_NAL") == null ? null : rs.getBoolean("NOT_NAL"),
                            rs.getObject("TYPE_POR") == null ? null : rs.getBoolean("TYPE_POR"),
                            rs.getBigDecimal("SUM_POR"), rs.getString("COD_VALUT"), rs.getString("ORG_PREDM"),
                            rs.getString("L_NAME_POR"), rs.getString("CODCEL_POR"), rs.getString("VID_DOC"), rs.getString("IST_INF"), rs.getString("DOCUMN_POR"));
                }, timestamp(from), timestamp(until), afterId, upperId);
    }

    private <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        FolioProfitReadBudget.remainingQuerySeconds();
        return jdbc.query(sql, statement -> {
            statement.setQueryTimeout(FolioProfitReadBudget.remainingQuerySeconds());
            new ArgumentPreparedStatementSetter(args).setValues(statement);
        }, mapper);
    }
    private static Timestamp timestamp(LocalDate date) { return Timestamp.valueOf(date.atStartOfDay()); }

    public record Row(long paymentId, String documentNumber, LocalDate documentDate, Integer warehouseId,
            Boolean bank, Boolean incoming, BigDecimal amount, String currencyCode, String organizationCode, String organizationName,
            String purposeCode, String operationType, String sourceInfo, String note) {}
}
