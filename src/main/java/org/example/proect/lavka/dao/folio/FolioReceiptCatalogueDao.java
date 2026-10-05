package org.example.proect.lavka.dao.folio;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

/** Read-only arrival picker. Deliberately excludes costs, supplier and payment data. */
@Repository
public class FolioReceiptCatalogueDao {
    private final JdbcTemplate jdbc;
    public FolioReceiptCatalogueDao(@Qualifier("folioJdbcTemplate") JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Document(long id, String number, LocalDate date, int warehouseId) {}
    public List<Document> documents(int warehouse, LocalDate date, long afterId) {
        return jdbc.query("""
                SELECT TOP 101 n.UNICUM_NUM,n.N_PLAT_POR,n.DOPN_SCHET,n.DATE_P_POR,n.ID_SCLAD
                  FROM dbo.SCL_NAKL n
                 WHERE n.TYPE_DOC=? AND n.STND_UCHET=1 AND ISNULL(n.VOZVRAT_PR,0)=0
                   AND n.ID_SCLAD=? AND n.DATE_P_POR>=? AND n.DATE_P_POR<? AND n.UNICUM_NUM>?
                 ORDER BY n.UNICUM_NUM
                """, (rs, i) -> new Document(rs.getLong("UNICUM_NUM"),
                rs.getBigDecimal("N_PLAT_POR").stripTrailingZeros().toPlainString()
                        + trim(rs.getString("DOPN_SCHET")), rs.getTimestamp("DATE_P_POR").toLocalDateTime().toLocalDate(), rs.getInt("ID_SCLAD")),
                "\u041f", warehouse, Timestamp.valueOf(date.atStartOfDay()), Timestamp.valueOf(date.plusDays(1).atStartOfDay()), afterId);
    }
    public List<String> skus(long id, int warehouse, LocalDate date) {
        // One joined read also checks the selected document date/warehouse and active accounted state.
        return jdbc.query("""
                SELECT DISTINCT TOP 2001 LTRIM(RTRIM(m.NAME_PREDM)) AS SKU
                  FROM dbo.SCL_MOVE m JOIN dbo.SCL_NAKL n ON n.UNICUM_NUM=m.UNICUM_NUM
                 WHERE n.UNICUM_NUM=? AND n.TYPE_DOC=? AND n.STND_UCHET=1 AND ISNULL(n.VOZVRAT_PR,0)=0
                   AND n.ID_SCLAD=? AND n.DATE_P_POR>=? AND n.DATE_P_POR<?
                   AND m.ID_SCLAD=? AND m.STND_UCHET=1 AND ISNULL(m.VOZVRAT_PR,0)=0
                   AND m.KOLC_PREDM>0 AND LTRIM(RTRIM(m.NAME_PREDM))<>''
                 ORDER BY SKU
                """, (rs, i) -> rs.getString("SKU"), id, "\u041f", warehouse,
                Timestamp.valueOf(date.atStartOfDay()), Timestamp.valueOf(date.plusDays(1).atStartOfDay()), warehouse);
    }
    private static String trim(String s) { return s == null ? "" : s.trim(); }
}
