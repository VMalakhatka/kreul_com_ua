package org.example.proect.lavka.dao.folio;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

@Repository
public class FolioProfitReportDao {

    private final JdbcTemplate jdbc;

    public FolioProfitReportDao(@Qualifier("folioJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PaymentRow> findPaymentCandidates(
            LocalDate monthStart,
            LocalDate nextMonthStart,
            String explicitPeriodMarker) {
        return jdbc.query("""
                SELECT p.UNICUM_PLT, p.N_PLAT_POR, p.DATE_P_POR, p.SUM_POR,
                       p.NOT_NAL, p.ID_SCLAD, p.CODCEL_POR, p.ORG_PREDM,
                       p.L_NAME_POR, p.VID_DOC, p.DOCUMN_POR
                  FROM dbo.SCL_PLAT p WITH (NOLOCK)
                 WHERE p.TYPE_POR = 0
                   AND ((p.DATE_P_POR >= ? AND p.DATE_P_POR < ?)
                        OR p.DOCUMN_POR LIKE ?)
                 ORDER BY p.DATE_P_POR, p.N_PLAT_POR, p.UNICUM_PLT
                """, (rs, rowNum) -> mapPayment(rs),
                Timestamp.valueOf(monthStart.atStartOfDay()),
                Timestamp.valueOf(nextMonthStart.atStartOfDay()),
                "%" + explicitPeriodMarker + "%");
    }

    public List<GrossMarginRow> findGrossMargins(LocalDate monthStart, LocalDate nextMonthStart) {
        return jdbc.query("""
                SELECT m.ID_SCLAD,
                       ISNULL(o.MY_ORGANIZ, '') AS ORG_TYPE,
                       n.VOZVRAT_PR,
                       m.STND_UCHET,
                       COUNT(*) AS LINE_COUNT,
                       SUM(ISNULL(m.SUM_PREDM, 0) - ISNULL(m.SUM_UCHET, 0)) AS GROSS_MARGIN
                  FROM dbo.SCL_NAKL n WITH (NOLOCK)
                  JOIN dbo.SCL_MOVE m WITH (NOLOCK) ON m.UNICUM_NUM = n.UNICUM_NUM
                  LEFT JOIN dbo._PARTNER o WITH (NOLOCK) ON o.N_USER = m.ORG_PREDM
                 WHERE n.DATE_P_POR >= ?
                   AND n.DATE_P_POR < ?
                   AND n.TYPE_DOC = ?
                 GROUP BY m.ID_SCLAD, ISNULL(o.MY_ORGANIZ, ''), n.VOZVRAT_PR, m.STND_UCHET
                """, (rs, rowNum) -> new GrossMarginRow(
                        rs.getInt("ID_SCLAD"),
                        trim(rs.getString("ORG_TYPE")),
                        rs.getBoolean("VOZVRAT_PR"),
                        rs.getBoolean("STND_UCHET"),
                        rs.getInt("LINE_COUNT"),
                        decimal(rs, "GROSS_MARGIN")
                ),
                Timestamp.valueOf(monthStart.atStartOfDay()),
                Timestamp.valueOf(nextMonthStart.atStartOfDay()),
                "Р");
    }

    private static PaymentRow mapPayment(ResultSet rs) throws SQLException {
        return new PaymentRow(
                rs.getLong("UNICUM_PLT"),
                folioNumber(rs.getBigDecimal("N_PLAT_POR")),
                rs.getTimestamp("DATE_P_POR").toLocalDateTime().toLocalDate(),
                decimal(rs, "SUM_POR"),
                rs.getBoolean("NOT_NAL"),
                nullableInteger(rs, "ID_SCLAD"),
                trim(rs.getString("CODCEL_POR")),
                trim(rs.getString("ORG_PREDM")),
                trim(rs.getString("L_NAME_POR")),
                trim(rs.getString("VID_DOC")),
                trim(rs.getString("DOCUMN_POR"))
        );
    }

    private static BigDecimal decimal(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? BigDecimal.ZERO : value;
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static String folioNumber(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String result = value.trim();
        return result.isEmpty() ? null : result;
    }

    public record PaymentRow(
            long paymentId,
            String documentNumber,
            LocalDate documentDate,
            BigDecimal amount,
            boolean bank,
            Integer warehouseId,
            String purposeCode,
            String expenseCode,
            String name,
            String documentClass,
            String note
    ) {
    }

    public record GrossMarginRow(
            int warehouseId,
            String organizationType,
            boolean returnDocument,
            boolean accounted,
            int lineCount,
            BigDecimal grossMargin
    ) {
    }
}
