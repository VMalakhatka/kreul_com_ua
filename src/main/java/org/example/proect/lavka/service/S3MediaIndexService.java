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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Transactional
    public int[] linkByFileNameToSkus(String fileName, List<String> skus, Integer startPos) {
        return dao.upsertLinksByFileNameAndSkus(fileName, skus, startPos);
    }

    @Transactional
    public int linkByFileNameToProduct(String fileName, long productId, Integer position) {
        return dao.upsertLinkByFileNameAndProductId(fileName, productId, position);
    }

    public List<java.util.Map<String,Object>> buildWooImages(List<String> fullKeys) {
        List<java.util.Map<String,Object>> images = new ArrayList<>();
        int pos = 0;
        for (String k : fullKeys) {
            images.add(java.util.Map.of(
                    "src", toPublicUrl(k),
                    "position", pos++
            ));
        }
        return images;
    }
    public List<Map<String,Object>> buildWooImagesForSku(String sku) {
        List<String> keys = dao.findFullKeysBySku(sku);
        return buildWooImages(keys); // уже есть у тебя
    }

    public Map<String, List<Map<String,Object>>> buildWooImagesForSkus(List<String> skus) {
        Map<String, List<Map<String,Object>>> out = new LinkedHashMap<>();
        for (String sku : skus) {
            out.put(sku, buildWooImagesForSku(sku));
        }
        return out;
    }

    public record AltTitle(String alt, String title) {}

    public AltTitle makeAltTitle(String productType, String brand, String lineOrColor, String volumeOrSize, String angle) {
        // ALT: “[тип] [бренд] [линейка/цвет/объём] [ракурс]”, резать до 100
        String base = String.join(" ",
                nonBlank(productType),
                nonBlank(brand),
                nonBlank(lineOrColor),
                nonBlank(volumeOrSize),
                nonBlank(angle)
        ).replaceAll("\\s+", " ").trim();

        String alt = base.length() > 100 ? base.substring(0, 100) : base;

        // Title опционален: SKU + транслитерированный alt, до 190 символов
        String title = translit(alt);
        if (title.length() > 190) title = title.substring(0, 190);

        return new AltTitle(alt, title);
    }

    private String nonBlank(String s) { return (s == null ? "" : s.trim()); }
    // заглушка; можешь подставить свою транслитерацию
    private String translit(String s) { return s; }

    @Transactional
    public void setAltTitleForFileAndSku(String fileName, String sku, int position,
                                         String alt, String title) {
        Long imageId = dao.resolveImageIdByFilename(fileName);
        if (imageId == null) throw new IllegalStateException("Image not found: " + fileName);
        dao.upsertAltTitle(imageId, sku, null, position, alt, title); // pending_meta=1
    }

    @Transactional
    public Map<String, List<String>> linkAndReturnUrlsByFileAndSkus(
            String fileName,
            List<String> skus,
            Integer startPos,
            java.util.function.Function<String, AltTitle> altTitleBySku // генератор ALT/TITLE по SKU
    ) {
        // 1) связываем
        dao.upsertLinksByFileNameAndSkus(fileName, skus, startPos);

        // 2) при необходимости проставляем ALT/TITLE (помечаем pending_meta)
        if (altTitleBySku != null) {
            Long imageId = dao.resolveImageIdByFilename(fileName);
            int base = (startPos == null ? 0 : Math.max(0, startPos));
            for (int i = 0; i < skus.size(); i++) {
                String sku = skus.get(i);
                AltTitle at = altTitleBySku.apply(sku);
                if (at != null) {
                    dao.upsertAltTitle(imageId, sku, null, base + i, at.alt(), at.title());
                }
            }
        }

        // 3) отдать ключи/URL’ы по каждому SKU
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String sku : skus) {
            List<String> keys = dao.findFullKeysBySku(sku);
            List<String> urls = new ArrayList<>(keys.size());
            for (String k : keys) urls.add(toPublicUrl(k));
            out.put(sku, urls);
        }
        return out;
    }
}