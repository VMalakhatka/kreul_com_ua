package org.example.proect.lavka.service.folio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.proect.lavka.controller.FolioProfitHistoryController;
import org.example.proect.lavka.dao.wp.FolioProfitHistoryDao;
import org.example.proect.lavka.dao.wp.FolioProfitHistoryDao.Row;
import org.example.proect.lavka.dao.wp.FolioProfitHistoryDao.State;
import org.example.proect.lavka.property.DatabaseProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FolioProfitLatestResultTest {
    final FolioProfitHistoryDao dao=mock(FolioProfitHistoryDao.class);
    final FolioProfitReportService calculator=mock(FolioProfitReportService.class);
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final NavigableMap<Long,Row> rows=new TreeMap<>();
    final Map<String,Long> published=new HashMap<>();
    FolioProfitHistoryService service;

    @BeforeEach void setup() {
        var properties=new DatabaseProperties();
        properties.setUrl("jdbc:jtds:sqlserver://example.invalid/Paint_Ua");
        service=new FolioProfitHistoryService(dao,calculator,json,properties);
        when(dao.state(anyString(),anyString())).thenAnswer(a->rows.descendingMap().values().stream()
                .filter(r->r.source().equals(a.getArgument(0)) && r.month().equals(a.getArgument(1))).findFirst()
                .map(r->new State(published.get(r.month()),r.id(),r.status())));
        when(dao.byId(anyString(),anyString(),anyLong())).thenAnswer(a->Optional.ofNullable(rows.get(a.getArgument(2)))
                .filter(r->r.source().equals(a.getArgument(0)) && r.month().equals(a.getArgument(1))));
        when(dao.latestResult(anyString(),anyString(),anyLong())).thenAnswer(a->rows.descendingMap().values().stream()
                .filter(r->r.source().equals(a.getArgument(0)) && r.month().equals(a.getArgument(1)) && r.id()<=(long)a.getArgument(2))
                .filter(r->List.of("COMPLETED","PROVISIONAL").contains(r.status()) && r.reportJson()!=null
                        && !List.of("","null").contains(r.reportJson().trim())).findFirst());
    }
    @AfterEach void readOnly() {
        verifyNoInteractions(calculator);
        verify(dao,never()).reserve(any(),any(),any(),any(),any());
        verify(dao,never()).finish(any(),any(),anyLong(),any(),any(),anyBoolean(),any());
    }
    void put(long id,String month,String status,String profit) throws Exception {
        String payload=null;
        if(profit!=null) {
            ObjectNode tree=json.valueToTree(FolioProfitHistoryServiceTest.report(month,!status.equals("COMPLETED")));
            var city=(ObjectNode)tree.get("cities").get(0);
            city.put("profit",profit);
            String gross=new java.math.BigDecimal(profit).add(new java.math.BigDecimal("20.01")).toPlainString();
            city.put("baseGrossProfit",gross);city.put("grossProfit",gross);
            if(!status.equals("COMPLETED")) {
                tree.put("complete",false);
                var warning=tree.putArray("warnings").addObject();
                warning.put("code","SYNTHETIC_CHECK_REQUIRED");
                warning.put("message","Synthetic preliminary report; not production data");
                warning.putObject("details");
            }
            payload=json.writeValueAsString(tree);
        }
        putPayload(id,month,status,payload);
    }
    void putPayload(long id,String month,String status,String payload) {
        rows.put(id,new Row(id,"Paint_Ua",month,"request-"+id,"hash","{}",status,payload,status.equals("COMPLETED"),
                status.equals("FAILED")?"SYNTHETIC_FAILED":null,Instant.EPOCH,status.equals("RUNNING")?null:Instant.EPOCH));
    }
    @Test void latestOfSeveralProvisionalResultsWinsOverPublishedAndExplicitOldIsStable() throws Exception {
        put(1,"2026-09","COMPLETED","80");published.put("2026-09",1L);
        put(2,"2026-09","PROVISIONAL","120");put(3,"2026-09","PROVISIONAL","180");
        var before=Map.copyOf(rows);
        var selected=service.get("2026-09",null);
        assertThat(selected.revisionId()).isEqualTo(3);
        assertThat(selected.status()).isEqualTo("PROVISIONAL");
        assertThat(selected.ok()).isTrue();assertThat(selected.auditComplete()).isFalse();
        assertThat(selected.report().complete()).isFalse();
        assertThat(selected.report().warnings()).extracting(w->w.code()).containsExactly("SYNTHETIC_CHECK_REQUIRED");
        assertThat(selected.report().controls().auditTruncated()).isTrue();
        assertThat(selected.publishedRevisionId()).isEqualTo(1);assertThat(selected.published()).isFalse();
        assertThat(selected.latestRevisionId()).isEqualTo(3);assertThat(selected.latestStatus()).isEqualTo("PROVISIONAL");
        var old=service.get("2026-09",1L);
        assertThat(old.report().cities().get(0).profit()).isEqualByComparingTo("80");
        assertThat(old.published()).isTrue();assertThat(old.revisionId()).isEqualTo(1);
        assertThat(rows).isEqualTo(before);assertThat(published.get("2026-09")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"FAILED","RUNNING"})
    void lastUnsuccessfulAttemptDoesNotHideExistingResult(String status) throws Exception {
        put(1,"2026-09","COMPLETED","80");published.put("2026-09",1L);
        put(2,"2026-09","PROVISIONAL","45");put(3,"2026-09",status,"9999");
        var selected=service.get("2026-09",null);
        assertThat(selected.revisionId()).isEqualTo(2);assertThat(selected.status()).isEqualTo("PROVISIONAL");
        assertThat(selected.latestRevisionId()).isEqualTo(3);assertThat(selected.latestStatus()).isEqualTo(status);
        assertThat(selected.report().cities().get(0).profit()).isEqualByComparingTo("45");
        var range=service.range("2026-09","2026-09");
        assertThat(range.months().get(0).revisionId()).isEqualTo(2);
        assertThat(range.months().get(0).latestStatus()).isEqualTo(status);
        assertThat(range.totals().get(0).profit()).isEqualByComparingTo("45");
        assertThat(range.complete()).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"FAILED","RUNNING"})
    void noValidResultReturnsLatestAttemptWithoutMoneyOrExportResultId(String status) throws Exception {
        put(1,"2026-09",status,"9999");
        var selected=service.get("2026-09",null);
        assertThat(selected.status()).isEqualTo(status);assertThat(selected.ok()).isFalse();
        assertThat(selected.revisionId()).isNull();assertThat(selected.latestRevisionId()).isEqualTo(1);
        assertThat(selected.report()).isNull();assertThat(selected.published()).isFalse();
        var range=service.range("2026-09","2026-09");
        assertThat(range.missingMonths()).containsExactly("2026-09");
        assertThat(range.months().get(0).status()).isEqualTo(status);
        assertThat(range.months().get(0).revisionId()).isNull();
        assertThat(range.totals()).allSatisfy(t->{assertThat(t.profit()).isNull();assertThat(t.complete()).isFalse();});
        // Explicit historical attempt still exposes its original diagnostic payload.
        assertThat(service.get("2026-09",1L).report()).isNotNull();
    }
    @Test void rangeIdsAndTotalsUseSameSelectionAsDefaultMonthsAndExportPinsThem() throws Exception {
        put(1,"2026-09","COMPLETED","80");published.put("2026-09",1L);
        put(2,"2026-09","PROVISIONAL","100");put(3,"2026-09","PROVISIONAL","180");
        put(4,"2026-10","COMPLETED","30");published.put("2026-10",4L);
        put(5,"2026-10","PROVISIONAL","50");put(6,"2026-10","FAILED",null);
        var range=service.range("2026-09","2026-10");
        assertThat(range.months()).extracting(m->m.revisionId()).containsExactly(3L,5L);
        assertThat(range.months()).extracting(m->m.publishedRevisionId()).containsExactly(1L,4L);
        assertThat(range.months()).allSatisfy(m->{
            assertThat(m.revisionId()).isEqualTo(service.get(m.month(),null).revisionId());
            assertThat(m.published()).isFalse();
        });
        assertThat(range.totals().get(0).profit()).isEqualByComparingTo("230");
        assertThat(range.complete()).isFalse();assertThat(range.missingMonths()).isEmpty();
        put(7,"2026-09","PROVISIONAL","999");
        assertThat(service.get("2026-09",range.months().get(0).revisionId()).report().cities().get(0).profit())
                .isEqualByComparingTo("180");
        assertThat(service.get("2026-09",null).revisionId()).isEqualTo(7);
        var tree=json.valueToTree(range);
        assertThat(tree.get("months").get(0).get("publishedRevisionId").asLong()).isEqualTo(1);
        assertThat(tree.get("months").get(0).get("published").asBoolean()).isFalse();
    }
    @Test void missingAndNullPayloadsNeverBecomeZeroResults() {
        assertThat(service.get("2026-09",null).status()).isEqualTo("MISSING");
        putPayload(1,"2026-09","COMPLETED",null);
        putPayload(2,"2026-09","PROVISIONAL","  null  ");
        putPayload(3,"2026-09","PROVISIONAL","  ");
        var selected=service.get("2026-09",null);
        assertThat(selected.status()).isEqualTo("MISSING");assertThat(selected.report()).isNull();
        assertThat(selected.revisionId()).isNull();assertThat(selected.latestRevisionId()).isEqualTo(3);
        assertThat(selected.errorCode()).isEqualTo("PROFIT_SAVED_REPORT_PAYLOAD_MISSING");
        assertThat(service.range("2026-09","2026-10").totals()).allSatisfy(t->assertThat(t.profit()).isNull());
    }
    @Test void corruptSelectedPayloadFailsVisiblyInsteadOfRecalculatingOrFallingBack() throws Exception {
        put(1,"2026-09","COMPLETED","80");published.put("2026-09",1L);
        putPayload(2,"2026-09","PROVISIONAL","{invalid");
        assertThatThrownBy(()->service.get("2026-09",null)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Saved profit report JSON is invalid");
    }
    @Test void newerEmptyPayloadsDoNotHideOlderReportAndLateOldCompletionDoesNotWin() throws Exception {
        put(1,"2026-09","RUNNING",null);
        put(2,"2026-09","PROVISIONAL","120");
        putPayload(3,"2026-09","COMPLETED",null);
        putPayload(4,"2026-09","PROVISIONAL","null");
        assertThat(service.get("2026-09",null).revisionId()).isEqualTo(2);
        put(1,"2026-09","COMPLETED","80"); // Earlier request finishes later.
        published.put("2026-09",1L);
        assertThat(service.get("2026-09",null).revisionId()).isEqualTo(2);
        assertThat(service.get("2026-09",null).latestRevisionId()).isEqualTo(4);
    }
    @Test void httpDefaultAndRangeSerializeSameResultAndExplicitExportRemainsPinned() throws Exception {
        put(1,"2026-09","COMPLETED","80");published.put("2026-09",1L);
        put(2,"2026-09","PROVISIONAL","120");put(3,"2026-09","FAILED",null);
        var mapper=json.copy().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var mvc=MockMvcBuilders.standaloneSetup(new FolioProfitHistoryController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
        String month=mvc.perform(get("/admin/folio/profit-report/saved/2026-09"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revisionId").value(2))
                .andExpect(jsonPath("$.status").value("PROVISIONAL"))
                .andExpect(jsonPath("$.latestRevisionId").value(3)).andExpect(jsonPath("$.latestStatus").value("FAILED"))
                .andExpect(jsonPath("$.publishedRevisionId").value(1)).andExpect(jsonPath("$.published").value(false))
                .andExpect(jsonPath("$.report.complete").value(false))
                .andExpect(jsonPath("$.report.warnings[0].code").value("SYNTHETIC_CHECK_REQUIRED"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String range=mvc.perform(get("/admin/folio/profit-report/saved").param("fromMonth","2026-09").param("toMonth","2026-09"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.months[0].revisionId").value(2))
                .andExpect(jsonPath("$.months[0].publishedRevisionId").value(1))
                .andExpect(jsonPath("$.months[0].published").value(false))
                .andExpect(jsonPath("$.months[0].latestStatus").value("FAILED"))
                .andExpect(jsonPath("$.complete").value(false))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(new java.math.BigDecimal(json.readTree(month).at("/report/cities/0/profit").asText()))
                .isEqualByComparingTo("120")
                .isEqualByComparingTo(new java.math.BigDecimal(json.readTree(range).at("/totals/0/profit").asText()));
        put(4,"2026-09","PROVISIONAL","999");
        mvc.perform(get("/admin/folio/profit-report/saved/2026-09").param("revisionId","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revisionId").value(2));
        mvc.perform(get("/admin/folio/profit-report/saved/2026-09").param("revisionId","1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revisionId").value(1))
                .andExpect(jsonPath("$.published").value(true));
        // Build outputs for frontend contract checks; synthetic only, never a production export.
        Path fixtures=Path.of("target","profit-latest-result-fixtures");Files.createDirectories(fixtures);
        Files.writeString(fixtures.resolve("month.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(month)));
        Files.writeString(fixtures.resolve("range.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(mapper.readTree(range)));
    }
    @Test void selectionIsBoundedByStateAndOtherMonthCannotLeakIntoIt() throws Exception {
        put(1,"2026-09","COMPLETED","80");put(2,"2026-09","PROVISIONAL","100");put(3,"2026-10","COMPLETED","999");
        when(dao.state("Paint_Ua","2026-09")).thenReturn(Optional.of(new State(1L,1,"COMPLETED")));
        assertThat(service.get("2026-09",null).revisionId()).isEqualTo(1);
        verify(dao).latestResult("Paint_Ua","2026-09",1);
        assertThatThrownBy(()->service.get("2026-09",3L)).isInstanceOf(FolioAccountNotFoundException.class);
    }
}
