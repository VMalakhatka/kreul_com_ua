package org.example.proect.lavka.dao.wp;

import org.example.proect.lavka.dto.SeenItem;
import org.example.proect.lavka.service.CardTovExportService;

import java.util.List;

public interface WpProductDao {

    /**
     * Возвращает окно товаров из Woo отсортированное по SKU ASC, начиная со следующего после cursorAfter.
     * Берём SKU и сохранённый hash (_ms_hash).
     *
     * Это аналог lts_collect_seen_window() из PHP.
     */
    List<SeenItem> collectSeenWindow(int limit, String cursorAfter);
}