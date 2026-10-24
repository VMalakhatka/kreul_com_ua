package org.example.proect.lavka.service.folio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.proect.lavka.dao.folio.FolioProfitScopeCatalogDao;
import org.example.proect.lavka.dao.wp.FolioProfitScopeSettingsDao;
import org.example.proect.lavka.dto.folio.*;
import org.example.proect.lavka.dto.folio.FolioProfitScopeCatalog.Warehouse;
import org.example.proect.lavka.property.FolioProfitReportProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FolioProfitScopeSettingsTest {
    private final FolioProfitScopeSettingsDao dao=mock(FolioProfitScopeSettingsDao.class);
    private final FolioProfitScopeCatalogDao source=mock(FolioProfitScopeCatalogDao.class);
    private final ObjectMapper json=new ObjectMapper();
    private final FolioProfitReportProperties properties=new FolioProfitReportProperties();
    private final FolioProfitScopeSettingsService service=new FolioProfitScopeSettingsService(dao,source,json,properties);
    @BeforeEach void catalog() {
        when(source.warehouses()).thenReturn(List.of(new Warehouse(1,"Киев"),new Warehouse(5,"Одесса"),new Warehouse(7,"Опт"),new Warehouse(12,"Хранение")));
        when(source.groupPaths()).thenReturn(List.of(List.of("A","Общая","C"),List.of("B","Общая"),List.of("A","Общая","C"),List.of("A","","Z"),List.of()));
    }
    private FolioProfitScopeSettings settings(long version,List<String> groups,List<String> subgroups) {
        return new FolioProfitScopeSettings(version,List.of(1,7),List.of(5),List.of(1,7,12),List.of(5),groups,subgroups);
    }
    @Test void defaultGetUsesConfigurationAndDoesNotReadFolioOrPersistDefaults() {
        properties.setKyivWarehouseIds(List.of(7));
        properties.setKyivStockWarehouseIds(List.of(12));
        assertThat(service.get()).isEqualTo(new FolioProfitScopeSettings(0L,List.of(7),List.of(5),List.of(12),List.of(5),List.of(),List.of()));
        verify(dao).get();verifyNoMoreInteractions(dao);verifyNoInteractions(source);
    }
    @Test void catalogPreservesParentsSameNamedBranchesAndInteriorBlanks() {
        var catalog=service.catalog();
        assertThat(catalog.groups()).extracting(FolioProfitScopeCatalog.Group::name).containsExactly("A","B");
        String a=FolioProfitGroupPath.id(List.of("A")),b=FolioProfitGroupPath.id(List.of("B"));
        String ac=FolioProfitGroupPath.id(List.of("A","Общая"));
        assertThat(catalog.subgroups()).hasSize(5);
        assertThat(catalog.subgroups()).filteredOn(g->g.name().equals("Общая")).hasSize(2)
                .extracting(FolioProfitScopeCatalog.Subgroup::groupId).containsExactly(a,b);
        assertThat(catalog.subgroups()).filteredOn(g->g.name().equals("C")).singleElement()
                .satisfies(g->{assertThat(g.groupId()).isEqualTo(a);assertThat(g.parentId()).isEqualTo(ac);assertThat(g.level()).isEqualTo(3);});
        assertThat(catalog.subgroups()).anySatisfy(g->assertThat(g.path()).containsExactly("A","","Z"));
        verifyNoInteractions(dao);
    }
    @Test void savingIsCasAndJsonRoundTripsAcrossServiceInstances() throws Exception {
        var selected=settings(4,List.of(FolioProfitGroupPath.id(List.of("A"))),List.of(FolioProfitGroupPath.id(List.of("B","Общая"))));
        when(dao.compareAndSet(eq(4L),anyString())).thenReturn(true,false);
        var saved=service.put(selected);
        assertThat(saved).isEqualTo(selected.withVersion(5));
        var payload=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(dao).compareAndSet(eq(4L),payload.capture());
        when(dao.get()).thenReturn(Optional.of(new FolioProfitScopeSettingsDao.Row(5,payload.getValue())));
        assertThat(new FolioProfitScopeSettingsService(dao,source,json,properties).get()).isEqualTo(saved);
        assertThatThrownBy(()->service.put(selected)).isInstanceOfSatisfying(FolioAccountConflictException.class,
                e->assertThat(e.getCode()).isEqualTo("PROFIT_SCOPE_SETTINGS_VERSION_CONFLICT"));
        verify(dao,times(2)).compareAndSet(eq(4L),anyString());
    }
    @Test void staleCalculationVersionStopsBeforeCatalogRead() {
        assertThatThrownBy(()->service.capture(2L)).isInstanceOfSatisfying(FolioAccountConflictException.class,
                e->assertThat(e.getCode()).isEqualTo("PROFIT_SCOPE_SETTINGS_VERSION_CONFLICT"));
        verify(dao).get();verifyNoMoreInteractions(dao);verifyNoInteractions(source);
    }
    @Test void capturedSelectionIncludesNamesAndParentsButNotOtherBranches() {
        var value=settings(3,List.of(),List.of(FolioProfitGroupPath.id(List.of("A","Общая","C"))));
        var catalog=service.catalog();
        var captured=new FolioProfitScopeSettingsService.Captured(value,catalog);
        var effective=FolioProfitScopeSettingsService.effective(captured,List.of(12),null);
        assertThat(value.kyivStockWarehouseIds()).containsExactly(1,7,12);
        assertThat(effective.kyivStockWarehouseIds()).containsExactly(12);
        assertThat(effective.version()).isEqualTo(3);
        var selected=FolioProfitScopeSettingsService.selectedCatalog(effective,catalog);
        assertThat(selected.groups()).extracting(FolioProfitScopeCatalog.Group::name).containsExactly("A");
        assertThat(selected.subgroups()).extracting(FolioProfitScopeCatalog.Subgroup::name).containsExactly("Общая","C");
        assertThat(selected.warehouses()).extracting(Warehouse::id).containsExactly(1,5,7,12);
        assertThat(FolioProfitScopeSettingsService.excludedPaths(effective)).containsExactly(List.of("A","Общая","C"));
    }
    @Test void unknownWarehouseAndWrongOrUnknownGroupCannotBeSilentlyIgnored() {
        var unknown=new FolioProfitScopeSettings(0L,List.of(999),List.of(5),List.of(1),List.of(5),List.of(),List.of());
        assertThatThrownBy(()->service.put(unknown)).isInstanceOfSatisfying(FolioAccountValidationException.class,
                e->assertThat(e.getCode()).isEqualTo("PROFIT_SCOPE_WAREHOUSE_NOT_FOUND"));
        for(var value:List.of(settings(0,List.of(FolioProfitGroupPath.id(List.of("absent"))),List.of()),
                settings(0,List.of(FolioProfitGroupPath.id(List.of("A","Общая"))),List.of()),
                settings(0,List.of(),List.of(FolioProfitGroupPath.id(List.of("A"))))))
            assertThatThrownBy(()->service.put(value)).isInstanceOfSatisfying(FolioAccountValidationException.class,
                    e->assertThat(e.getCode()).isEqualTo("PROFIT_SCOPE_GROUP_NOT_FOUND"));
        verifyNoInteractions(dao);
    }
    @Test void invalidStructureAndOverlapNeverReadOrWriteDatabase() {
        assertThatThrownBy(()->service.put(settings(Long.MAX_VALUE-1,List.of(),List.of())))
                .isInstanceOf(FolioAccountValidationException.class);
        for(var ids:Arrays.asList(null,List.<Integer>of(),List.of(1,1),List.of(0),Arrays.asList((Integer)null),Collections.nCopies(51,1)))
            assertThatThrownBy(()->service.put(new FolioProfitScopeSettings(0L,ids,List.of(5),List.of(1),List.of(5),List.of(),List.of())))
                    .isInstanceOf(FolioAccountValidationException.class);
        assertThatThrownBy(()->service.put(new FolioProfitScopeSettings(0L,List.of(1),List.of(1),List.of(1),List.of(5),List.of(),List.of())))
                .isInstanceOfSatisfying(FolioAccountValidationException.class,e->assertThat(e.getCode()).isEqualTo("PROFIT_SCOPE_WAREHOUSE_OVERLAP"));
        assertThatThrownBy(()->service.put(new FolioProfitScopeSettings(0L,List.of(1),List.of(5),List.of(1),List.of(1),List.of(),List.of())))
                .isInstanceOf(FolioAccountValidationException.class);
        assertThatThrownBy(()->service.put(settings(0,null,List.of()))).isInstanceOf(FolioAccountValidationException.class);
        assertThatThrownBy(()->service.put(settings(0,List.of("fake"),List.of()))).isInstanceOf(FolioAccountValidationException.class);
        verifyNoInteractions(dao,source);
    }
    @Test void unavailableOrCorruptStorageAndCatalogNeverBecomeEmptyDefaults() {
        when(dao.get()).thenReturn(Optional.of(new FolioProfitScopeSettingsDao.Row(1,"{}")));
        assertThatThrownBy(service::get).hasMessage("PROFIT_SCOPE_SETTINGS_STORAGE_INVALID");
        when(dao.get()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        assertThatThrownBy(service::get).isInstanceOf(org.springframework.dao.DataAccessException.class);
        when(source.groupPaths()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("offline"));
        assertThatThrownBy(service::catalog).isInstanceOf(org.springframework.dao.DataAccessException.class);
        verify(dao,never()).compareAndSet(anyLong(),anyString());
    }
    @Test void snapshotCopiesListsAndCompositeIdsAreCanonical() {
        var ids=new ArrayList<>(List.of(1));
        var value=new FolioProfitScopeSettings(0L,ids,List.of(5),ids,List.of(5),List.of(),List.of());ids.clear();
        assertThat(value.kyivWarehouseIds()).containsExactly(1);
        assertThatThrownBy(()->value.kyivWarehouseIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        var path=List.of("Материалы","","Бумага / \"x\"");
        assertThat(FolioProfitGroupPath.decode(FolioProfitGroupPath.id(path))).isEqualTo(path);
        assertThatThrownBy(()->FolioProfitGroupPath.decode(FolioProfitGroupPath.id(path)+"=")).isInstanceOf(FolioAccountValidationException.class);
    }
    @ParameterizedTest @ValueSource(strings={"1.5","true","\"1\"","-1","9223372036854775807"})
    void versionsAreStrictIntegers(String value) {
        assertThatThrownBy(()->json.readValue("{\"version\":"+value+"}",FolioProfitScopeSettings.class)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThatThrownBy(()->json.readValue("{\"expectedScopeSettingsVersion\":"+value+"}",FolioProfitSavedReports.CalculateRequest.class)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
    @ParameterizedTest @ValueSource(strings={"1.5","true","\"1\""})
    void warehouseIdsAreStrictIntegers(String value) {
        assertThatThrownBy(()->json.readValue("{\"kyivWarehouseIds\":["+value+"]}",FolioProfitScopeSettings.class)).isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
}
