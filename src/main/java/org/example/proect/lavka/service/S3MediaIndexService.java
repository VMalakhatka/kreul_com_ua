package org.example.proect.lavka.service;


import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dao.wp.S3MediaIndexDao;
import org.example.proect.lavka.property.OvhS3Props;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class S3MediaIndexService {

    private final S3Client s3;
    private final OvhS3Props props;
    private final S3MediaIndexDao dao;

    public record IndexedChunk(int insertedOrUpdated, int pages) {}

    /**
     * Полная индексация (можно вызывать по кнопке/по CRON).
     * Идём по всем объектам с заданным prefix (или по всему бакету).
     */
    @Transactional
    public IndexedChunk reindexAll() {
        String prefix = (props.prefix() == null) ? "" : props.prefix();
        ListObjectsV2Request req = ListObjectsV2Request.builder()
                .bucket(props.bucket())
                .prefix(prefix)
                .maxKeys(1000)
                .build();

        ListObjectsV2Iterable it = s3.listObjectsV2Paginator(req);

        int total = 0;
        int pages = 0;
        List<S3MediaIndexDao.Row> batch = new ArrayList<>(1000);

        for (ListObjectsV2Response page : it) {
            pages++;
            for (S3Object o : page.contents()) {
                String key = o.key();
                String name = key.substring(key.lastIndexOf('/') + 1).toLowerCase();
                long size = o.size();
                Instant lm = o.lastModified();
                String etag = o.eTag();

                batch.add(new S3MediaIndexDao.Row(name, key, size, lm, etag));
                if (batch.size() >= 1000) {
                    int[] res = dao.upsertBatch(batch);
                    total += res.length;
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            int[] res = dao.upsertBatch(batch);
            total += res.length;
        }
        return new IndexedChunk(total, pages);
    }

    /**
     * Быстро найти по имени (через индекс). Может вернуть несколько совпадений.
     */
    public List<S3MediaIndexDao.Row> find(String fileName) {
        return dao.findByFileName(fileName.toLowerCase());
    }

    /** Построить публичный URL (virtual-hosted). */
    public String toPublicUrl(String fullKey) {
        // без доп. экранирования: у тебя уже такой формат
        return "https://" + props.bucket() + ".s3.gra.io.cloud.ovh.net/" + fullKey;
    }
}