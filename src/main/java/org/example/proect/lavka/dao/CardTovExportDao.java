package org.example.proect.lavka.dao;


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
}