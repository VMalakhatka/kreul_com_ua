package org.example.proect.lavka.dao.folio;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

/** Read-only document product picker. Never returns prices, amounts or counterparties. */
@Repository
public class FolioReceiptCatalogueDao {
    private static final String RECEIPT="\u041f", INVOICE="\u0421";
    private final JdbcTemplate jdbc;
    public FolioReceiptCatalogueDao(@Qualifier("folioJdbcTemplate") JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Document(long id, String number, LocalDate date, int warehouseId, String type, boolean accounted) {}
    private static List<String> types(String selection) {
        return switch(selection) {
            case "receipt" -> List.of(RECEIPT,RECEIPT);
            case "invoice" -> List.of(INVOICE,INVOICE);
            case "all" -> List.of(RECEIPT,INVOICE);
            default -> throw new IllegalArgumentException("Invalid document type");
        };
    }
    public List<Document> documents(int warehouse, LocalDate date, long afterId, String selection) {
        var types=types(selection);
        return jdbc.query("""
                SELECT TOP 101 n.UNICUM_NUM,n.N_PLAT_POR,n.DOPN_SCHET,n.DATE_P_POR,n.ID_SCLAD,n.TYPE_DOC,n.STND_UCHET
                  FROM dbo.SCL_NAKL n
                 WHERE n.TYPE_DOC IN (?,?) AND (n.TYPE_DOC=? OR n.STND_UCHET=1) AND ISNULL(n.VOZVRAT_PR,0)=0
                   AND n.ID_SCLAD=? AND n.DATE_P_POR>=? AND n.DATE_P_POR<? AND n.UNICUM_NUM>?
                 ORDER BY n.UNICUM_NUM
                """, (rs, i) -> new Document(rs.getLong("UNICUM_NUM"),
                rs.getBigDecimal("N_PLAT_POR").stripTrailingZeros().toPlainString()
                        + trim(rs.getString("DOPN_SCHET")), rs.getTimestamp("DATE_P_POR").toLocalDateTime().toLocalDate(), rs.getInt("ID_SCLAD"),
                INVOICE.equals(trim(rs.getString("TYPE_DOC")))?"invoice":"receipt", rs.getBoolean("STND_UCHET")),
                types.get(0),types.get(1),INVOICE,warehouse,Timestamp.valueOf(date.atStartOfDay()),Timestamp.valueOf(date.plusDays(1).atStartOfDay()),afterId);
    }
    public List<String> skus(long id, int warehouse, LocalDate date, String selection) {
        var types=types(selection);
        // Receipts require accounted headers and lines. Invoices only supply a product selection,
        // so a non-accounting promotional invoice must not reserve stock to be usable here.
        return jdbc.query("""
                SELECT DISTINCT TOP 2001 LTRIM(RTRIM(m.NAME_PREDM)) AS SKU
                  FROM dbo.SCL_MOVE m JOIN dbo.SCL_NAKL n ON n.UNICUM_NUM=m.UNICUM_NUM
                 WHERE n.UNICUM_NUM=? AND n.TYPE_DOC IN (?,?) AND (n.TYPE_DOC=? OR n.STND_UCHET=1) AND ISNULL(n.VOZVRAT_PR,0)=0
                   AND n.ID_SCLAD=? AND n.DATE_P_POR>=? AND n.DATE_P_POR<?
                   AND m.ID_SCLAD=? AND (n.TYPE_DOC=? OR m.STND_UCHET=1) AND ISNULL(m.VOZVRAT_PR,0)=0
                   AND m.KOLC_PREDM>0 AND LTRIM(RTRIM(m.NAME_PREDM))<>''
                 ORDER BY SKU
                """, (rs, i) -> rs.getString("SKU"),id,types.get(0),types.get(1),INVOICE,warehouse,
                Timestamp.valueOf(date.atStartOfDay()),Timestamp.valueOf(date.plusDays(1).atStartOfDay()),warehouse,INVOICE);
    }
    private static String trim(String s) { return s == null ? "" : s.trim(); }
}
