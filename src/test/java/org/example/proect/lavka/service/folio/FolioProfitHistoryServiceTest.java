package org.example.proect.lavka.service.folio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.proect.lavka.dao.wp.FolioProfitHistoryDao;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.*;
import org.example.proect.lavka.dto.folio.FolioProfitSavedReports.*;
import org.example.proect.lavka.property.DatabaseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FolioProfitHistoryServiceTest {
    private FolioProfitHistoryDao dao;
    private FolioProfitReportService calculator;
    private FolioProfitHistoryService service;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private final CalculateRequest request=new CalculateRequest("386cc2b2-cb52-4c21-89d1-c72e3c834290",null,null,null,null,BigDecimal.ZERO,null,null,null);
    private final Map<Long,FolioProfitHistoryDao.Row> rows=new LinkedHashMap<>();
    private Long published=null;
    @BeforeEach void setup() {
        dao=mock(FolioProfitHistoryDao.class); calculator=mock(FolioProfitReportService.class);
        DatabaseProperties database=new DatabaseProperties(); database.setUrl("jdbc:jtds:sqlserver:"+"//example.invalid/Paint_Ua");
        service=new FolioProfitHistoryService(dao,calculator,json,database);
        when(calculator.resolveTaxSettings(any())).thenReturn(org.example.proect.lavka.dto.folio.FolioProfitTaxSettings.defaults());
        when(dao.byRequest(anyString(),anyString())).thenAnswer(a->rows.values().stream().filter(r->r.requestId().equals(a.getArgument(1))).findFirst());
        when(dao.byId(anyString(),anyString(),anyLong())).thenAnswer(a->Optional.ofNullable(rows.get(a.getArgument(2))));
        when(dao.state(anyString(),anyString())).thenAnswer(a->rows.isEmpty()?Optional.empty():Optional.of(
                new FolioProfitHistoryDao.State(published,rows.size(),rows.get((long)rows.size()).status())));
        when(dao.reserve(anyString(),anyString(),anyString(),anyString(),anyString())).thenAnswer(a->{
            long id=rows.size()+1;
            var row=new FolioProfitHistoryDao.Row(id,a.getArgument(0),a.getArgument(1),a.getArgument(2),a.getArgument(3),a.getArgument(4),
                    "RUNNING",null,false,null,Instant.now(),null); rows.put(id,row); return row;
        });
        doAnswer(a->{
            long id=a.getArgument(2); var old=rows.get(id); String status=a.getArgument(3);
            rows.put(id,new FolioProfitHistoryDao.Row(id,old.source(),old.month(),old.requestId(),old.requestHash(),old.requestJson(),status,
                    a.getArgument(4),a.getArgument(5),a.getArgument(6),old.createdAt(),Instant.now()));
            if(!status.equals("FAILED")&&(published==null||status.equals("COMPLETED")&&published<id)) published=id;
            return null;
        }).when(dao).finish(anyString(),anyString(),anyLong(),anyString(),nullable(String.class),anyBoolean(),nullable(String.class));
    }
    @Test void oneAuditCalculationIsSavedAndReplayNeverRecalculates() throws Exception {
        var full=report("2025-07",false);
        when(calculator.calculate(any(),eq(true),any())).thenReturn(full);
        var saved=service.calculate("2025-07",request);
        assertThat(saved.status()).isEqualTo("COMPLETED");
        assertThat(saved.request().odesaAdditionalSalary()).isZero();
        assertThat(saved.request().kyivAdditionalSalary()).isNull();
        assertThat(saved.report()).isEqualTo(full);
        var replay=service.calculate("2025-07",request);
        assertThat(replay.revisionId()).isEqualTo(saved.revisionId());
        verify(calculator,times(1)).calculate(any(),eq(true),any());
        var arg=org.mockito.ArgumentCaptor.forClass(FolioProfitReportService.Request.class);
        verify(calculator).calculate(arg.capture(),eq(true),any());
        assertThat(arg.getValue().month()).isEqualTo("2025-07");
        assertThat(arg.getValue().odesaAdditionalSalary()).isZero();
        String fixture=System.getProperty("folio.profit.saved.fixture.output");
        if(fixture!=null) java.nio.file.Files.writeString(java.nio.file.Path.of(fixture),json.writeValueAsString(saved));
    }
    @Test void partialAndFailedRevisionsNeverReplacePublishedReport() {
        when(calculator.calculate(any(),eq(true),any())).thenReturn(report("2025-07",false));
        service.calculate("2025-07",request);
        when(calculator.calculate(any(),eq(true),any())).thenReturn(report("2025-07",true));
        var partial=service.calculate("2025-07",withId(UUID.randomUUID().toString()));
        assertThat(partial.status()).isEqualTo("PROVISIONAL");
        assertThat(partial.auditComplete()).isFalse();
        assertThat(partial.published()).isFalse();
        assertThat(service.get("2025-07",null).revisionId()).isEqualTo(1);
        assertThat(service.get("2025-07",null).latestRevisionId()).isEqualTo(2);
        when(calculator.calculate(any(),eq(true),any())).thenThrow(new IllegalStateException("private connection details"));
        var failed=service.calculate("2025-07",withId(UUID.randomUUID().toString()));
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.report()).isNull();
        assertThat(failed.errorCode()).isEqualTo("PROFIT_SAVED_CALCULATION_FAILED");
        assertThat(service.get("2025-07",null).revisionId()).isEqualTo(1);
    }
    @Test void historyAndMissingRangeNeverReadFolio() {
        var range=service.range("2025-07","2025-11");
        assertThat(range.months()).hasSize(5);
        assertThat(range.missingMonths()).containsExactly("2025-07","2025-08","2025-09","2025-10","2025-11");
        assertThat(range.complete()).isFalse();
        assertThat(range.totals()).allSatisfy(t->assertThat(t.profit()).isNull());
        assertThat(service.get("2025-07",null).status()).isEqualTo("MISSING");
        service.revisions("2025-07",20,null);
        verifyNoInteractions(calculator);
    }
    @Test void reusedKeyWithOtherParametersOrMonthIsConflict() {
        when(calculator.calculate(any(),eq(true),any())).thenReturn(report("2025-07",false));
        service.calculate("2025-07",request);
        assertThatThrownBy(()->service.calculate("2025-08",request)).isInstanceOf(FolioAccountConflictException.class);
        var changed=new CalculateRequest(request.requestId(),null,null,null,null,null,null,null,null);
        assertThatThrownBy(()->service.calculate("2025-07",changed)).isInstanceOf(FolioAccountConflictException.class);
        verify(calculator,times(1)).calculate(any(),eq(true),any());
    }
    @Test void runningRequestReturnsStateInsteadOfStartingAgain() {
        when(calculator.calculate(any(),eq(true),any())).thenReturn(report("2025-07",false));
        service.calculate("2025-07",request);
        var old=rows.get(1L);
        rows.put(1L,new FolioProfitHistoryDao.Row(1,old.source(),old.month(),old.requestId(),old.requestHash(),old.requestJson(),"RUNNING",null,false,null,old.createdAt(),null));
        clearInvocations(calculator);
        assertThat(service.calculate("2025-07",request).status()).isEqualTo("RUNNING");
        verifyNoInteractions(calculator);
    }
    @Test void invalidMonthRangeOrNamespaceDoesNotReadFolio() {
        assertThatThrownBy(()->service.range("2025-07","2027-07")).isInstanceOf(FolioAccountValidationException.class);
        assertThatThrownBy(()->service.get("bad",null)).isInstanceOf(FolioAccountValidationException.class);
        assertThatThrownBy(()->FolioProfitHistoryService.sourceFromUrl("unknown")).isInstanceOf(FolioAccountValidationException.class);
        assertThat(FolioProfitHistoryService.sourceFromUrl("jdbc:jtds:sqlserver:"+"//example.invalid/Paint_Rus;test=true")).isEqualTo("Paint_Rus");
        verifyNoInteractions(calculator);
    }
    static FolioProfitReportResponse report(String month,boolean truncated) {
        BigDecimal z=BigDecimal.ZERO;
        var inputs=new Inputs(new BigDecimal("0.4285714286"),"REGISTERED_EMPLOYEE_SHARE",new BigDecimal("0.41"),null,null,z,"REQUEST_OVERRIDE",
                List.of(1,7,12),List.of(5),List.of(1,7,12),List.of(5),z,"DEFAULT");
        return new FolioProfitReportResponse(true,month,OffsetDateTime.parse("2026-09-09T12:00:00Z"),true,"test-rules",inputs,
                List.of(new CityResult("KYIV",new BigDecimal("100.01"),z,new BigDecimal("100.01"),new BigDecimal("20.01"),new BigDecimal("80.00")),
                        new CityResult("ODESA",new BigDecimal("50"),z,new BigDecimal("50"),new BigDecimal("10"),new BigDecimal("40"))),
                List.of(new InventoryResult("KYIV",List.of(1,7,12),new BigDecimal("300"),new BigDecimal("400"),new BigDecimal("100"),1,1,0,0,List.of()),
                        new InventoryResult("ODESA",List.of(5),new BigDecimal("500"),new BigDecimal("450"),new BigDecimal("-50"),1,1,0,0,List.of())),
                List.of(),List.of(),new MasterClassSummary(5,"Synthetic",true,"TEST",z,z,z,z,z,0,0,0,0,false),List.of(),
                new Controls(0,z,z,z,z,z,0,truncated,Map.of(),0,0,0,z,z),List.of(),List.of(),null,List.of(),false,
                Map.of("EXPENSES",new SectionStatus("AVAILABLE",null,null,null)));
    }
    @Test void employeeCountsPersistAndParticipateInIdempotency() {
        when(calculator.calculate(any(),eq(true),any())).thenReturn(report("2025-07",false));
        var counted=new CalculateRequest(request.requestId(),null,null,null,null,BigDecimal.ZERO,null,null,null,0,7);
        var saved=service.calculate("2025-07",counted);
        assertThat(saved.request().kyivEmployeeCount()).isZero();
        assertThat(saved.request().odesaEmployeeCount()).isEqualTo(7);
        assertThat(service.calculate("2025-07",counted).revisionId()).isEqualTo(saved.revisionId());
        verify(calculator).calculate(argThat(r->r.kyivEmployeeCount()==0&&r.odesaEmployeeCount()==7),eq(true),any());
        assertThatThrownBy(()->service.calculate("2025-07",new CalculateRequest(request.requestId(),null,null,null,null,
                BigDecimal.ZERO,null,null,null,4,3))).isInstanceOf(FolioAccountConflictException.class);
        verify(calculator,times(1)).calculate(any(),eq(true),any());
    }
    @Test void oldStoredRequestHashReplaysAndOldInputsStayUnknown() throws Exception {
        String raw="{\"requestId\":\"386cc2b2-cb52-4c21-89d1-c72e3c834290\",\"odesaTaxShare\":null,\"rubToUahRate\":null,"
                +"\"odesaMasterClassIncome\":null,\"odesaMasterClassReturn\":null,\"odesaAdditionalSalary\":\"0\","
                +"\"kyivStockWarehouseIds\":null,\"odesaStockWarehouseIds\":null,\"kyivAdditionalSalary\":null}";
        assertThat(json.writeValueAsString(request)).isEqualTo(raw);
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(("2025-07\n"+raw).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var tree=json.valueToTree(report("2025-07",false));
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree.get("inputs")).remove(List.of(
                "kyivEmployeeCount","odesaEmployeeCount","totalEmployeeCount","allocationMode","kyivTaxShare"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)tree).remove("taxDetails");
        rows.put(1L,new FolioProfitHistoryDao.Row(1,"Paint_Ua","2025-07",request.requestId(),hash,raw,"COMPLETED",
                json.writeValueAsString(tree),true,null,Instant.now(),Instant.now())); published=1L;
        var replay=service.calculate("2025-07",request);
        assertThat(replay.report().inputs().kyivEmployeeCount()).isNull();
        assertThat(replay.report().inputs().allocationMode()).isNull();
        assertThat(replay.report().inputs().odesaTaxShare()).isEqualByComparingTo("0.4285714286");
        assertThat(replay.report().taxDetails()).isNull();
        verifyNoInteractions(calculator);
    }
    @Test void pinnedVersionConflictIsBeforeReserveAndDoesNotCreateFailedRevision() {
        var pinned=new CalculateRequest(request.requestId(),null,null,null,null,BigDecimal.ZERO,null,null,null,null,null,3L);
        when(calculator.resolveTaxSettings(3L)).thenThrow(new FolioAccountConflictException(
                "PROFIT_TAX_SETTINGS_VERSION_CONFLICT","Changed"));
        assertThatThrownBy(()->service.calculate("2025-07",pinned)).isInstanceOfSatisfying(FolioAccountConflictException.class,
                e->assertThat(e.getCode()).isEqualTo("PROFIT_TAX_SETTINGS_VERSION_CONFLICT"));
        verify(dao,never()).reserve(any(),any(),any(),any(),any());
        verify(dao,never()).finish(any(),any(),anyLong(),any(),any(),anyBoolean(),any());
        verify(calculator,never()).calculate(any(),anyBoolean(),any());
    }
    @Test void capturedVersionAndListsPersistAndReplayIgnoresLaterSettingsChanges() {
        var captured=new org.example.proect.lavka.dto.folio.FolioProfitTaxSettings(3L,List.of("ФОП1"),List.of("ФОП2"));
        when(calculator.resolveTaxSettings(3L)).thenReturn(captured);
        var base=report("2025-07",false);
        var withTax=new FolioProfitReportResponse(base.ok(),base.month(),base.calculatedAt(),base.complete(),base.ruleVersion(),
                base.inputs(),base.cities(),base.inventory(),base.expenses(),base.documents(),base.masterClass(),base.masterClassDocuments(),
                base.controls(),base.warnings(),base.expenseLines(),base.periodPolicy(),base.periodDiagnostics(),base.periodDiagnosticsTruncated(),
                base.sections(),new TaxDetails(captured,new BigDecimal("10"),new BigDecimal("20"),BigDecimal.ZERO,List.of()));
        when(calculator.calculate(any(),eq(true),same(captured))).thenReturn(withTax);
        var pinned=new CalculateRequest(request.requestId(),null,null,null,null,BigDecimal.ZERO,null,null,null,null,null,3L);
        var saved=service.calculate("2025-07",pinned);
        assertThat(saved.request().taxSettingsVersion()).isEqualTo(3);
        assertThat(saved.report().taxDetails().settings()).isEqualTo(captured);
        var order=inOrder(calculator,dao);
        order.verify(calculator).resolveTaxSettings(3L);
        order.verify(dao).reserve(any(),any(),any(),any(),any());
        order.verify(calculator).calculate(any(),eq(true),same(captured));
        when(calculator.resolveTaxSettings(3L)).thenThrow(new FolioAccountConflictException("PROFIT_TAX_SETTINGS_VERSION_CONFLICT","Changed"));
        clearInvocations(calculator);
        assertThat(service.calculate("2025-07",pinned).report().taxDetails()).isEqualTo(saved.report().taxDetails());
        assertThat(service.get("2025-07",saved.revisionId()).report().taxDetails()).isEqualTo(saved.report().taxDetails());
        service.revisions("2025-07",20,null);
        verifyNoInteractions(calculator);
    }
    private CalculateRequest withId(String id) { return new CalculateRequest(id,null,null,null,null,BigDecimal.ZERO,null,null,null); }
}
