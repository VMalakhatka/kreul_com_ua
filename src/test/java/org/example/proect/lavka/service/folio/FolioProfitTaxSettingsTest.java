package org.example.proect.lavka.service.folio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.proect.lavka.dao.wp.FolioProfitTaxSettingsDao;
import org.example.proect.lavka.dto.folio.FolioProfitTaxSettings;
import org.example.proect.lavka.dto.folio.FolioProfitSavedReports.CalculateRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FolioProfitTaxSettingsTest {
    private final FolioProfitTaxSettingsDao dao=mock(FolioProfitTaxSettingsDao.class);
    private final ObjectMapper json=new ObjectMapper();
    private final FolioProfitTaxSettingsService service=new FolioProfitTaxSettingsService(dao,json);

    @Test void absentSettingsReturnDefaultsWithoutWriting() {
        when(dao.get()).thenReturn(Optional.empty());
        assertThat(service.get()).isEqualTo(FolioProfitTaxSettings.defaults());
        verify(dao).get(); verifyNoMoreInteractions(dao);
    }
    @Test void completeReplacementIsNormalizedVersionedAndAllowsEmptyLists() {
        when(dao.compareAndSet(anyLong(),anyString(),anyString())).thenReturn(true);
        var saved=service.put(new FolioProfitTaxSettings(8L,List.of(" новафоп ","malafop"),List.of()));
        assertThat(saved.version()).isEqualTo(9);
        assertThat(saved.retailFirmCodes()).containsExactly("НОВАФОП","МАЛАФОП");
        assertThat(saved.wholesaleFirmCodes()).isEmpty();
        verify(dao).compareAndSet(8L,"[\"НОВАФОП\",\"МАЛАФОП\"]","[]");
        when(dao.get()).thenReturn(Optional.of(new FolioProfitTaxSettingsDao.Row(9L,"[\"НОВАФОП\",\"МАЛАФОП\"]","[]")));
        assertThat(service.get()).isEqualTo(saved);
    }
    @Test void optimisticConflictNeverOverwritesOrRetries() {
        when(dao.compareAndSet(anyLong(),anyString(),anyString())).thenReturn(false);
        assertThatThrownBy(()->service.put(FolioProfitTaxSettings.defaults()))
                .isInstanceOfSatisfying(FolioAccountConflictException.class,
                        e->assertThat(e.getCode()).isEqualTo("PROFIT_TAX_SETTINGS_VERSION_CONFLICT"));
        verify(dao,times(1)).compareAndSet(anyLong(),anyString(),anyString());
        verifyNoMoreInteractions(dao);
    }
    @Test void overlappingFirmOrHistoricalAliasCannotBelongToBothLists() {
        for(String code:List.of("МАЛАФОП","malafop"))
            assertThatThrownBy(()->service.put(new FolioProfitTaxSettings(0L,List.of("МАЛАФОП"),List.of(code))))
                    .isInstanceOfSatisfying(FolioAccountValidationException.class,
                            e->assertThat(e.getCode()).isEqualTo("PROFIT_TAX_FIRM_OVERLAP"));
        verifyNoInteractions(dao);
    }
    @Test void invalidInputNeverReachesDatabase() {
        var invalid=new ArrayList<FolioProfitTaxSettings>();
        invalid.add(null);
        invalid.add(new FolioProfitTaxSettings(null,List.of(),List.of()));
        invalid.add(new FolioProfitTaxSettings(-1L,List.of(),List.of()));
        invalid.add(new FolioProfitTaxSettings(Long.MAX_VALUE,List.of(),List.of()));
        invalid.add(new FolioProfitTaxSettings(0L,null,List.of()));
        invalid.add(new FolioProfitTaxSettings(0L,List.of(),null));
        for(var codes:List.of(Arrays.asList((String)null),List.of(""),List.of("A B"),List.of("A".repeat(65)),
                List.of("ФОП"," фоп "),List.of("МАЛАФОП","MALAFOP"),Collections.nCopies(101,"A")))
            invalid.add(new FolioProfitTaxSettings(0L,codes,List.of()));
        for(var value:invalid) assertThatThrownBy(()->service.put(value)).isInstanceOf(FolioAccountValidationException.class);
        verifyNoInteractions(dao);
    }
    @Test void corruptOrUnavailableStorageNeverFallsBackToDefaults() {
        when(dao.get()).thenReturn(Optional.of(new FolioProfitTaxSettingsDao.Row(2L,"broken","[]")));
        assertThatThrownBy(service::get).hasMessage("PROFIT_TAX_SETTINGS_STORAGE_INVALID");
        when(dao.get()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private"));
        assertThatThrownBy(service::get).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void snapshotListsAreImmutableCopies() {
        var codes=new ArrayList<>(List.of("ФОП"));
        var settings=new FolioProfitTaxSettings(0L,codes,List.of()); codes.clear();
        assertThat(settings.retailFirmCodes()).containsExactly("ФОП");
        assertThatThrownBy(()->settings.retailFirmCodes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @ParameterizedTest @ValueSource(strings={"1.5","true","\"2\"","-1","9223372036854775808","9223372036854775807"})
    void malformedVersionsAreNotSilentlyCoerced(String value) {
        assertThatThrownBy(()->json.readValue("{\"version\":"+value+"}",FolioProfitTaxSettings.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
        assertThatThrownBy(()->json.readValue("{\"taxSettingsVersion\":"+value+"}",CalculateRequest.class))
                .isInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);
    }
}
