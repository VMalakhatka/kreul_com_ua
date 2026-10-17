package org.example.proect.lavka.folio.payment;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static org.example.proect.lavka.folio.payment.WfpPaymentException.require;

@Repository
public class WfpPaymentDao {
    private final JdbcTemplate jdbc;
    public WfpPaymentDao(@Qualifier("folioJdbcTemplate") JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Snapshot(String payerName, Integer salesTax, Integer marginTax, String ownOrganization, String revision) {}
    private static final Map<String,String> PROCEDURES = Map.of(
        "INSERT_PLAT", "c6d7e3f18b4fb1e5e7eca6c4af8cd7fadb5f28358880185a3d8218e490740ec8",
        "i_ins_plat", "f6a6080061dc0f306ce1d462cecb9f1c2c6bd2555918abe8673f3e4a7b283374",
        "I_GET_LAST_PLAT_NUM", "04bac678f18a1a592039d7ba5631e567c1f43fc74075a2bfccfff756ac76b81a",
        "I_TEST_NUM_PLAT", "83697807b42d0edd354202db8c04d379b59c8262bc59419a9acadf70e1b5675a");

    public void environment(String expected) {
        require(expected.equals(jdbc.queryForObject("SELECT DB_NAME()", String.class)), "wrong_payment_database");
    }
    public void transactionGuard() {
        require(Integer.valueOf(1).equals(jdbc.queryForObject("SELECT @@TRANCOUNT", Integer.class)), "payment_transaction_boundary");
    }
    public void verifyProcedures() {
        PROCEDURES.forEach((name, hash) -> {
            var fragments = jdbc.queryForList("""
                SELECT c.text FROM dbo.syscomments c JOIN dbo.sysobjects o ON o.id=c.id
                WHERE o.name=? AND USER_NAME(o.uid)='dbo' AND c.encrypted=0 ORDER BY c.number,c.colid
                """, String.class, name);
            require(!fragments.isEmpty() && fragments.stream().noneMatch(Objects::isNull)
                    && hash.equals(WfpPaymentCommand.hash(String.join("", fragments).replace("\r\n", "\n").replace("\r", "\n"))),
                    "native_payment_procedure_changed");
        });
    }
    public Snapshot snapshot(WfpPaymentCommand c, long template, boolean lock) {
        String hint = lock ? " WITH (UPDLOCK,HOLDLOCK) " : " ";
        var payers = jdbc.queryForList("SELECT NAME_USER FROM dbo._PARTNER" + hint + "WHERE N_USER=?", c.payerKey());
        require(payers.size() == 1, "payment_payer_missing");
        String name = trim(payers.get(0).get("NAME_USER"));
        require(name != null && !name.isBlank() && name.length() <= 70, "payment_payer_name_invalid");
        var templates = jdbc.queryForList("""
            SELECT IS_NALPROD,NDS_TORGN,MY_ORG FROM dbo.SCL_PLAT
            """ + hint + """
            WHERE UNICUM_PLT=? AND TYPE_POR=1 AND NOT_NAL=1 AND ID_SCLAD=5
             AND STND_UCHET=1 AND UCHET_MOVE=1 AND VALUTROUBL=1
             AND CODCEL_POR='МАЛАФОП' AND VID_DOC='РЕАЛИЗАЦИЯ'
             AND ISNULL(NALOG_POR,0)=0 AND ISNULL(NALOGPRCNT,0)=0 AND ISNULL(NALG2PRCNT,0)=0
             AND ISNULL(SUMVAL_POR,0)=0 AND ISNULL(NALOGVALUT,0)=0
            """, template);
        require(templates.size() == 1, "bank_template_required");
        var t = templates.get(0);
        Integer sales = integer(t.get("IS_NALPROD")), margin = integer(t.get("NDS_TORGN"));
        String org = trim(t.get("MY_ORG"));
        require(count("SELECT COUNT(*) FROM dbo.SCLAD_R WHERE ID_SCLAD=5") == 1
                && count("SELECT COUNT(*) FROM dbo.VID_OPER WHERE SIGNIFIC='РЕАЛИЗАЦИЯ'") == 1, "payment_reference_missing");
        // The request identity and an approved bank template, not an invoice, define the payment.
        String revision = WfpPaymentCommand.hash(c.requestHash() + "|" + template + "|" + name.length() + ":" + name
                + "|" + sales + "|" + margin + "|" + org);
        return new Snapshot(name, sales, margin, org, revision);
    }
    public Map<String,Object> lookup(String key, String scope, boolean lock) {
        var rows = jdbc.queryForList("SELECT * FROM dbo.LAVKA_WFP_PAYMENTS"
                + (lock ? " WITH (UPDLOCK,HOLDLOCK) " : " ") + "WHERE OPERATION_KEY=? AND SOURCE_SCOPE=?", key, scope);
        if (rows.isEmpty()) return null;
        var row = rows.get(0);
        long id = ((Number) row.get("PAYMENT_ID")).longValue();
        long cents = ((Number) row.get("AMOUNT_CENTS")).longValue();
        // Distribution/NOTOVAROST may legitimately change later in Folio; never recreate the receipt.
        boolean intact = count("""
            SELECT COUNT(*) FROM dbo.SCL_PLAT WHERE UNICUM_PLT=? AND SUM_POR=? AND ORG_PREDM=?
             AND ID_SCLAD=5 AND TYPE_POR=1 AND NOT_NAL=1 AND STND_UCHET=1 AND UCHET_MOVE=1
             AND CODCEL_POR='МАЛАФОП' AND VID_DOC='РЕАЛИЗАЦИЯ' AND DOCUMN_POR=?
            """, id, BigDecimal.valueOf(cents, 2), row.get("PAYER_KEY"), "WFP " + trim(row.get("REFERENCE"))) == 1;
        return result(intact ? "SUCCEEDED" : "REVIEW", key, trim(row.get("REQUEST_HASH")), cents, id,
                ((Number) row.get("PAYMENT_NUMBER")).longValue());
    }
    public Map<String,Object> insertNative(WfpPaymentCommand c, Snapshot source) {
        // Isolated scratch key; empty by design because an incoming payment is NOT an invoice allocation.
        int scratch = -(Integer.parseInt(c.operationKey().substring(0, 7), 16) + 1);
        require(count("SELECT COUNT(*) FROM dbo.TMP_MOVE WITH (UPDLOCK,HOLDLOCK) WHERE UNICUM_NUM=?", scratch) == 0,
                "payment_scratch_collision");
        require(count("SELECT COUNT(*) FROM dbo.SCL_PLAT WITH (UPDLOCK,HOLDLOCK) WHERE CHARINDEX(?,DOCUMN_POR)>0 OR CHARINDEX(?,OSNOVANIE)>0",
                c.reference(), c.reference()) == 0, "existing_payment_requires_review");
        BigDecimal amount = BigDecimal.valueOf(c.amountCents(), 2);
        String date = Instant.parse(c.confirmedAt()).atZone(ZoneId.of("Europe/Kyiv")).toLocalDate()
                .format(DateTimeFormatter.ofPattern("dd.MM.uuuu"));
        long[] nativeResult = jdbc.execute((ConnectionCallback<long[]>) connection -> {
            try (PreparedStatement p = connection.prepareStatement(NATIVE_SQL)) {
                p.setQueryTimeout(25);
                p.setString(1, date); p.setBigDecimal(2, amount); p.setString(3, "WFP " + c.reference());
                p.setString(4, "Woo " + c.orderId() + "; SHA256:" + c.requestHash());
                p.setString(5, source.payerName()); p.setString(6, c.payerKey()); p.setInt(7, scratch);
                p.setObject(8, source.salesTax(), Types.SMALLINT); p.setObject(9, source.marginTax(), Types.SMALLINT);
                p.setObject(10, source.ownOrganization(), Types.VARCHAR);
                long[] found = null;
                boolean rows = p.execute();
                while (true) {
                    if (rows) try (ResultSet rs = p.getResultSet()) {
                        while (rs.next()) {
                            require(found == null, "unexpected_native_result");
                            found = new long[]{rs.getLong("payment_id"), rs.getLong("payment_number")};
                        }
                    }
                    else if (p.getUpdateCount() == -1) break;
                    rows = p.getMoreResults();
                }
                require(found != null && found[0] > 0 && found[1] > 0, "native_payment_not_confirmed");
                return found;
            }
        });
        require(count("""
            SELECT COUNT(*) FROM dbo.SCL_PLAT WHERE UNICUM_PLT=? AND SUM_POR=? AND NOTOVAROST=?
             AND ORG_PREDM=? AND L_NAME_POR=? AND ID_SCLAD=5 AND TYPE_POR=1 AND NOT_NAL=1
             AND STND_UCHET=1 AND UCHET_MOVE=1 AND VALUTROUBL=1
             AND CODCEL_POR='МАЛАФОП' AND VID_DOC='РЕАЛИЗАЦИЯ' AND DOCUMN_POR=?
             AND N_PLAT_POR=? AND NACLAD_POR=0 AND DATE_P_POR=?
            """, nativeResult[0], amount, amount, c.payerKey(), source.payerName(), "WFP " + c.reference(),
                nativeResult[1], Instant.parse(c.confirmedAt()).atZone(ZoneId.of("Europe/Kyiv")).toLocalDate()
                        .format(DateTimeFormatter.BASIC_ISO_DATE)) == 1,
                "payment_header_postcondition");
        require(count("SELECT COUNT(*) FROM dbo.SCL_PMOV WHERE UNICUM_PLT=?", nativeResult[0]) == 0
                && count("SELECT COUNT(*) FROM dbo.TMP_MOVE WHERE UNICUM_NUM=?", scratch) == 0, "payment_allocation_postcondition");
        return result("SUCCEEDED", c.operationKey(), c.requestHash(), c.amountCents(), nativeResult[0], nativeResult[1]);
    }
    public void remember(WfpPaymentCommand c, Map<String,Object> result) {
        int inserted = jdbc.update("""
            INSERT dbo.LAVKA_WFP_PAYMENTS(OPERATION_KEY,REQUEST_HASH,SOURCE_SCOPE,MERCHANT_HASH,REFERENCE,
             ORDER_ID,AMOUNT_CENTS,PAYER_KEY,CONFIRMED_AT,PAYMENT_ID,PAYMENT_NUMBER,CREATED_AT)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,GETUTCDATE())
            """, c.operationKey(), c.requestHash(), c.sourceScope(), c.merchantHash(), c.reference(), c.orderId(),
                c.amountCents(), c.payerKey(), c.confirmedAt(), result.get("paymentId"), result.get("paymentNumber"));
        require(inserted == 1, "payment_ledger_postcondition");
    }
    private int count(String sql, Object... args) { return Objects.requireNonNull(jdbc.queryForObject(sql, Integer.class, args)); }
    private static Integer integer(Object o) {
        if (o == null) return null;
        return o instanceof Boolean b ? (b ? 1 : 0) : ((Number) o).intValue();
    }
    private static String trim(Object o) { return o == null ? null : o.toString().stripTrailing(); }
    private static Map<String,Object> result(String status, String key, String hash, long amount, long id, long number) {
        return Map.of("status",status,"operationKey",key,"requestHash",hash,"amountCents",amount,
                "paymentId",id,"paymentNumber",number,"paymentWarehouse",5);
    }
    static final String NATIVE_SQL = """
        DECLARE @ret int,@payment int,@number int,@name varchar(20),@qty float,@required float,@position smallint,
         @date char(10),@amount float,@note varchar(100),@basis varchar(136),@payername varchar(70),@payer varchar(8),
         @scratch int,@sales smallint,@margin smallint,@myorg varchar(8),@tx int
        SELECT @date=?,@amount=?,@note=?,@basis=?,@payername=?,@payer=?,@scratch=?,@sales=?,@margin=?,@myorg=?,
         @number=NULL,@payment=NULL,@tx=@@TRANCOUNT
        IF @tx<>1 BEGIN RAISERROR('Payment transaction boundary',16,1) RETURN END
        EXEC @ret=dbo.INSERT_PLAT
         @skip_test=0,@sclad=5,@date_p_por=@date,@sum_por=@amount,
         @nalog1_por=NULL,@prcnt_por=0,@stnd_uchet=1,@codcel_por='МАЛАФОП',
         @documn_por=@note,@prilog_por=NULL,@osnovanie=@basis,@n_sf_por=NULL,@buhschetpl=NULL,
         @naclad_por=0,@uchet_move=1,@contr_por=NULL,@not_nal=1,@valutroubl=1,@cod_valut=NULL,
         @sumval_por=0,@nalog_por=0,@nalogprcnt=0,@nalg2prcnt=0,@type_por=1,@l_name_por=@payername,
         @nalog2_por=NULL,@otmetka=0,@vid_doc='РЕАЛИЗАЦИЯ',@org_predm=@payer,@notovarost=@amount,
         @notovarval=0,@nalogvalut=0,@unicum_tmp=@scratch,@corr_unicum=NULL,
         @unicum_plt=@payment OUTPUT,@n_plat_por=@number OUTPUT,@name_predm=@name OUTPUT,
         @kolc_predm=@qty OUTPUT,@koltreb_pr=@required OUTPUT,@num_predmt=@position OUTPUT,
         @family='WFP',@is_nalprod=@sales,@nds_torgn=@margin,@my_org=@myorg,@ist_inf=NULL,@spec_act=1
        IF @ret IS NULL OR @ret<>0 OR @payment IS NULL OR @number IS NULL OR @@TRANCOUNT<>@tx
         BEGIN RAISERROR('Native payment not confirmed',16,1) RETURN END
        SELECT @payment AS payment_id,@number AS payment_number
        """;
}
