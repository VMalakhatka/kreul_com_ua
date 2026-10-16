package org.example.proect.lavka.service.folio;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;

class FolioOrderDocumentPurposeTest {
    private final FolioOrderAccountService service = new FolioOrderAccountService(null, null);
    @Test void manualDocumentsKeepTheirPurposeWithoutInventingShortages() {
        for (String info : new String[]{"Черновик Int 123", "ПРД Int 123"}) {
            String result = ReflectionTestUtils.invokeMethod(service, "missingAdditionalInfo", info, "non_accounting_account");
            assertThat(result).isEqualTo(info).doesNotContain("Нет на складе");
        }
    }
    @Test void onlyRealMissingStockDocumentsGetShortageMarker() {
        String shortage = ReflectionTestUtils.invokeMethod(service, "missingAdditionalInfo", "Int 123", "missing_stock_account");
        String account = ReflectionTestUtils.invokeMethod(service, "missingAdditionalInfo", "Int 123", "account");
        assertThat(shortage).contains("Нет на складе").doesNotStartWith("ПРД");
        assertThat(account).isEqualTo("Int 123");
    }
}
