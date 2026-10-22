package org.example.proect.lavka.dao;

import org.example.proect.lavka.dao.mapper.CardTovExportRowMapper;
import org.example.proect.lavka.dto.CardTovExportDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CardTovSupplierTest {
    NamedParameterJdbcTemplate jdbc;
    CardTovExportDaoImpl dao;
    List<CardTovExportDto> cards;
    List<String[]> suppliers;

    @BeforeEach void setup() throws Exception {
        jdbc = mock(NamedParameterJdbcTemplate.class); dao = new CardTovExportDaoImpl(jdbc);
        var card = new CardTovExportDto(); card.setSku("P-141515"); cards = List.of(card);
        suppliers = Collections.singletonList(new String[]{"P-141515", "Pebeo France"});
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), eq(CardTovExportRowMapper.M)))
                .thenAnswer(i -> cards);
        doAnswer(i -> {
            String sql = i.getArgument(0); MapSqlParameterSource params = i.getArgument(1);
            assertThat(sql).contains("ID_SCLAD = 7", "COD_ARTIC IN (:skus)").doesNotContain("P-141515");
            assertThat((Collection<?>) params.getValue("skus")).hasSizeLessThanOrEqualTo(1000);
            RowCallbackHandler callback = i.getArgument(2);
            for (var values : suppliers) {
                var rs = mock(ResultSet.class);
                when(rs.getString("COD_ARTIC")).thenReturn(values[0]);
                when(rs.getString("DOP2_ARTIC")).thenReturn(values[1]);
                callback.processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));
    }

    @Test void everyPageAndDiffReadGetsSupplierFromSameWarehouse() {
        var results = List.of(dao.findPage(null, 1), dao.findBySkus(List.of("P-141515")),
                dao.findBetweenExcluding("P-141514", "P-141516", List.of(), 2),
                dao.findBetweenExcluding("P-141514", "P-141516", List.of("other"), 2),
                dao.findLessThanExcluding("P-141516", List.of(), 1), dao.findGreaterThan("P-141514", 1));
        for (var result : results) assertThat(result.get(0).getSupplier()).isEqualTo("Pebeo France");
        verify(jdbc, times(6)).query(anyString(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));
    }

    @Test void confirmedEmptySupplierIsDifferentFromMissingRow() {
        suppliers = Collections.singletonList(new String[]{"P-141515   ", null});
        assertThat(dao.findPage(null, 1).get(0).getSupplier()).isEmpty();
        suppliers = List.of();
        assertThatThrownBy(() -> dao.findPage(null, 1)).hasMessage("FOLIO_SUPPLIER_UNAVAILABLE");
    }

    @Test void duplicateWarehouseIdentityDoesNotChooseAnArbitrarySupplier() {
        suppliers = List.of(new String[]{"P-141515", "First"}, new String[]{"P-141515 ", "Second"});
        assertThatThrownBy(() -> dao.findPage(null, 1)).hasMessage("FOLIO_SUPPLIER_AMBIGUOUS");
    }

    @Test void emptyPageDoesNotQuerySuppliers() {
        cards = List.of();
        assertThat(dao.findPage(null, 1)).isEmpty();
        verify(jdbc, never()).query(anyString(), any(MapSqlParameterSource.class), any(RowCallbackHandler.class));
    }
}
