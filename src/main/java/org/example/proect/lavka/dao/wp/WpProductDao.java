package org.example.proect.lavka.dao.wp;

import org.example.proect.lavka.dto.SeenItem;
import org.example.proect.lavka.service.CardTovExportService;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface WpProductDao {

    /**
     * Возвращает окно товаров из Woo отсортированное по SKU ASC, начиная со следующего после cursorAfter.
     * Берём SKU и сохранённый hash (_ms_hash).
     *
     * Это аналог lts_collect_seen_window() из PHP.
     */
    List<SeenItem> collectSeenWindow(int limit, String cursorAfter);

    Map<String, Long> findIdsBySkus(Collection<String> skus);

    public Long findAttachmentIdByS3KeyOrGuid(String s3Key, String guid);

    public Long findFeaturedId(long productId);

    public List<Long> findGalleryIds(long productId);

    List<String> listSkusBetween(String fromSku, String toSku, int limit, String afterExclusive);

}