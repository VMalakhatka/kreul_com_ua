package org.example.proect.lavka.service;

import org.example.proect.lavka.client.*;
import org.example.proect.lavka.dao.CardTovExportDao;
import org.example.proect.lavka.dao.wp.WpProductDao;
import org.example.proect.lavka.dto.*;
import org.example.proect.lavka.property.WooProperties;
import org.example.proect.lavka.service.category.WooCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SyncServiceUnitAttributeTest {
    WpProductDao wp; CardTovExportService cards; WooApiClient woo; LavkaLocationsClient locations;
    SyncServiceImpl service;
    static final long UNIT = 87; // synthetic target ID deliberately unrelated to any installation
    List<Map<String,Object>> other;
    @BeforeEach void setup() {
        wp=mock(WpProductDao.class); cards=mock(CardTovExportService.class); woo=mock(WooApiClient.class);
        locations=mock(LavkaLocationsClient.class);
        service=new SyncServiceImpl(wp,cards,woo,locations,new WooProperties());
        other=List.of(attr(15,"Brand",List.of("A","B"),3,false,true),attr(0,"Custom",List.of("X"),8,true,false));
        when(wp.collectSeenWindow(anyInt(),any())).thenReturn(List.of(new SeenItem("KR-79406","same-hash",100L)));
        when(woo.requireUnitAttributeId()).thenReturn(UNIT);
        when(woo.readProductAttributes(List.of(100L))).thenReturn(Map.of(100L,other));
        when(woo.upsertProductsBatch(anyMap())).thenReturn(new WooApiClient.WooBatchResult(1,1,0));
    }
    private void diff(List<CardTovExportOutDto> update,List<CardTovExportOutDto> create) {
        when(cards.diffPage(any(),anyInt(),anyList(),anyBoolean())).thenReturn(
                new CardTovExportService.DiffResult("KR-79406",true,update,List.of(),create));
    }
    @SuppressWarnings("unchecked") Map<String,Object> posted(String section) {
        var capture=org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(woo).upsertProductsBatch(capture.capture());
        return ((List<Map<String,Object>>)capture.getValue().get(section)).get(0);
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> attrs(Map<String,Object> product) {
        return (List<Map<String,Object>>)product.get("attributes");
    }
    @ParameterizedTest @ValueSource(strings={"250мл","125мл","2,5г","2  x  125мл"})
    void changedCardPreservesOtherFieldsAndAttributes(String unit) {
        diff(List.of(dto("KR-79406","  "+unit+"  ")),List.of());
        service.runOneBatch(10,200,null,false);
        var product=posted("update");
        assertThat(attrs(product).subList(0,2)).isEqualTo(other);
        assertThat(attrs(product).get(2)).containsEntry("id",UNIT).containsEntry("options",List.of(unit))
                .containsEntry("visible",true).containsEntry("variation",false).containsEntry("position",9);
        assertThat(product).containsEntry("id",100L).containsEntry("name","Test product")
                .containsEntry("weight","2.0").containsEntry("description","source description")
                .containsEntry("status","publish").containsEntry("catalog_visibility","visible")
                .containsEntry("dimensions",Map.of("length","1.0","width","3.0","height","4.0"))
                .containsEntry("categories",List.of(Map.of("id",12L)));
        assertThat(product.get("meta_data")).isEqualTo(List.of(
                Map.of("key","_ms_hash","value","same-hash"),Map.of("key","_wc_gtin_code","value","12345"),
                Map.of("key","_edin_izmer","value",unit),Map.of("key","_razm_izmer","value","box"),
                Map.of("key","_ves_edinic","value","2.000")));
        verify(cards,never()).findUnitsForSync(any());
    }
    @Test void newCardUsesTargetGlobalIdAndKeepsLegacyMeta() {
        diff(List.of(),List.of(dto("KR-49602","125мл")));
        when(cards.findUnitsForSync(any())).thenReturn(Map.of());
        service.runOneBatch(10,200,null,false);
        var product=posted("create");
        assertThat(product).containsEntry("sku","KR-49602").doesNotContainKey("id");
        assertThat(attrs(product)).hasSize(1);
        assertThat(attrs(product).get(0)).containsEntry("id",UNIT).containsEntry("options",List.of("125мл"));
        assertThat((List<?>)product.get("meta_data")).anyMatch(v -> v.equals(Map.of("key","_edin_izmer","value","125мл")));
    }
    @Test void unitOnlyBackfillForHashUnchangedHasNoOtherFieldsAndRepeatSkips() {
        diff(List.of(),List.of());
        when(cards.findUnitsForSync(List.of("KR-79406"))).thenReturn(Map.of("KR-79406","250мл"));
        service.runOneBatch(10,200,null,false);
        var product=posted("update");
        assertThat(product).containsOnlyKeys("id","attributes","meta_data");
        assertThat(product.get("meta_data")).isEqualTo(List.of(Map.of("key","_edin_izmer","value","250мл")));
        assertThat(attrs(product).subList(0,2)).isEqualTo(other);
        verify(cards).diffPage(isNull(),eq(10),eq(List.of(new CardTovExportService.ItemHash("KR-79406","same-hash"))),eq(false));
        verifyNoInteractions(locations);
        clearInvocations(woo,cards);
        when(woo.readProductAttributes(List.of(100L))).thenReturn(Map.of(100L,attrs(product)));
        service.runOneBatch(10,200,null,false);
        verify(woo,never()).upsertProductsBatch(any());
        verify(cards,never()).findUnitsForSync(any());
    }
    @Test void emptyTargetIsFilledWithoutChangingItsFlagsAndPosition() {
        var empty=attr(UNIT,"Одиниця виміру",List.of(),11,false,true);
        when(woo.readProductAttributes(List.of(100L))).thenReturn(Map.of(100L,List.of(other.get(0),empty)));
        diff(List.of(),List.of()); when(cards.findUnitsForSync(any())).thenReturn(Map.of("KR-79406","2,5г"));
        service.runOneBatch(10,200,null,false);
        assertThat(attrs(posted("update")).get(1)).containsEntry("position",11).containsEntry("visible",false)
                .containsEntry("variation",true).containsEntry("options",List.of("2,5г"));
        assertThat(empty.get("options")).isEqualTo(List.of());
    }
    @Test void forceRefreshChangesOnlyUnitOptionsWithinExistingAttributes() {
        when(woo.readProductAttributes(List.of(100L))).thenReturn(Map.of(100L,List.of(
                other.get(0),attr(UNIT,"Одиниця виміру",List.of("125мл"),7,false,false))));
        diff(List.of(dto("KR-79406","250мл")),List.of());
        service.forceRefreshOneBatch(10,200,null,false);
        var updated=attrs(posted("update"));
        assertThat(updated).hasSize(2);
        assertThat(updated.get(1)).containsEntry("options",List.of("250мл"))
                .containsEntry("position",7).containsEntry("visible",false).containsEntry("variation",false);
        verify(cards,never()).findUnitsForSync(any());
        verify(cards).diffPage(any(),anyInt(),anyList(),eq(true));
    }
    @Test void hashUnchangedNonemptyUnitIsNotCorrectedByBackfill() {
        diff(List.of(),List.of());
        when(woo.readProductAttributes(List.of(100L))).thenReturn(Map.of(100L,List.of(
                attr(UNIT,"Одиниця виміру",List.of("125мл"),0,true,false))));
        service.runOneBatch(10,200,null,false);
        verify(cards,never()).findUnitsForSync(any()); verify(woo,never()).upsertProductsBatch(any());
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"   "})
    void emptySourceDoesNotSendAttributesOrLegacyUnit(String value) {
        diff(List.of(dto("KR-79406",value)),List.of());
        service.runOneBatch(10,200,null,false);
        var product=posted("update");
        assertThat(product).doesNotContainKey("attributes");
        assertThat(product.get("meta_data").toString()).doesNotContain("_edin_izmer");
    }
    @Test void absentSourceForUnchangedCardAndDryRunDoNotWrite() {
        diff(List.of(),List.of()); when(cards.findUnitsForSync(any())).thenReturn(Map.of());
        service.runOneBatch(10,200,null,false); verify(woo,never()).upsertProductsBatch(any());
        when(cards.findUnitsForSync(any())).thenReturn(Map.of("KR-79406","250мл"));
        service.runOneBatch(10,200,null,true); verify(woo,never()).upsertProductsBatch(any());
    }
    @Test void missingGlobalAttributeFailsBeforeDiffOrMutation() {
        when(woo.requireUnitAttributeId()).thenThrow(new IllegalStateException("WOO_UNIT_ATTRIBUTE_MISSING"));
        assertThatThrownBy(()->service.runOneBatch(10,200,null,false)).hasMessage("WOO_UNIT_ATTRIBUTE_MISSING");
        verifyNoInteractions(cards,locations); verify(woo,never()).upsertProductsBatch(any());
    }
    @Test void unitLookupDoesNotMapCategoriesOrChangeSourceValues() {
        var dao=mock(CardTovExportDao.class); var categories=mock(WooCategoryService.class);
        var real=new CardTovExportService(dao,categories,wp);
        var a=new CardTovExportDto(); a.setSku("KR-79406"); a.setEDIN_IZMER("  2  x  125мл  ");
        var b=new CardTovExportDto(); b.setSku("KR-49602"); b.setEDIN_IZMER(" ");
        when(dao.findBySkus(any())).thenReturn(List.of(a,b));
        assertThat(real.findUnitsForSync(List.of("KR-79406","KR-49602"))).containsExactlyEntriesOf(Map.of("KR-79406","2  x  125мл"));
        verifyNoInteractions(categories);
    }
    static Map<String,Object> attr(long id,String name,List<String> options,int position,boolean visible,boolean variation) {
        return Map.of("id",id,"name",name,"options",options,"position",position,"visible",visible,"variation",variation);
    }
    static CardTovExportOutDto dto(String sku,String unit) {
        return new CardTovExportOutDto(sku," Test product ",null,unit," 12345 ",2d,1d,3d,4d,
                1,2d,"source description"," box ",null,12L,"same-hash");
    }
}
