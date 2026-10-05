package org.example.proect.lavka.controller;

import org.example.proect.lavka.dao.folio.FolioReceiptCatalogueDao;
import org.example.proect.lavka.dao.stock.MsWarehouseDao;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.sql.Timestamp;
import java.sql.ResultSet;
import java.math.BigDecimal;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import java.util.stream.LongStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FolioReceiptCatalogueTest {
    private final FolioReceiptCatalogueDao dao=mock(FolioReceiptCatalogueDao.class);
    private final FolioReceiptCatalogueController controller=new FolioReceiptCatalogueController(dao,mock(MsWarehouseDao.class));
    private final LocalDate day=LocalDate.of(2026,10,5);
    @Test void pagingDoesNotLoseTheLastDocument() {
        var rows=LongStream.rangeClosed(1,101).mapToObj(i->new FolioReceiptCatalogueDao.Document(i,"R"+i,day,7,"receipt",true)).toList();
        when(dao.documents(7,day,0,"all")).thenReturn(rows);
        var page=controller.documents(7,day,0,"all");
        assertThat((List<?>)page.get("documents")).hasSize(100);
        assertThat(page).containsEntry("hasMore",true).containsEntry("nextAfterId",100L);
    }
    @Test void rejectsInvalidSelectionsBeforeQuerying() {
        assertThatThrownBy(()->controller.documents(0,day,0,"all")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller.documents(7,day,-1,"all")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller.skus(0,7,day,"all")).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(dao);
    }
    @Test void missingOrOversizedReceiptCannotProduceAnAnnouncement() {
        when(dao.skus(9,7,day,"invoice")).thenReturn(List.of());
        assertThatThrownBy(()->controller.skus(9,7,day,"invoice")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        when(dao.skus(9,7,day,"invoice")).thenReturn(LongStream.range(0,2001).mapToObj(Long::toString).toList());
        assertThatThrownBy(()->controller.skus(9,7,day,"invoice")).isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
    }
    @Test void onlyPublicSkuSelectionFieldsAreReturned() {
        when(dao.skus(9,7,day,"invoice")).thenReturn(List.of("00123","АБВ"));
        assertThat(controller.skus(9,7,day,"invoice")).containsOnlyKeys("ok","documentId","warehouseId","date","skus","documentType","documentTypes")
                .containsEntry("skus",List.of("00123","АБВ"));
    }
    @Test void httpSelectionAndLegacyDefaultBindCorrectly() throws Exception {
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        when(dao.documents(eq(7),eq(day),eq(0L),anyString())).thenReturn(List.of());
        mvc.perform(get("/admin/folio/receipt-catalogue").param("warehouseId","7").param("date",day.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.documentType").value("receipt"));
        mvc.perform(get("/admin/folio/receipt-catalogue").param("warehouseId","7").param("date",day.toString()).param("documentType","all"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.documentTypes[1]").value("invoice"));
        mvc.perform(get("/admin/folio/receipt-catalogue").param("warehouseId","7").param("date",day.toString()).param("documentType","payment"))
                .andExpect(status().isBadRequest());
        verify(dao).documents(7,day,0,"receipt");verify(dao).documents(7,day,0,"all");verifyNoMoreInteractions(dao);
    }
    @Test @SuppressWarnings("unchecked") void skuQueriesScopeBothWarehousesAndTheWholeDayForEveryType() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(invocation->{
            String sql=invocation.getArgument(0);
            assertThat(sql).contains("n.UNICUM_NUM=?","n.TYPE_DOC IN (?,?)","(n.TYPE_DOC=? OR n.STND_UCHET=1)",
                    "n.ID_SCLAD=?","m.ID_SCLAD=?","(n.TYPE_DOC=? OR m.STND_UCHET=1)","n.DATE_P_POR>=?","n.DATE_P_POR<?",
                    "ISNULL(n.VOZVRAT_PR,0)=0","ISNULL(m.VOZVRAT_PR,0)=0","m.KOLC_PREDM>0")
                    .doesNotContain("CENA","SUM_PREDM","NOLOCK","OFFSET","ROW_NUMBER","INSERT","UPDATE");
            Object[] args=java.util.Arrays.copyOfRange(invocation.getArguments(),2,invocation.getArguments().length);
            assertThat(args[0]).isEqualTo(9L);assertThat(args[3]).isEqualTo("С");
            assertThat(args[4]).isEqualTo(7);assertThat(args[5]).isEqualTo(Timestamp.valueOf(day.atStartOfDay()));
            assertThat(args[6]).isEqualTo(Timestamp.valueOf(day.plusDays(1).atStartOfDay()));assertThat(args[7]).isEqualTo(7);
            assertThat(args[8]).isEqualTo("С");
            return List.of(args[1]+":"+args[2]);
        });
        var reader=new FolioReceiptCatalogueDao(jdbc);
        assertThat(reader.skus(9,7,day,"receipt")).containsExactly("П:П");
        assertThat(reader.skus(9,7,day,"invoice")).containsExactly("С:С");
        assertThat(reader.skus(9,7,day,"all")).containsExactly("П:С");
        assertThatThrownBy(()->reader.skus(9,7,day,"payment")).isInstanceOf(IllegalArgumentException.class);
        verify(jdbc,times(3)).query(anyString(),any(RowMapper.class),any(Object[].class));
    }
    @Test @SuppressWarnings("unchecked") void invoicePickerPreservesNonAccountingFlagAndVisibleNumber() throws Exception {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);ResultSet rs=mock(ResultSet.class);
        when(rs.getLong("UNICUM_NUM")).thenReturn(901L);when(rs.getBigDecimal("N_PLAT_POR")).thenReturn(new BigDecimal("555.00"));
        when(rs.getString("DOPN_SCHET")).thenReturn(" А ");when(rs.getTimestamp("DATE_P_POR")).thenReturn(Timestamp.valueOf(day.atTime(23,59)));
        when(rs.getInt("ID_SCLAD")).thenReturn(7);when(rs.getString("TYPE_DOC")).thenReturn("С ");when(rs.getBoolean("STND_UCHET")).thenReturn(false);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(invocation->{
            String sql=invocation.getArgument(0);
            assertThat(sql).contains("n.TYPE_DOC IN (?,?)","(n.TYPE_DOC=? OR n.STND_UCHET=1)","n.UNICUM_NUM>?").doesNotContain("CENA","SUM_POR","ORGANIZ");
            RowMapper<FolioReceiptCatalogueDao.Document> mapper=invocation.getArgument(1);
            return List.of(mapper.mapRow(rs,0));
        });
        assertThat(new FolioReceiptCatalogueDao(jdbc).documents(7,day,900,"invoice"))
                .containsExactly(new FolioReceiptCatalogueDao.Document(901,"555А",day,7,"invoice",false));
    }
}
