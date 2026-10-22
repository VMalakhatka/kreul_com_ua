package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.*;
import org.example.proect.lavka.dao.folio.FolioManagerDao.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FolioManagerServiceTest {
    final FolioManagerDao dao=mock(FolioManagerDao.class);
    final FolioAccountingPriceDao mutex=mock(FolioAccountingPriceDao.class);
    final FolioAssemblyCalculator assembly=mock(FolioAssemblyCalculator.class);
    final FolioCurrencyPriceServiceTest.Manager tx=new FolioCurrencyPriceServiceTest.Manager();
    final FolioManagerService service=new FolioManagerService(dao,mutex,assembly,tx);
    final List<Map<String,String>> input=List.of(Map.of("COD_ARTIC","A","NAME_ARTIC","Новое имя"));
    final List<Target> before=List.of(new Target(0,"A",5,Map.of("NAME_ARTIC","Старое")),new Target(0,"A",7,Map.of("NAME_ARTIC","Старое")));
    final List<Target> after=List.of(new Target(0,"A",5,Map.of("NAME_ARTIC","Новое имя")),new Target(0,"A",7,Map.of("NAME_ARTIC","Новое имя")));
    String preview(){when(dao.snapshot(FolioManagerOperation.NAME,input,false)).thenReturn(before);return service.preview("NAME",input).token();}
    @Test void previewNeverWritesAndAllWarehousesAreVisible(){var token=preview();assertEquals(before,service.status(token).before());verify(dao,never()).update(any(),any());verifyNoInteractions(mutex);assertEquals(0,tx.commits);}
    @Test void applyChecksSnapshotWritesOnceAndVerifiesAllWarehouses(){String token=preview();when(dao.snapshot(FolioManagerOperation.NAME,input,true)).thenReturn(before,after);when(dao.update(any(),any())).thenReturn(2);
        assertThrows(IllegalArgumentException.class,()->service.apply(token,false));assertEquals("APPLIED",service.apply(token,true).status());assertEquals(1,tx.commits);assertEquals("APPLIED",service.apply(token,true).status());verify(dao,times(1)).update(FolioManagerOperation.NAME,input.get(0));verify(mutex).acquireRecalculationMutex(5000);}
    @Test void staleSnapshotPreventsWrites(){var t=preview();when(dao.snapshot(FolioManagerOperation.NAME,input,true)).thenReturn(after);assertEquals("STALE",service.apply(t,true).status());verify(dao,never()).update(any(),any());assertEquals(1,tx.rollbacks);}
    @Test void failureRollsBackAndReplayDoesNotRetry(){var t=preview();when(dao.snapshot(FolioManagerOperation.NAME,input,true)).thenReturn(before);when(dao.update(any(),any())).thenThrow(new IllegalStateException());assertEquals("UNKNOWN",service.apply(t,true).status());assertEquals("UNKNOWN",service.apply(t,true).status());verify(dao,times(1)).update(any(),any());assertEquals(1,tx.rollbacks);}
    @Test void verifyFailureRollsBack(){var t=preview();when(dao.snapshot(FolioManagerOperation.NAME,input,true)).thenReturn(before);when(dao.update(any(),any())).thenReturn(2);assertEquals("UNKNOWN",service.apply(t,true).status());assertEquals(1,tx.rollbacks);}
    @Test void unknownArticleIsReportedAndNotCreated(){when(dao.snapshot(FolioManagerOperation.NAME,input,false)).thenReturn(List.of());var p=service.preview("NAME",input);assertEquals(List.of("A"),p.missing());assertEquals("EMPTY",service.apply(p.token(),true).status());verify(dao,never()).update(any(),any());}
    @Test void groupsRequireAllSixFieldsAndExplicitBlanksMeanNull(){var row=new LinkedHashMap<String,String>();row.put("CODE_ARTIC","A");for(String f:FolioManagerOperation.GROUPS.fields)row.put(f,"");var normalized=FolioManagerOperation.GROUPS.validate(List.of(row));assertNull(normalized.get(0).get("NGROUP_TV6"));row.remove("NGROUP_TV6");assertThrows(IllegalArgumentException.class,()->FolioManagerOperation.GROUPS.validate(List.of(row)));}
    @Test void refusesDuplicateCodesUnsupportedUnicodeAndWrongOperation(){assertThrows(IllegalArgumentException.class,()->service.preview("NAME",List.of(input.get(0),input.get(0))));assertThrows(IllegalArgumentException.class,()->service.preview("NAME",List.of(Map.of("COD_ARTIC","A","NAME_ARTIC","😀"))));assertThrows(IllegalArgumentException.class,()->service.preview("NAME_RUS",input));verifyNoInteractions(dao);}
    @Test void baseKeepsDuplicatesAndUnmatchedRowsAndCannotApply(){var rows=List.of(Map.of("ARTIC_BAZA","A"),Map.of("ARTIC_BAZA","A"),Map.of("ARTIC_BAZA","B"));var table=new Table(List.of("ARTIC","ARTIC_BAZA"),List.of(List.of("A1","A"),List.of("A1","A"),Arrays.asList(null,null)));when(dao.base(rows)).thenReturn(table);var p=service.preview("BASE",rows);assertEquals(table,p.outputs().get("BASE"));assertEquals("READY",service.apply(p.token(),true).status());verify(dao,never()).update(any(),any());}
    @Test void existingDescriptionIsUpdatedWithoutDuplicateInsert(){var rows=List.of(Map.of("COD_ARTIC","A","DESCRIPTION","Text"));when(dao.snapshot(FolioManagerOperation.DESCRIPTION,rows,false)).thenReturn(List.of(new Target(0,"A",null,Map.of("DESCRIPTION","Before"))));var p=service.preview("DESCRIPTION",rows);assertEquals("PREVIEW",p.status());when(dao.snapshot(FolioManagerOperation.DESCRIPTION,rows,true)).thenReturn(p.before(),List.of(new Target(0,"A",null,Map.of("DESCRIPTION","Text"))));when(dao.update(FolioManagerOperation.DESCRIPTION,rows.get(0))).thenReturn(1);assertEquals("APPLIED",service.apply(p.token(),true).status());verify(dao,never()).insertDescription(any());}
    @Test void newDescriptionInsertPrecedesUpdateAndRollsBackTogether(){var row=Map.of("COD_ARTIC","NEW","DESCRIPTION","Text");var rows=List.of(row);when(dao.snapshot(FolioManagerOperation.DESCRIPTION,rows,false)).thenReturn(List.of());when(dao.snapshot(FolioManagerOperation.DESCRIPTION,rows,true)).thenReturn(List.of(),List.of(new Target(0,"NEW",null,Map.of("DESCRIPTION","Text"))));when(dao.insertDescription(row)).thenReturn(1);when(dao.update(FolioManagerOperation.DESCRIPTION,row)).thenReturn(1);var p=service.preview("DESCRIPTION",rows);assertEquals("APPLIED",service.apply(p.token(),true).status());var order=inOrder(dao);order.verify(dao).insertDescription(row);order.verify(dao).update(FolioManagerOperation.DESCRIPTION,row);}
}
