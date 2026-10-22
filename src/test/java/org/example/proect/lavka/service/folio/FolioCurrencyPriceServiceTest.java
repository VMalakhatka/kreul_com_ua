package org.example.proect.lavka.service.folio;
import org.example.proect.lavka.dao.folio.*;
import org.example.proect.lavka.dao.folio.FolioCurrencyPriceDao.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.*;
import org.springframework.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class FolioCurrencyPriceServiceTest {
    static class Manager extends AbstractPlatformTransactionManager {
        int commits,rollbacks;boolean active;
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object o,TransactionDefinition d){active=true;}
        protected void doCommit(DefaultTransactionStatus s){commits++;active=false;}
        protected void doRollback(DefaultTransactionStatus s){rollbacks++;active=false;}
    }
    final FolioCurrencyPriceDao dao=mock(FolioCurrencyPriceDao.class);
    final FolioAccountingPriceDao mutex=mock(FolioAccountingPriceDao.class);
    final Manager manager=new Manager();final FolioCurrencyPriceService service=new FolioCurrencyPriceService(dao,mutex,manager);
    final List<Input> input=List.of(new Input("ML-47068","1.8987","3"));
    final List<Row> before=List.of(new Row(0,"SCL_ARTC","ML-47068",5,null,"2","2"),new Row(0,"SCL_ARTC","ML-47068",7,null,"2","2"),new Row(0,"SCL_PRIC","ML-47068",7,42L,"2",null));
    final List<Row> after=List.of(new Row(0,"SCL_ARTC","ML-47068",5,null,"1.8987","3"),new Row(0,"SCL_ARTC","ML-47068",7,null,"1.8987","3"),new Row(0,"SCL_PRIC","ML-47068",7,42L,"1.8987",null));
    String preview(){when(dao.snapshot(input,false)).thenReturn(before);return service.preview(input).token();}
    @Test void previewOnlyReadsAndPreservesFourDecimals(){
        var token=preview();assertEquals("PREVIEW",service.status(token).status());verify(dao,never()).snapshot(anyList(),eq(true));verify(dao,never()).updateCards(any());verifyNoInteractions(mutex);assertEquals(0,manager.commits);
        assertEquals("1.8987",FolioCurrencyPriceService.validate(List.of(new Input("ML-47068","1,8987","3"))).get(0).price());
    }
    @Test void bothUpdatesShareOneTransactionAndReplayDoesNotWrite(){
        String token=preview();when(dao.snapshot(input,true)).thenReturn(before,after);
        when(dao.updateCards(any())).thenAnswer(i->{assertTrue(manager.active);return 2;});
        when(dao.updateRetail(any())).thenAnswer(i->{assertTrue(manager.active);return 1;});
        assertThrows(IllegalArgumentException.class,()->service.apply(token,false));
        assertEquals("APPLIED",service.apply(token,true).status());assertEquals(1,manager.commits);assertEquals(0,manager.rollbacks);
        assertEquals("APPLIED",service.apply(token,true).status());verify(dao,times(1)).updateCards(input.get(0));verify(dao,times(1)).updateRetail(input.get(0));
        var order=inOrder(mutex,dao);order.verify(mutex).acquireRecalculationMutex(5000);order.verify(dao).preflight();order.verify(dao).snapshot(input,true);order.verify(dao).updateCards(input.get(0));order.verify(dao).updateRetail(input.get(0));
    }
    @Test void concurrentChangeAbortsBeforeAnyUpdate(){String token=preview();when(dao.snapshot(input,true)).thenReturn(after);assertEquals("STALE",service.apply(token,true).status());verify(dao,never()).updateCards(any());assertEquals(1,manager.rollbacks);}
    @Test void secondUpdateFailureRollsBackAndNeverRetries(){String token=preview();when(dao.snapshot(input,true)).thenReturn(before);when(dao.updateCards(any())).thenReturn(2);when(dao.updateRetail(any())).thenThrow(new IllegalStateException());assertEquals("UNKNOWN",service.apply(token,true).status());assertEquals(1,manager.rollbacks);assertEquals(0,manager.commits);service.apply(token,true);verify(dao,times(1)).updateCards(any());}
    @Test void changedRowCountRollsBack(){String token=preview();when(dao.snapshot(input,true)).thenReturn(before);when(dao.updateCards(any())).thenReturn(3);assertEquals("UNKNOWN",service.apply(token,true).status());verify(dao,never()).updateRetail(any());assertEquals(1,manager.rollbacks);}
    @Test void missingRowsAreReportedAndNotInserted(){when(dao.snapshot(input,false)).thenReturn(List.of());var p=service.preview(input);assertEquals(List.of("ML-47068"),p.missingCards());assertEquals(List.of("ML-47068"),p.missingRetail());assertEquals("EMPTY",service.apply(p.token(),true).status());verify(dao,never()).updateCards(any());}
    @Test void rejectsDuplicatesAndUnrepresentableCodesBeforeDatabase(){assertThrows(IllegalArgumentException.class,()->service.preview(List.of(input.get(0),input.get(0))));assertThrows(IllegalArgumentException.class,()->service.preview(List.of(new Input("😀","1","3"))));verifyNoInteractions(dao);}
}
