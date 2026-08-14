package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioCustomerDocumentDao;
import org.example.proect.lavka.dao.folio.FolioCustomerDocumentDao.DocumentCursor;
import org.example.proect.lavka.dao.folio.FolioCustomerDocumentDao.PartnerRow;
import org.example.proect.lavka.dto.folio.FolioCustomerDocumentDetailResponse;
import org.example.proect.lavka.dto.folio.FolioCustomerDocumentType;
import org.example.proect.lavka.dto.folio.FolioCustomerDocumentsResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FolioCustomerDocumentServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-14T10:00:00Z"), ZoneOffset.UTC
    );

    @Test
    void returnsLimitedPageAndOpaqueCursor() {
        FolioCustomerDocumentDao dao = mock(FolioCustomerDocumentDao.class);
        when(dao.findPartner("КЛИЕНТ")).thenReturn(Optional.of(new PartnerRow("КЛИЕНТ", "Клиент")));
        when(dao.findDocuments(
                eq("КЛИЕНТ"),
                eq(LocalDate.of(2026, 1, 1)),
                eq(LocalDate.of(2026, 8, 14)),
                eq(List.of(FolioCustomerDocumentType.ACCOUNT, FolioCustomerDocumentType.EXPENSE)),
                eq(2),
                eq(null)
        )).thenReturn(List.of(
                summary(FolioCustomerDocumentType.EXPENSE, 20, LocalDateTime.of(2026, 8, 10, 12, 0)),
                summary(FolioCustomerDocumentType.ACCOUNT, 19, LocalDateTime.of(2026, 8, 9, 12, 0))
        ));

        var response = new FolioCustomerDocumentService(dao, CLOCK).list(
                " КЛИЕНТ ",
                LocalDate.of(2026, 1, 1),
                null,
                "account,expense",
                1,
                null
        );

        assertThat(response.documents()).extracting(FolioCustomerDocumentsResponse.DocumentSummary::documentId)
                .containsExactly(20L);
        assertThat(response.hasMore()).isTrue();
        assertThat(response.nextCursor()).isNotBlank();
        assertThat(response.filters().dateTo()).isEqualTo(LocalDate.of(2026, 8, 14));
    }

    @Test
    void decodesCursorAndPassesItToDao() {
        FolioCustomerDocumentDao dao = mock(FolioCustomerDocumentDao.class);
        when(dao.findPartner("A")).thenReturn(Optional.of(new PartnerRow("A", "Client")));
        when(dao.findDocuments(any(), any(), any(), any(), any(Integer.class), any()))
                .thenReturn(List.of());
        FolioCustomerDocumentService service = new FolioCustomerDocumentService(dao, CLOCK);

        var first = summary(
                FolioCustomerDocumentType.PAYMENT,
                77,
                LocalDateTime.of(2026, 8, 13, 10, 20, 30, 123_000_000)
        );
        when(dao.findDocuments(
                eq("A"), any(), any(), any(), eq(2), eq(null)
        )).thenReturn(List.of(first, summary(
                FolioCustomerDocumentType.ACCOUNT, 76, LocalDateTime.of(2026, 8, 12, 10, 0)
        )));
        String cursor = service.list("A", null, null, null, 1, null).nextCursor();

        service.list("A", null, null, null, 10, cursor);

        ArgumentCaptor<DocumentCursor> cursorCaptor = ArgumentCaptor.forClass(DocumentCursor.class);
        verify(dao).findDocuments(eq("A"), any(), any(), any(), eq(11), cursorCaptor.capture());
        assertThat(cursorCaptor.getValue().documentDate()).isEqualTo(first.documentDate());
        assertThat(cursorCaptor.getValue().typeRank()).isEqualTo(FolioCustomerDocumentType.PAYMENT.sortRank());
        assertThat(cursorCaptor.getValue().documentId()).isEqualTo(77);
    }

    @Test
    void readsOnlyDocumentOwnedByRequestedPartner() {
        FolioCustomerDocumentDao dao = mock(FolioCustomerDocumentDao.class);
        when(dao.findPartner("A")).thenReturn(Optional.of(new PartnerRow("A", "Client")));
        when(dao.findStockDocument("A", FolioCustomerDocumentType.ACCOUNT, 753568L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> new FolioCustomerDocumentService(dao, CLOCK)
                .get("A", "ACCOUNT", 753568L))
                .isInstanceOf(FolioCustomerDocumentNotFoundException.class)
                .hasMessageContaining("753568");

        verify(dao).findStockDocument("A", FolioCustomerDocumentType.ACCOUNT, 753568L);
        verify(dao, never()).findPayment(any(), any(Long.class));
    }

    @Test
    void rejectsInvalidFiltersBeforeDocumentQuery() {
        FolioCustomerDocumentDao dao = mock(FolioCustomerDocumentDao.class);
        when(dao.findPartner("A")).thenReturn(Optional.of(new PartnerRow("A", "Client")));
        FolioCustomerDocumentService service = new FolioCustomerDocumentService(dao, CLOCK);

        assertThatThrownBy(() -> service.list(
                "A",
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2026, 8, 14),
                "invoice",
                101,
                null
        )).isInstanceOf(FolioAccountValidationException.class);

        verify(dao, never()).findDocuments(any(), any(), any(), any(), any(Integer.class), any());
    }

    private static FolioCustomerDocumentsResponse.DocumentSummary summary(
            FolioCustomerDocumentType type,
            long id,
            LocalDateTime date) {
        return new FolioCustomerDocumentsResponse.DocumentSummary(
                type,
                id,
                Long.toString(id),
                null,
                date,
                new BigDecimal("100.00"),
                null,
                null,
                7,
                true,
                false,
                false,
                null,
                null,
                1,
                null,
                type.repeatable(),
                "ACTIVE_LEDGER"
        );
    }
}
