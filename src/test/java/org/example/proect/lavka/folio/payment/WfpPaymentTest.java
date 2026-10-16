package org.example.proect.lavka.folio.payment;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WfpPaymentTest {
    private static final String MERCHANT = "a".repeat(64), REVISION = "b".repeat(64);
    private WfpPaymentProperties config() {
        var p = new WfpPaymentProperties(); p.setApiEnabled(true); p.setApplyEnabled(true);
        p.setToken("offline-test-not-a-real-token-12345678"); p.setExpectedDatabase("Paint_Rus");
        p.setSourceScope("fixture"); p.setMerchantHash(MERCHANT); p.setStartAt("2019-01-01T00:00:00Z"); p.setBankTemplateId(1);
        return p;
    }
    private WfpPaymentCommand command(String payer, long amount, String time, String revision) {
        return new WfpPaymentCommand(WfpPaymentCommand.key("fixture",MERCHANT,"42_woo_w4p_1792000000"),
                "fixture", MERCHANT, "42_woo_w4p_1792000000", 42, amount, "UAH", time, payer, revision);
    }
    private WfpPaymentCommand command() { return command("ИНЕТ КЛ",400,"2020-01-01T00:00:00Z",REVISION); }
    @Test void canonicalIdentityMatchesPhpIncludingCyrillicAndSpaces() {
        var c = command("ИНЕТ КЛ",400,"2026-10-16T12:00:00Z",REVISION);
        c.validate(config(),Instant.parse("2026-10-16T13:00:00Z"));
        assertEquals("88c9bf2650e62c4575d2448696b9b5a75f42f5865326c5cbaab7d3c6234179cd",c.operationKey());
        assertEquals("fb6b485ed61e391663edc2e7e945ff210e5a9bf8d198de4e5c905049f57927f4",c.requestHash());
        assertNotEquals(c.requestHash(),command("OTHER",400,c.confirmedAt(),REVISION).requestHash());
        assertEquals(c.operationKey(),command("OTHER",401,c.confirmedAt(),REVISION).operationKey());
    }
    @Test void malformedIdentityDatesAmountsAndPayersFailClosed() {
        for (var c : new WfpPaymentCommand[]{command("",400,"2020-01-01T00:00:00Z",REVISION),
                command("OTHER|X",400,"2020-01-01T00:00:00Z",REVISION),command(" ИНЕТ КЛ",400,"2020-01-01T00:00:00Z",REVISION),
                command("TEST",0,"2020-01-01T00:00:00Z",REVISION),command("TEST",1000000000,"2020-01-01T00:00:00Z",REVISION),
                command("TEST",400,"2018-01-01T00:00:00Z",REVISION),command("TEST",400,"2099-01-01T00:00:00Z",REVISION),
                command("TEST",400,"tomorrow",REVISION)}) {
            assertThrows(WfpPaymentException.class,()->c.validate(config(),Instant.parse("2026-10-16T13:00:00Z")));
        }
        var p=config(); p.setMerchantHash("c".repeat(64));
        assertThrows(WfpPaymentException.class,()->command().validate(p,Instant.now()));
    }
    @Test void controllerAuthorizationAndErrorsNeverExposeSqlOrTouchServiceWhenDisabled() {
        var service=mock(WfpPaymentService.class); var p=config(); var controller=new WfpPaymentController(service,p);
        p.setApiEnabled(false); assertEquals(503,controller.apply(p.getToken(),command()).getStatusCode().value());
        p.setApiEnabled(true); assertEquals(401,controller.preview("wrong",command()).getStatusCode().value());
        assertEquals(401,controller.status(null,command().operationKey()).getStatusCode().value());
        verifyNoInteractions(service);
        when(service.apply(any())).thenThrow(new IllegalStateException("private SQL/credential fixture"));
        var response=controller.apply(p.getToken(),command());
        assertEquals(503,response.getStatusCode().value()); assertEquals("no-store",response.getHeaders().getCacheControl());
        assertEquals(Map.of("code","payment_outcome_requires_lookup"),response.getBody());
    }
    private record Harness(WfpPaymentService service,WfpPaymentDao dao,PlatformTransactionManager manager,
                           WfpPaymentProperties config,SimpleTransactionStatus tx) {}
    private Harness harness() {
        var p=config(); var dao=mock(WfpPaymentDao.class); var manager=mock(PlatformTransactionManager.class);
        var tx=new SimpleTransactionStatus(); when(manager.getTransaction(any())).thenReturn(tx);
        when(dao.lookup(anyString(),anyString(),anyBoolean())).thenReturn(null);
        when(dao.snapshot(any(),eq(1L),anyBoolean())).thenReturn(new WfpPaymentDao.Snapshot("Fixture",null,null,null,REVISION));
        when(dao.insertNative(any(),any())).thenReturn(Map.of("status","SUCCEEDED","requestHash",command().requestHash(),"paymentId",1L));
        return new Harness(new WfpPaymentService(p,dao,manager),dao,manager,p,tx);
    }
    @Test void previewNeverStartsWriteTransactionOrInvokesProcedure() {
        var h=harness(); assertEquals("READY",h.service.preview(command()).get("status"));
        verifyNoInteractions(h.manager); verify(h.dao,never()).insertNative(any(),any()); verify(h.dao,never()).remember(any(),any());
    }
    @Test void applyCommitsNativeAndLedgerTogetherWithSerializableBoundary() {
        var h=harness(); h.service.apply(command());
        var sequence=inOrder(h.dao,h.manager);
        sequence.verify(h.manager).getTransaction(argThat(t->t.getIsolationLevel()==TransactionDefinition.ISOLATION_SERIALIZABLE
                && t.getPropagationBehavior()==TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        sequence.verify(h.dao).lookup(command().operationKey(),"fixture",true);
        sequence.verify(h.dao).transactionGuard(); sequence.verify(h.dao).verifyProcedures();
        sequence.verify(h.dao).snapshot(command(),1L,true); sequence.verify(h.dao).insertNative(eq(command()),any());
        sequence.verify(h.dao).remember(eq(command()),any()); sequence.verify(h.dao).transactionGuard();
        sequence.verify(h.manager).commit(h.tx);
    }
    @Test void duplicateSameRequestReturnsOriginalWithoutNewNativeCall() {
        var h=harness(); var existing=Map.<String,Object>of("status","SUCCEEDED","requestHash",command().requestHash(),"paymentId",99L);
        when(h.dao.lookup(command().operationKey(),"fixture",true)).thenReturn(existing);
        assertEquals(existing,h.service.apply(command()));
        verify(h.dao,never()).insertNative(any(),any()); verify(h.dao,never()).remember(any(),any());
    }
    @Test void identityConflictAndChangedPreviewRollbackWithoutProcedure() {
        var h=harness(); when(h.dao.lookup(command().operationKey(),"fixture",true)).thenReturn(Map.of("requestHash","other"));
        assertEquals("payment_identity_conflict",assertThrows(WfpPaymentException.class,()->h.service.apply(command())).getMessage());
        verify(h.manager).rollback(h.tx); verify(h.dao,never()).insertNative(any(),any());
        var h2=harness(); var changed=command("ИНЕТ КЛ",400,"2020-01-01T00:00:00Z","c".repeat(64));
        var service=h2.service;
        assertEquals("payer_or_template_changed",assertThrows(WfpPaymentException.class,()->service.apply(changed)).getMessage());
        verify(h2.manager).rollback(h2.tx); verify(h2.dao,never()).insertNative(any(),any());
    }
    @Test void ledgerFailureRollsBackNativePaymentRatherThanReportingSuccess() {
        var h=harness(); doThrow(new IllegalStateException("fixture failure")).when(h.dao).remember(any(),any());
        assertThrows(IllegalStateException.class,()->h.service.apply(command()));
        verify(h.manager).rollback(h.tx); verify(h.manager,never()).commit(any());
    }
    @Test void killSwitchAndPreviewRequirementPreventWrites() {
        var h=harness(); h.config.setApplyEnabled(false);
        assertThrows(WfpPaymentException.class,()->h.service.apply(command())); verifyNoInteractions(h.manager);
        h.config.setApplyEnabled(true);
        assertThrows(WfpPaymentException.class,()->h.service.apply(command("TEST",400,"2020-01-01T00:00:00Z",null)));
        verifyNoInteractions(h.manager);
    }
    @Test void nativeProtocolHasNoInvoiceAllocationAndUsesZeroNotNullDocumentNumber() {
        assertTrue(WfpPaymentDao.NATIVE_SQL.contains("@naclad_por=0"));
        assertTrue(WfpPaymentDao.NATIVE_SQL.contains("@notovarost=@amount"));
        assertTrue(WfpPaymentDao.NATIVE_SQL.contains("@corr_unicum=NULL"));
        assertFalse(WfpPaymentDao.NATIVE_SQL.contains("INSERT dbo.TMP_MOVE"));
        assertFalse(WfpPaymentDao.NATIVE_SQL.contains("UPDATE dbo.SCL_NAKL"));
    }
}
