package org.example.proect.lavka.service.folio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse;
import org.example.proect.lavka.dto.folio.FolioProfitSavedReports.Revision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;

class FolioProfitHistoryTotalsTest {
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private static final List<String> FLOWS=List.of("baseGrossProfit","manualGrossAdjustments","grossProfit","operatingExpenses","profit");
    private static final List<String> SCOPES=List.of("kyivWarehouseIds","odesaWarehouseIds","kyivStockWarehouseIds","odesaStockWarehouseIds");
    @Test void sumsMoneyExactlyButUsesOnlyFirstAndLastInventoryBoundaries() {
        var july=revision(FolioProfitHistoryServiceTest.report("2025-07",false));
        var august=revision(FolioProfitHistoryServiceTest.report("2025-08",false));
        var totals=FolioProfitHistoryTotals.calculate("KYIV",List.of(july,august));
        assertThat(totals.grossProfit()).isEqualByComparingTo("200.02");
        assertThat(totals.operatingExpenses()).isEqualByComparingTo("40.02");
        assertThat(totals.profit()).isEqualByComparingTo("160.00");
        assertThat(totals.openingAccountingValue()).isEqualByComparingTo("300");
        assertThat(totals.closingAccountingValue()).isEqualByComparingTo("400");
        assertThat(totals.accountingValueChange()).isEqualByComparingTo("100");
        assertThat(totals.complete()).isTrue();
    }
    @Test void missingMonthNeverBecomesZeroOrPartialSum() {
        var missing=new Revision(false,"MISSING","Paint_Ua","2025-08",null,null,null,null,null,false,false,null,null,null,null,null);
        var totals=FolioProfitHistoryTotals.calculate("KYIV",List.of(revision(FolioProfitHistoryServiceTest.report("2025-07",false)),missing));
        assertThat(totals.profit()).isNull();
        assertThat(totals.operatingExpenses()).isNull();
        assertThat(totals.closingAccountingValue()).isNull();
        assertThat(totals.complete()).isFalse();
    }

    @Test void differentWarehouseScopesDoNotBlockSavedAmountsForEitherCity() throws Exception {
        var july=FolioProfitHistoryServiceTest.report("2025-07",false);
        var august=changed("2025-08",tree->{
            var inputs=(ObjectNode)tree.get("inputs");
            inputs.putArray("kyivWarehouseIds").add(1).add(7);
            inputs.putArray("odesaWarehouseIds").add(5).add(15);
            inputs.putArray("kyivStockWarehouseIds").add(1);
            inputs.putArray("odesaStockWarehouseIds").add(15);
            ((ObjectNode)tree.get("inventory").get(0)).putArray("warehouseIds").add(1);
            ((ObjectNode)tree.get("inventory").get(1)).putArray("warehouseIds").add(15);
        });
        for(String city:List.of("KYIV","ODESA")) {
            var expected=FolioProfitHistoryTotals.calculate(city,List.of(revision(july),revision(FolioProfitHistoryServiceTest.report("2025-08",false))));
            assertThat(FolioProfitHistoryTotals.calculate(city,List.of(revision(july),revision(august)))).isEqualTo(expected);
            assertThat(expected.complete()).isTrue();
        }
    }

    @ParameterizedTest @ValueSource(strings={"empty","null","missing","noInputs"})
    void missingLegacyScopeMetadataDoesNotBlockExistingAmounts(String mode) throws Exception {
        var old=changed("2025-07",tree->{
            var inputs=(ObjectNode)tree.get("inputs");
            for(String field:SCOPES) {
                if(mode.equals("empty")) inputs.putArray(field);
                else if(mode.equals("null")) inputs.putNull(field);
                else inputs.remove(field);
            }
            if(mode.equals("noInputs")) tree.remove("inputs");
        });
        for(String city:List.of("KYIV","ODESA")) {
            var expected=FolioProfitHistoryTotals.calculate(city,List.of(revision(FolioProfitHistoryServiceTest.report("2025-07",false))));
            assertThat(FolioProfitHistoryTotals.calculate(city,List.of(revision(old)))).isEqualTo(expected);
        }
    }

    @Test void inventoryUsesRequestedBoundariesNotSumOrIntermediateEndpoints() throws Exception {
        var july=changed("2025-07",tree->((ObjectNode)tree.get("inventory").get(0)).put("closingAccountingValue","9999"));
        var august=changed("2025-08",tree->tree.putArray("inventory"));
        var september=changed("2025-09",tree->{
            var inventory=(ObjectNode)tree.get("inventory").get(0);
            inventory.put("openingAccountingValue","8888");
            inventory.put("closingAccountingValue","250");
        });
        var totals=FolioProfitHistoryTotals.calculate("KYIV",List.of(revision(july),revision(august),revision(september)));
        assertThat(totals.openingAccountingValue()).isEqualByComparingTo("300");
        assertThat(totals.closingAccountingValue()).isEqualByComparingTo("250");
        assertThat(totals.accountingValueChange()).isEqualByComparingTo("-50");
        assertThat(totals.profit()).isEqualByComparingTo("240");
    }

