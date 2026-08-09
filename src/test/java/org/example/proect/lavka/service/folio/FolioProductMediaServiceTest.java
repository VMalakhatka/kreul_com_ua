package org.example.proect.lavka.service.folio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.proect.lavka.dao.folio.FolioProductMediaDao;
import org.example.proect.lavka.dao.wp.FolioProductMediaRequestDao;
import org.example.proect.lavka.dao.wp.S3MediaIndexDao;
import org.example.proect.lavka.dto.folio.media.FolioProductMediaChangeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FolioProductMediaServiceTest {

    private FolioProductMediaDao folio;
    private S3MediaIndexDao s3;
    private FolioProductMediaService service;

    @BeforeEach
    void setUp() {
        folio = mock(FolioProductMediaDao.class);
        s3 = mock(S3MediaIndexDao.class);
        service = new FolioProductMediaService(
                folio,
                s3,
                mock(FolioProductMediaRequestDao.class),
                new ObjectMapper(),
                transactionManager()
        );
    }

    @Test
    void normalizedSearchNameRemovesLegacyPathAndCollapsesSeparators() {
        assertThat(FolioProductMediaService.normalizeFilename("pic/PCY-941008--R.JPG"))
                .isEqualTo(FolioProductMediaService.normalizeFilename("pcy-941008-r.jpg"));
    }

    @Test
    void previewSetMainIsReadyAndDoesNotWriteFolio() {
        when(folio.findProduct("ABC-001", false)).thenReturn(
                new FolioProductMediaDao.ProductRow("ABC-001", "Product", "old.jpg", 42));
        when(folio.countProductsByPlusArtic(42)).thenReturn(1);
        when(s3.findByFileName("new.jpg")).thenReturn(List.of(
                new S3MediaIndexDao.Row(
                        "new.jpg", "wp-content/uploads/2026/08/new.jpg", 123,
                        Instant.parse("2026-08-09T10:00:00Z"), "abc")
        ));

        var response = service.change(request(new FolioProductMediaChangeRequest.Change(
                "set_main", "ABC-001", null,
                "old.jpg", null,
                "new.jpg", null,
                new FolioProductMediaChangeRequest.S3Proof(
                        "wp-content/uploads/2026/08/new.jpg", 123L, "\"abc\"")
        )));

        assertThat(response.ok()).isTrue();
        assertThat(response.summary().ready()).isEqualTo(1);
        assertThat(response.results().get(0).status()).isEqualTo("ready");
        verify(folio, never()).updateMain(anyString(), anyString(), anyString());
    }

    @Test
    void previewBlocksSameNameS3ObjectsWithDifferentContent() {
        when(folio.findProduct("ABC-001", false)).thenReturn(
                new FolioProductMediaDao.ProductRow("ABC-001", "Product", "old.jpg", 42));
        when(folio.countProductsByPlusArtic(42)).thenReturn(1);
        when(s3.findByFileName("new.jpg")).thenReturn(List.of(
                new S3MediaIndexDao.Row("new.jpg", "a/new.jpg", 123, null, "aaa"),
                new S3MediaIndexDao.Row("new.jpg", "b/new.jpg", 124, null, "bbb")
        ));

        var response = service.change(request(new FolioProductMediaChangeRequest.Change(
                "set_main", "ABC-001", null,
                "old.jpg", null,
                "new.jpg", null,
                null
        )));

        assertThat(response.ok()).isFalse();
        assertThat(response.summary().blocked()).isEqualTo(1);
        assertThat(response.results().get(0).errors())
                .extracting(error -> error.code())
                .contains("S3_FILENAME_CONFLICT");
        verify(folio, never()).updateMain(anyString(), anyString(), anyString());
    }

    @Test
    void applyAddGalleryUsesGeneratedStableRecordId() {
        FolioProductMediaRequestDao requestDao = mock(FolioProductMediaRequestDao.class);
        service = new FolioProductMediaService(
                folio, s3, requestDao, new ObjectMapper(), transactionManager());
        when(folio.findProduct("ABC-001", true)).thenReturn(
                new FolioProductMediaDao.ProductRow("ABC-001", "Product", "main.jpg", 42));
        when(folio.countProductsByPlusArtic(42)).thenReturn(1);
        when(folio.findGalleryByPlusArtic(42, true)).thenReturn(List.of());
        when(folio.insertGallery(42, "gallery.jpg", 1)).thenReturn(12345);
        when(s3.findByFileName("gallery.jpg")).thenReturn(List.of(
                new S3MediaIndexDao.Row("gallery.jpg", "uploads/gallery.jpg", 10, null, "etag")
        ));

        var request = new FolioProductMediaChangeRequest(
                "apply-request", false, "test", List.of(
                new FolioProductMediaChangeRequest.Change(
                        "add_gallery", "ABC-001", null,
                        null, null, "gallery.jpg", null, null)
        ));
        var response = service.change(request);

        assertThat(response.ok()).isTrue();
        assertThat(response.summary().applied()).isEqualTo(1);
        assertThat(response.results().get(0).recordId().key()).isEqualTo("12345");
        verify(folio).insertGallery(42, "gallery.jpg", 1);
        verify(requestDao).save(anyString(), anyString(), anyString());
    }

    private static FolioProductMediaChangeRequest request(
            FolioProductMediaChangeRequest.Change change
    ) {
        return new FolioProductMediaChangeRequest(
                "test-request", true, "test", List.of(change));
    }

    private static PlatformTransactionManager transactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }
}
