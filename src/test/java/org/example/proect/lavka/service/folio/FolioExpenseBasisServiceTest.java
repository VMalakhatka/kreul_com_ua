package org.example.proect.lavka.service.folio;
import org.example.proect.lavka.dao.folio.FolioExpenseBasisDao;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class FolioExpenseBasisServiceTest {
    final FolioExpenseBasisDao dao=mock(FolioExpenseBasisDao.class);
    final FolioExpenseBasisService service=new FolioExpenseBasisService(dao);
    final FolioExpenseBasisDao.Header archived=new FolioExpenseBasisDao.Header("ACCOUNT",42,"123456","Woo",LocalDate.of(2026,6,18),7,"TEST","ARCHIVE_LEDGER");
    @Test void resolvesArchivedAccountByAllExactKeys() {
        when(dao.expenseBasis("TEST",1)).thenReturn(List.of("123456Woo о 18.06.2026"));
        when(dao.accounts("TEST",new BigDecimal("123456"),"Woo",LocalDate.of(2026,6,18),true)).thenReturn(List.of(archived));
        var result=service.resolve("TEST",1);
        assertThat(result.status()).isEqualTo("MATCHED"); assertThat(result.account()).isEqualTo(archived);
        verify(dao).accounts("TEST",new BigDecimal("123456"),"Woo",LocalDate.of(2026,6,18),false);
    }
    @Test void ambiguousAcrossArchiveAndActiveNeverPicksFirst() {
        when(dao.expenseBasis("TEST",1)).thenReturn(List.of("123456Woo от 18.06.2026"));
        when(dao.accounts(anyString(),any(),anyString(),any(),anyBoolean())).thenReturn(List.of(archived));
        var result=service.resolve("TEST",1); assertThat(result.status()).isEqualTo("AMBIGUOUS"); assertThat(result.account()).isNull();
    }
    @Test void missingAccountDoesNotInventOne() {
        when(dao.expenseBasis("TEST",1)).thenReturn(List.of("123456 від 18.06.2026"));
        assertThat(service.resolve("TEST",1).status()).isEqualTo("NOT_FOUND");
    }
    @Test void notesAndInvalidDatesDoNotSearch() {
        for(String basis:List.of("РЕЛ","123456Woo от 31.02.2026","123456Woo от 18.06.2026 extra")) {
            when(dao.expenseBasis("TEST",1)).thenReturn(List.of(basis));
            assertThat(service.resolve("TEST",1).status()).isEqualTo("UNRECOGNIZED_BASIS");
        }
        verify(dao,never()).accounts(anyString(),any(),anyString(),any(),anyBoolean());
    }
    @Test void blankBasisIsExplicit() {
        when(dao.expenseBasis("TEST",1)).thenReturn(List.of(""));
        assertThat(service.resolve("TEST",1).status()).isEqualTo("NO_BASIS");
    }
    @Test void missingExpenseAndBadIdentityReject() {
        assertThatThrownBy(()->service.resolve("TEST",1)).isInstanceOf(FolioCustomerDocumentNotFoundException.class);
        assertThatThrownBy(()->service.resolve("",1)).isInstanceOf(FolioAccountValidationException.class);
        assertThatThrownBy(()->service.resolve("TEST",0)).isInstanceOf(FolioAccountValidationException.class);
    }
}
