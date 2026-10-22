package org.example.proect.lavka.service;

import org.example.proect.lavka.dao.CardTovExportDao;
import org.example.proect.lavka.dao.wp.WpProductDao;
import org.example.proect.lavka.dto.CardTovExportDto;
import org.example.proect.lavka.dto.CardTovExportOutDto;
import org.example.proect.lavka.service.category.WooCategoryService;
import org.example.proect.lavka.utils.FullSyncHash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.RecordComponent;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FullSyncHashTest {
    static CardTovExportOutDto card() {
        return new CardTovExportOutDto("P-141515", "Paint", "paint.jpg", "80ml", "12345",
                1d, 2d, 3d, 4d, 1, 5d, "Description", "Box", "Group description", 10L,
                "Pebeo France", "old");
    }

    static Stream<String> fields() {
        return Arrays.stream(CardTovExportOutDto.class.getRecordComponents())
                .map(RecordComponent::getName).filter(n -> !n.equals("hash"));
    }

    static CardTovExportOutDto change(CardTovExportOutDto value, String field, Object replacement) throws Exception {
        var components = CardTovExportOutDto.class.getRecordComponents();
        Object[] values = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            values[i] = components[i].getName().equals(field) ? replacement : components[i].getAccessor().invoke(value);
        }
        return CardTovExportOutDto.class.getDeclaredConstructor(Arrays.stream(components)
                .map(RecordComponent::getType).toArray(Class<?>[]::new)).newInstance(values);
    }

    @ParameterizedTest @MethodSource("fields")
    void everyExportedFieldChangesHash(String field) throws Exception {
        var component = Arrays.stream(CardTovExportOutDto.class.getRecordComponents())
                .filter(c -> c.getName().equals(field)).findFirst().orElseThrow();
        Object replacement = component.getType() == String.class ? "Changed|\n\"value\""
                : component.getType() == Double.class ? (Object) 99.125d
                : component.getType() == Integer.class ? (Object) 7 : 123L;
        assertThat(FullSyncHash.calculate(change(card(), field, replacement)))
                .isNotEqualTo(FullSyncHash.calculate(card()));
        assertThat(FullSyncHash.calculate(change(card(), field, null)))
                .isNotEqualTo(FullSyncHash.calculate(card()));
    }

    @Test void deterministicAndNotSelfReferential() throws Exception {
        String hash = FullSyncHash.calculate(card());
        assertThat(hash).matches("[0-9a-f]{64}");
        assertThat(FullSyncHash.calculate(change(card(), "hash", hash))).isEqualTo(hash);
        assertThat(FullSyncHash.calculate(change(card(), "hash", null))).isEqualTo(hash);
    }

    @Test void separatorsNullsAndWhitespaceCannotHideAChange() throws Exception {
        var a = change(change(card(), "description", "one|two"), "razmIzmer", "three");
        var b = change(change(card(), "description", "one"), "razmIzmer", "two|three");
        assertThat(FullSyncHash.calculate(a)).isNotEqualTo(FullSyncHash.calculate(b));
        assertThat(FullSyncHash.calculate(change(card(), "description", " Description ")))
                .isNotEqualTo(FullSyncHash.calculate(card()));
        assertThat(FullSyncHash.calculate(change(card(), "status", null)))
                .isNotEqualTo(FullSyncHash.calculate(change(card(), "status", 0)));
        assertThat(FullSyncHash.calculate(change(card(), "supplier", null)))
                .isNotEqualTo(FullSyncHash.calculate(change(card(), "supplier", "")));
    }

    @Test void supplierChangeAndLegacyHashTriggerDiffAndNextUnchangedPassSkips() {
        var dao = mock(CardTovExportDao.class);
        var service = new CardTovExportService(dao, mock(WooCategoryService.class), mock(WpProductDao.class));
        var row = new CardTovExportDto(); row.setSku("P-141515"); row.setName("Paint"); row.setSupplier("Pebeo France");
        when(dao.findBySkus(anyCollection())).thenReturn(List.of(row));
        var first = service.diffPage(null, 10, List.of(new CardTovExportService.ItemHash(row.getSku(), "legacy-hash")));
        assertThat(first.toUpdateFull()).hasSize(1);
        var output = first.toUpdateFull().get(0);
        assertThat(output.supplier()).isEqualTo("Pebeo France");
        var seen = List.of(new CardTovExportService.ItemHash(row.getSku(), output.hash()));
        assertThat(service.diffPage(null, 10, seen).toUpdateFull()).isEmpty();
        row.setSupplier("Other supplier");
        assertThat(service.diffPage(null, 10, seen).toUpdateFull()).hasSize(1);
        row.setSupplier("");
        assertThat(service.diffPage(null, 10, seen).toUpdateFull()).singleElement()
                .extracting(CardTovExportOutDto::supplier).isEqualTo("");
        when(dao.findPage(null, 10)).thenReturn(List.of(row));
        assertThat(service.diffPage(null, 10, List.of()).toCreateFull()).hasSize(1);
    }
}
