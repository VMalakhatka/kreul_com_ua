package org.example.proect.lavka.service;

import org.example.proect.lavka.dto.sync.SyncRunResponse;

public interface SyncService {

    /**
     * Выполняет один шаг синхронизации по диффу.
     *
     * @param limit           сколько товаров максимум обрабатывать (cap)
     * @param pageSizeWoo     сколько SKU читать из Woo за раз (окно seen)
     * @param cursorAfter     от какого SKU продолжать читать Woo (null = сначала)
     * @param dryRun          если true -> ничего не пишем в Woo, только считаем
     *
     * @return отчет, который сразу готов отдавать наружу
     */
    SyncRunResponse runOneBatch(
            Integer limit,
            Integer pageSizeWoo,
            String cursorAfter,
            Boolean dryRun
    );
}