package org.example.proect.lavka.service.folio;
import org.example.proect.lavka.dao.folio.FolioDocumentValuesDao;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class FolioDocumentValuesServiceTest {
    @Test void rejectsInvalidReferenceBeforeRead() {
        var dao=mock(FolioDocumentValuesDao.class); var service=new FolioDocumentValuesService(dao);
        assertThatThrownBy(()->service.read("TEST","PAYMENT",1)).isInstanceOf(FolioAccountValidationException.class);
        assertThatThrownBy(()->service.read("","EXPENSE",1)).isInstanceOf(FolioAccountValidationException.class);
        assertThatThrownBy(()->service.read("TEST","EXPENSE",0)).isInstanceOf(FolioAccountValidationException.class);
        verifyNoInteractions(dao);
    }
    @Test void exactReceiptPartnerAndCyrillicTypeAreRequired() {
        var dao=mock(FolioDocumentValuesDao.class); when(dao.read("TEST","RECEIPT","П",123)).thenReturn(List.of());
        assertThatThrownBy(()->new FolioDocumentValuesService(dao).read(" TEST ","RECEIPT",123)).isInstanceOf(FolioCustomerDocumentNotFoundException.class);
        verify(dao).read("TEST","RECEIPT","П",123);
    }
}
