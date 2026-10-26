package org.example.proect.lavka.dao.folio;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/** Manager-only values in base UAH. Missing line values are never treated as zero. */
@Repository
public class FolioDocumentValuesDao {
    private final JdbcTemplate jdbc;
    public FolioDocumentValuesDao(@Qualifier("folioJdbcTemplate") JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<Values> read(String partner, String type, String folioType, long id) {
        return jdbc.query("""
            SELECT n.UNICUM_NUM,n.N_PLAT_POR,n.DOPN_SCHET,n.DATE_P_POR,n.ID_SCLAD,n.SUM_POR,
                   m.LINE_COUNT,m.AMOUNT_COUNT,m.COST_COUNT,m.SHIPMENT_AMOUNT,m.ACCOUNTING_AMOUNT
            FROM dbo.SCL_NAKL n WITH (NOLOCK)
            LEFT JOIN (SELECT UNICUM_NUM,COUNT(*) AS LINE_COUNT,COUNT(SUM_PREDM) AS AMOUNT_COUNT,
                         COUNT(SUM_UCHET) AS COST_COUNT,SUM(SUM_PREDM) AS SHIPMENT_AMOUNT,
                         SUM(SUM_UCHET) AS ACCOUNTING_AMOUNT
                       FROM dbo.SCL_MOVE WITH (NOLOCK) WHERE UNICUM_NUM=? GROUP BY UNICUM_NUM) m
              ON m.UNICUM_NUM=n.UNICUM_NUM
            WHERE n.UNICUM_NUM=? AND n.BRIEFORG=? AND n.TYPE_DOC=? AND ISNULL(n.VOZVRAT_PR,0)=0
            """,(rs,row)-> {
                int lines=rs.getInt("LINE_COUNT"), amounts=rs.getInt("AMOUNT_COUNT"), costs=rs.getInt("COST_COUNT");
                return new Values(type,rs.getLong("UNICUM_NUM"),partner,
                    rs.getBigDecimal("N_PLAT_POR").stripTrailingZeros().toPlainString(),
                    rs.getString("DOPN_SCHET")==null?"":rs.getString("DOPN_SCHET").trim(),
                    rs.getTimestamp("DATE_P_POR").toLocalDateTime().toLocalDate(),
                    rs.getObject("ID_SCLAD")==null?null:rs.getInt("ID_SCLAD"),money(rs.getBigDecimal("SUM_POR")),
                    lines>0 && amounts==lines?money(rs.getBigDecimal("SHIPMENT_AMOUNT")):null,
                    lines>0 && costs==lines?money(rs.getBigDecimal("ACCOUNTING_AMOUNT")):null,
                    lines,lines-amounts,lines-costs,"UAH","ACTIVE_LEDGER");
            },id,id,partner,folioType);
    }
    private static BigDecimal money(BigDecimal value) { return value==null?null:value.setScale(2,RoundingMode.HALF_UP); }
    public record Values(String documentType,long documentId,String partnerShortName,String documentNumber,
        String documentNumberSuffix,LocalDate documentDate,Integer warehouseId,BigDecimal documentAmount,
        BigDecimal shipmentAmount,BigDecimal accountingAmount,int lineCount,int missingAmountLines,
        int missingAccountingLines,String currency,String source) {}
}
