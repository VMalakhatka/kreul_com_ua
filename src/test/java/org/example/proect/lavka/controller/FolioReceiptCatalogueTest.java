package org.example.proect.lavka.controller;

import org.example.proect.lavka.dao.folio.FolioReceiptCatalogueDao;
import org.example.proect.lavka.dao.stock.MsWarehouseDao;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
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
        var rows=LongStream.rangeClosed(1,101).mapToObj(i->new FolioReceiptCatalogueDao.Document(i,"R"+i,day,7)).toList();
        when(dao.documents(7,day,0)).thenReturn(rows);
        var page=controller.documents(7,day,0);
        assertThat((List<?>)page.get("documents")).hasSize(100);
        assertThat(page).containsEntry("hasMore",true).containsEntry("nextAfterId",100L);
    }
    @Test void rejectsInvalidSelectionsBeforeQuerying() {
        assertThatThrownBy(()->controller.documents(0,day,0)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller.documents(7,day,-1)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller.skus(0,7,day)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(dao);
    }
    @Test void missingOrOversizedReceiptCannotProduceAnAnnouncement() {
        when(dao.skus(9,7,day)).thenReturn(List.of());
        assertThatThrownBy(()->controller.skus(9,7,day)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("404");
        when(dao.skus(9,7,day)).thenReturn(LongStream.range(0,2001).mapToObj(Long::toString).toList());
        assertThatThrownBy(()->controller.skus(9,7,day)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
    }
    @Test void onlyPublicSkuSelectionFieldsAreReturned() {
        when(dao.skus(9,7,day)).thenReturn(List.of("00123","АБВ"));
        assertThat(controller.skus(9,7,day)).containsOnlyKeys("ok","documentId","warehouseId","date","skus")
                .containsEntry("skus",List.of("00123","АБВ"));
    }
    @Test @SuppressWarnings("unchecked") void queryScopesHeaderAndLinesWithoutExposingCosts() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(invocation->{
            String sql=invocation.getArgument(0);
            assertThat(sql).contains("n.TYPE_DOC=?","n.STND_UCHET=1","n.ID_SCLAD=?","m.ID_SCLAD=?","m.STND_UCHET=1","n.DATE_P_POR>=?","n.DATE_P_POR<?","ISNULL(n.VOZVRAT_PR,0)=0","m.KOLC_PREDM>0")
                    .doesNotContain("CENA","SUM_PREDM","NOLOCK","OFFSET","ROW_NUMBER","INSERT","UPDATE");
            assertThat(invocation.getArgument(3,String.class)).isEqualTo("П");
            return List.of("00123");
        });
        assertThat(new FolioReceiptCatalogueDao(jdbc).skus(9,7,day)).containsExactly("00123");
    }
}