    @ParameterizedTest @ValueSource(ints={0,1,2})
    void missingMonthNeverFallsBackToAnotherMonth(int missingIndex) {
        var revisions=new ArrayList<Revision>();
        for(String month:List.of("2025-07","2025-08","2025-09")) revisions.add(revision(FolioProfitHistoryServiceTest.report(month,false)));
        String month=revisions.get(missingIndex).month();
        revisions.set(missingIndex,new Revision(false,"MISSING","Paint_Ua",month,null,null,null,null,null,false,false,null,null,null,null,null));
        var totals=FolioProfitHistoryTotals.calculate("KYIV",revisions);
        var tree=JSON.valueToTree(totals);
        for(String field:FLOWS) assertThat(tree.get(field).isNull()).as(field).isTrue();
        assertThat(totals.complete()).isFalse();
        if(missingIndex==0) assertThat(totals.openingAccountingValue()).isNull();
        else assertThat(totals.openingAccountingValue()).isEqualByComparingTo("300");
        if(missingIndex==2) assertThat(totals.closingAccountingValue()).isNull();
        else assertThat(totals.closingAccountingValue()).isEqualByComparingTo("400");
        if(missingIndex==1) assertThat(totals.accountingValueChange()).isEqualByComparingTo("100");
        else assertThat(totals.accountingValueChange()).isNull();
    }

    @ParameterizedTest @ValueSource(strings={"baseGrossProfit","manualGrossAdjustments","grossProfit","operatingExpenses","profit"})
    void missingFieldMakesOnlyItsAggregateUnknown(String field) throws Exception {
        var august=changed("2025-08",tree->((ObjectNode)tree.get("cities").get(0)).remove(field));
        var july=revision(FolioProfitHistoryServiceTest.report("2025-07",false));
        var actual=FolioProfitHistoryTotals.calculate("KYIV",List.of(july,revision(august)));
        var expected=JSON.valueToTree(FolioProfitHistoryTotals.calculate("KYIV",List.of(july,july)));
        var tree=JSON.valueToTree(actual);
        for(String flow:FLOWS) {
            if(flow.equals(field)) assertThat(tree.get(flow).isNull()).isTrue();
            else assertThat(tree.get(flow)).as(flow).isEqualTo(expected.get(flow));
        }
        assertThat(actual.complete()).isFalse();
    }

    @ParameterizedTest @ValueSource(strings={"missingCity","missingCities","missingInventory","missingBoundary"})
    void genuinelyMissingDataIsNotMarkedComplete(String mode) throws Exception {
        var august=changed("2025-08",tree->{
            switch(mode) {
                case "missingCity" -> ((com.fasterxml.jackson.databind.node.ArrayNode)tree.get("cities")).remove(0);
                case "missingCities" -> tree.remove("cities");
                case "missingInventory" -> tree.remove("inventory");
                case "missingBoundary" -> ((ObjectNode)tree.get("inventory").get(0)).putNull("closingAccountingValue");
            }
        });
        var totals=FolioProfitHistoryTotals.calculate("KYIV",List.of(revision(FolioProfitHistoryServiceTest.report("2025-07",false)),revision(august)));
        assertThat(totals.complete()).isFalse();
        if(mode.startsWith("missingCit")) assertThat(totals.profit()).isNull();
        else {
            assertThat(totals.profit()).isEqualByComparingTo("160");
            assertThat(totals.closingAccountingValue()).isNull();
            assertThat(totals.accountingValueChange()).isNull();
        }
    }

    @Test void explicitZeroAndProvisionalSavedValuesRemainVisible() throws Exception {
        var report=changed("2025-07",tree->{
            var city=(ObjectNode)tree.get("cities").get(0);
            for(String field:FLOWS) city.put(field,"0");
        });
        var complete=FolioProfitHistoryTotals.calculate("KYIV",List.of(revision(report)));
        assertThat(complete.profit()).isZero();
        assertThat(complete.complete()).isTrue();
        var provisional=new Revision(true,"PROVISIONAL","Paint_Ua",report.month(),2L,"id",2L,2L,
                "PROVISIONAL",true,false,null,null,null,report,null);
        var totals=FolioProfitHistoryTotals.calculate("KYIV",List.of(provisional));
        assertThat(totals.profit()).isZero();
        assertThat(totals.complete()).isFalse();
    }

    private FolioProfitReportResponse changed(String month,Consumer<ObjectNode> change) throws Exception {
        ObjectNode tree=JSON.valueToTree(FolioProfitHistoryServiceTest.report(month,false));
        change.accept(tree);
        return JSON.treeToValue(tree,FolioProfitReportResponse.class);
    }
    private Revision revision(FolioProfitReportResponse report) { return new Revision(true,"COMPLETED","Paint_Ua",report.month(),1L,"id",1L,1L,
            "COMPLETED",true,true,null,null,null,report,null); }
}
