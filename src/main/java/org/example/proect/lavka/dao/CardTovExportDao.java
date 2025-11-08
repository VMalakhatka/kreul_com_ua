package org.example.proect.lavka.dao;


import jakarta.annotation.Nullable;
import org.example.proect.lavka.dto.CardTovExportDto;

import java.util.Collection;
import java.util.List;

public interface CardTovExportDao {
    List<CardTovExportDto> findPage(String after, int limit);


    /** Ровно по списку SKU (для update/delete) */
    List<CardTovExportDto> findBySkus(Collection<String> skus);

    /**
     * Найти новые товары в интервале (minSku, maxSku), которых нет у Woo (exclude),
     * ограничивая капом cap.
     */
    List<CardTovExportDto> findBetweenExcluding(String minSku, String maxSku,
                                                Collection<String> exclude, int cap);

    List<CardTovExportDto> findLessThanExcluding(String maxExclusive, Collection<String> exclude, int limit);

    List<CardTovExportDto> findGreaterThan(String minExclusive, int limit);

    List<CardTovExportDto> findGreaterThanExcluding(String lowerExclusive,
                                                    Collection<String> excludeSkus,
                                                    int limit);
    public @Nullable CardTovExportDaoImpl.MsCardImages findCardImagesBySku(String sku);
    public List<CardTovExportDaoImpl.MsGalleryImage> findGalleryByPlusArtic(String plusArtic);
    public CardTovExportDaoImpl.MsImagesBundle findImagesBundleBySku(String sku);

}