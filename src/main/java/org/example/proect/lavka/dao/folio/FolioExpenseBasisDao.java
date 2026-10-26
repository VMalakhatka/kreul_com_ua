package org.example.proect.lavka.dao.folio;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;

/** Exact, bounded header reads only; archived accounts are evidence, never restored. */
@Repository
public class FolioExpenseBasisDao {
    private final JdbcTemplate jdbc;
    public FolioExpenseBasisDao(@Qualifier("folioJdbcTemplate") JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<String> expenseBasis(String partner, long id) {
        return jdbc.query("SELECT OSNOVANIE FROM dbo.SCL_NAKL WITH (NOLOCK) WHERE UNICUM_NUM=? AND BRIEFORG=? AND TYPE_DOC=? AND ISNULL(VOZVRAT_PR,0)=0",
                (rs,n)->rs.getString(1),id,partner,"Р");
    }
    public List<Header> accounts(String partner, BigDecimal number, String suffix, LocalDate date, boolean archive) {
        String table=archive ? "SCL_ARCN" : "SCL_NAKL";
        return jdbc.query("SELECT TOP 2 UNICUM_NUM,N_PLAT_POR,DOPN_SCHET,DATE_P_POR,ID_SCLAD,BRIEFORG FROM dbo."+table+
                " WITH (NOLOCK) WHERE TYPE_DOC=? AND BRIEFORG=? AND N_PLAT_POR=? AND LTRIM(RTRIM(ISNULL(DOPN_SCHET,'')))=? AND DATE_P_POR>=? AND DATE_P_POR<? AND ISNULL(VOZVRAT_PR,0)=0 ORDER BY UNICUM_NUM",
                (rs,n)->new Header("ACCOUNT",rs.getLong("UNICUM_NUM"),rs.getBigDecimal("N_PLAT_POR").stripTrailingZeros().toPlainString(),
                        rs.getString("DOPN_SCHET")==null ? "" : rs.getString("DOPN_SCHET").trim(),rs.getTimestamp("DATE_P_POR").toLocalDateTime().toLocalDate(),
                        rs.getObject("ID_SCLAD")==null ? null : rs.getInt("ID_SCLAD"),rs.getString("BRIEFORG").trim(),archive ? "ARCHIVE_LEDGER" : "ACTIVE_LEDGER"),
                "С",partner,number,suffix,Timestamp.valueOf(date.atStartOfDay()),Timestamp.valueOf(date.plusDays(1).atStartOfDay()));
    }
    public record Header(String documentType,long documentId,String documentNumber,String documentNumberSuffix,
                         LocalDate documentDate,Integer warehouseId,String partnerShortName,String source) {}
}
