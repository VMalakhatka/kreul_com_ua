package org.example.proect.lavka.service.folio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao.*;
import org.example.proect.lavka.dto.folio.*;
import org.example.proect.lavka.dto.folio.FolioProfitScopeCatalog.*;
import org.example.proect.lavka.property.FolioProfitReportProperties;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FolioProfitScopeReportTest {
    private final FolioProfitReportDao dao=mock(FolioProfitReportDao.class);
    private final FolioProfitScopeSettingsService settings=mock(FolioProfitScopeSettingsService.class);
    private final FolioProfitReportService service=new FolioProfitReportService(dao,new FolioProfitClassifier(),new FolioProfitReportProperties(),null,settings);
    private final FolioProfitReportService.Request request=new FolioProfitReportService.Request("2026-07",null,null,null,null,BigDecimal.ZERO,null,null);
    private final String groupId=FolioProfitGroupPath.id(List.of("Материалы"));
    private final FolioProfitScopeCatalog catalog=new FolioProfitScopeCatalog(List.of(new Warehouse(1,"Киев"),new Warehouse(5,"Одесса"),new Warehouse(7,"Опт"),new Warehouse(12,"Хранение")),
            List.of(new Group(groupId,"Материалы",List.of("Материалы"))),List.of());
    @BeforeEach void source() {
        when(dao.findWarehouseNames(any())).thenReturn(Map.of(1,"Киев",5,"Одесса",7,"Опт",12,"Хранение"));
        when(dao.masterClassArticleExists(anyString())).thenReturn(true);
        when(dao.findGrossMargins(any(),any())).thenReturn(List.of(new GrossMarginRow(1,"",false,true,1,bd("100")),
                new GrossMarginRow(5,"",false,true,1,bd("200")),new GrossMarginRow(7,"",false,true,1,bd("50"))));
        when(dao.findMasterClassMovements(eq(1),anyString(),any(),any())).thenReturn(List.of(mk(1,"100","40")));
        when(dao.findMasterClassMovements(eq(5),anyString(),any(),any())).thenReturn(List.of(mk(5,"200","70")));
    }
    private FolioProfitScopeSettings scopes(List<Integer> kyiv,List<Integer> odesa,List<String> exclusions) {
        return new FolioProfitScopeSettings(4L,kyiv,odesa,List.of(1),List.of(5),exclusions,List.of());
    }
    private FolioProfitReportResponse calculate(FolioProfitScopeSettings scope) {
        when(settings.capture(null)).thenReturn(new FolioProfitScopeSettingsService.Captured(scope,catalog));
        return service.calculate(request,true);
    }
    @Test void fixedMasterClassWarehousesAreRemovedFromActualSalesCityOnlyOnce() {
        var normal=calculate(scopes(List.of(1,7),List.of(5),List.of()));
        assertThat(normal.cities().get(0).grossProfit()).isEqualByComparingTo("190");
        assertThat(normal.cities().get(1).grossProfit()).isEqualByComparingTo("270");
        var cross=calculate(scopes(List.of(5,7),List.of(1),List.of()));
        assertThat(cross.cities().get(0).manualGrossAdjustments()).isEqualByComparingTo("-30");
        assertThat(cross.cities().get(1).manualGrossAdjustments()).isEqualByComparingTo("140");
        assertThat(cross.cities().get(0).grossProfit()).isEqualByComparingTo("220");
        assertThat(cross.cities().get(1).grossProfit()).isEqualByComparingTo("240");
        assertThat(cross.masterClassesByCity().get("KYIV").warehouseId()).isEqualTo(1);
        assertThat(cross.masterClassesByCity().get("ODESA").warehouseId()).isEqualTo(5);
        var both=calculate(scopes(List.of(1,5),List.of(7),List.of()));
        assertThat(both.cities().get(0).grossProfit()).isEqualByComparingTo("210");
        assertThat(both.cities().get(1).grossProfit()).isEqualByComparingTo("250");
        var neither=calculate(scopes(List.of(7),List.of(12),List.of()));
        assertThat(neither.cities().get(0).grossProfit()).isEqualByComparingTo("150");
        assertThat(neither.cities().get(1).grossProfit()).isEqualByComparingTo("200");
    }
    @Test void groupExclusionsChangeOnlyInventoryAmountsAndCountsAndSnapshotLabels() throws Exception {
        when(dao.findInventoryOpenings(any())).thenReturn(List.of(new InventoryOpeningRow(1,"NEG",bd("-1"),bd("10"))));
        var original=calculate(scopes(List.of(1,7),List.of(5),List.of()));
        when(dao.findInventoryOpenings(any(),any())).thenAnswer(a->{
            int warehouse=((List<Integer>)a.getArgument(0)).get(0);
            return List.of(new InventoryOpeningRow(warehouse,"KEEP",bd("2"),bd("10")));
        });
        when(dao.findInventoryMovements(any(),any(),any(),any())).thenAnswer(a->{
            int warehouse=((List<Integer>)a.getArgument(0)).get(0);
            return List.of(new InventoryMovementRow(warehouse,"KEEP",bd("1"),bd("10"),bd("2"),bd("20")));
        });
        var selected=scopes(List.of(1,7),List.of(5),List.of(groupId));
        var report=calculate(selected);
        assertThat(report.cities()).isEqualTo(original.cities());
        assertThat(report.expenses()).isEqualTo(original.expenses());
        assertThat(report.masterClassesByCity()).isEqualTo(original.masterClassesByCity());
        assertThat(report.grossProfitLines()).isEqualTo(original.grossProfitLines());
        assertThat(report.inventory()).allSatisfy(row->{
            assertThat(row.openingAccountingValue()).isEqualByComparingTo("30");
            assertThat(row.closingAccountingValue()).isEqualByComparingTo("40");
            assertThat(row.openingPositionCount()).isEqualTo(1);assertThat(row.closingPositionCount()).isEqualTo(1);
            assertThat(row.negativeClosingPositionCount()).isZero();
        });
        assertThat(report.scopeSettings()).isEqualTo(selected);
        assertThat(report.scopeCatalog().groups()).extracting(Group::name).containsExactly("Материалы");
        assertThat(report.inputs().kyivWarehouseIds()).containsExactly(1,7);
        verify(dao).findInventoryOpenings(List.of(1),List.of(List.of("Материалы")));
        verify(dao).findInventoryOpenings(List.of(5),List.of(List.of("Материалы")));
        verify(dao).findInventoryMovements(List.of(1),LocalDate.of(2026,7,1),LocalDate.of(2026,8,1),List.of(List.of("Материалы")));
        String fixture=System.getProperty("folio.profit.scope.fixture.output");
        if(fixture!=null) java.nio.file.Files.writeString(java.nio.file.Path.of(fixture),new ObjectMapper().findAndRegisterModules()
                .writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
    @Test void settingsCapturedOnceAndLegacyStockOverrideDoesNotWriteOrReRead() {
        var initial=scopes(List.of(1,7),List.of(5),List.of(groupId));
        var captured=new FolioProfitScopeSettingsService.Captured(initial,catalog);
        var override=new FolioProfitReportService.Request("2026-07",null,null,null,null,BigDecimal.ZERO,List.of(12),null);
        var report=service.calculate(override,true,FolioProfitTaxSettings.defaults(),captured);
        assertThat(report.scopeSettings().kyivStockWarehouseIds()).containsExactly(12);
        assertThat(report.scopeSettings().excludedInventoryGroupIds()).containsExactly(groupId);
        assertThat(report.scopeSettings().version()).isEqualTo(4);
        assertThat(initial.kyivStockWarehouseIds()).containsExactly(1);
        assertThat(report.inputs().kyivStockWarehouseIds()).containsExactly(12);
        verifyNoInteractions(settings);
    }
    @Test void invalidMonthDoesNotReadSettingsOrFolioCatalog() {
        var invalid=new FolioProfitReportService.Request("bad",null,null,null,null,null,null,null);
        assertThatThrownBy(()->service.calculate(invalid,true)).isInstanceOf(FolioAccountValidationException.class);
        verifyNoInteractions(settings,dao);
    }
    private static BigDecimal bd(String value) {return new BigDecimal(value);}
    private static MasterClassMovementRow mk(int warehouse,String amount,String cost) {
        return new MasterClassMovementRow(warehouse,"synthetic-"+warehouse,"1","",1,LocalDate.of(2026,7,10),warehouse,
                "Мастер-Класс июль","Р","Р","",false,false,true,true,BigDecimal.ONE,bd(amount),bd(amount),bd(cost),"");
    }
}
