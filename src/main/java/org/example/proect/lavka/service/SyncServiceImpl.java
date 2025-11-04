package org.example.proect.lavka.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.proect.lavka.client.LavkaLocationsClient;
import org.example.proect.lavka.client.WooApiClient;
import org.example.proect.lavka.dao.wp.WpProductDao;
import org.example.proect.lavka.dto.CardTovExportOutDto;
import org.example.proect.lavka.dto.SeenItem;
import org.example.proect.lavka.dto.sync.SyncRunResponse;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncServiceImpl implements SyncService {

    private final WpProductDao wpProductDao;
    private final CardTovExportService cardTovExportService;
    private final WooApiClient wooApiClient;
    private final LavkaLocationsClient lavkaLocationsClient;

    @Override
    public SyncRunResponse runOneBatch(
            Integer limit,
            Integer pageSizeWoo,
            String startCursorAfter,
            Boolean dryRun
    ) {
        // === 0. Входные параметры ===
        final int runLimit = (limit == null || limit < 1)
                ? 1_000_000_000 // условно бесконечно
                : limit;

        // это максимум, сколько diffPage за РАЗ имеет право нам вернуть,
        // не больше 1000 — как мы сами ограничиваем в diffPage

        final int diffChunkLimit = Math.min(runLimit, 1000);

        // сколько SKU Woo попадает в одно "окно сравнения"
        final int wooWindowSize = (pageSizeWoo == null || pageSizeWoo < 1)
                ? 200
                : Math.min(pageSizeWoo, 1000);

        final boolean isDry = (dryRun != null && dryRun);
        Map<Long, String> categoryDescMap = new HashMap<>();

        // === 1. Счётчики результата ===
        int totalProcessed = 0;
        int totalCreated   = 0;
        int totalUpdated   = 0;
        int totalDrafted   = 0;
        final List<String> allErrors = new ArrayList<>();

        // === 2. Глобальный курсор (переходит между окнами Woo)
        String globalCursorAfter = (startCursorAfter == null || startCursorAfter.isBlank())
                ? null
                : startCursorAfter.trim();

        boolean stopeedByLimit = false;

        // ===== ВНЕШНИЙ ЦИКЛ =====
        while (true) {

            // Снимок перед началом обработки этого окна Woo
            final String windowStartCursor = globalCursorAfter;

            // 2.1 Берём окно Woo начиная сразу после globalCursorAfter
            //     +1 чтобы понять "есть ли ещё дальше"
            List<SeenItem> windowRaw =
                    wpProductDao.collectSeenWindow(wooWindowSize + 1, globalCursorAfter);

            // Если вообще ничего нет -> Woo кончился, выходим полностью
            if (windowRaw.isEmpty()) {
                break;
            }

            // есть ли "хвост" дальше этого окна?
            boolean wooAlmostAtEnd = (windowRaw.size() <= wooWindowSize);

            // 3. готовим окно для diff (только sku + hash)
            List<CardTovExportService.ItemHash> windowForDiff = windowRaw.stream()
                    .limit(wooWindowSize)
                    .map(it -> new CardTovExportService.ItemHash(
                            it.sku(),
                            it.hash() == null ? "" : it.hash()))
                    .toList();

            totalProcessed += windowForDiff.size();

            Map<String, Long> existingIdsBySku = windowRaw.stream()
                    .collect(Collectors.toMap(SeenItem::sku, SeenItem::postId, (a, b)->a));

            // 2.2 В этом окне Woo мы будем крутить ВНУТРЕННИЙ ЦИКЛ,
            //     пока diffPage говорит, что есть ещё create'ов
            String innerAfterSku = globalCursorAfter;   // локальный курсор для diffPage
            boolean firstIterationForThisWindow = true; // чтобы не дублировать update/delete
            boolean windowFinished = false;             // станет true когда diff.last()==true

            // ===== ВНУТРЕННИЙ ЦИКЛ =====
            while (!windowFinished) {

                // 2.2.1 Получаем дифф
                CardTovExportService.DiffResult diff =
                        cardTovExportService.diffPage(
                                innerAfterSku,
                                diffChunkLimit,
                                windowForDiff
                        );

                // разбор результата
                List<String> toDelete = firstIterationForThisWindow ? nzList(diff.toDelete()): List.of();
                List<CardTovExportOutDto> toUpdateFull = firstIterationForThisWindow ? nzList(diff.toUpdateFull()):List.of();
                List<CardTovExportOutDto> toCreateFull = nzList(diff.toCreateFull());

                // считаем, сколько всего штук в этой конкретной порции
                int batchDel = firstIterationForThisWindow ? toDelete.size() : 0;
                int batchUpd = firstIterationForThisWindow ? toUpdateFull.size() : 0;
                int batchAdd = toCreateFull.size();
                int batchProcessed = batchDel + batchUpd + batchAdd;
                if(batchProcessed!=0) {
                    // 2.2.2 Применяем в Woo
                    if (isDry) {
                        // только считаем
                        if (firstIterationForThisWindow) {
                            totalDrafted += batchDel;    // только в первой итерации окна
                            totalUpdated += batchUpd;    // только в первой итерации окна
                        }
                        totalCreated += batchAdd;    // всегда (каждая порция create)
                    } else {
                        for (CardTovExportOutDto dto : toUpdateFull) {
                            collectCategoryDesc(categoryDescMap, dto.groupId(), dto.grDescr());
                        }
                        for (CardTovExportOutDto dto : toCreateFull) {
                            collectCategoryDesc(categoryDescMap, dto.groupId(), dto.grDescr());
                        }
                        Map<String, Object> batchPayload = buildWooBatchPayload(toUpdateFull, toCreateFull, toDelete, existingIdsBySku);

                        // разобрать batchPayload на чанки по 100 total
                        List<Map<String,Object>> subPayloads = splitBatchPayload(batchPayload, 20);

                        // апдейты/создания
                        // bulk upsert:
                        //  - toUpdateFull  (только в первую итерацию)
                        //  - toCreateFull  (каждый раз)
                        if (firstIterationForThisWindow) {
                            totalDrafted += batchDel;    // только в первой итерации окна
                            totalUpdated += batchUpd;    // только в первой итерации окна
                        }

//                        List<CardTovExportOutDto> updBatch =
//                                firstIterationForThisWindow ? toUpdateFull : List.of();

                        // BulkResult bulkRes = bulkUpsertWoo(updBatch, toCreateFull);
                        totalCreated += batchAdd;
                    }
                }

                // 2.2.3 Двигаем локальный курсор и смотрим флаги
                innerAfterSku = diff.nextAfter();   // может стать последний созданный SKU
                windowFinished = diff.last();       // true => мы ДОКОНЧАЛИ все create для этого окна
                firstIterationForThisWindow = false;

                // 2.2.4 Стоперы безопасности

                // Если достигли общий лимит по запросу runLimit — выходим полностью
                if (totalProcessed >= runLimit) {
                    stopeedByLimit = true;
                    break;
                }

                // Если diff не дал nextAfter (или не сдвинул):
                // это значит мы застряли, смысла продолжать нет
                if (innerAfterSku == null || innerAfterSku.isBlank()) {
                    windowFinished = true;
                }
                if (innerAfterSku != null
                        && innerAfterSku.equals(globalCursorAfter)) {
                    // это прям патологический случай - nextAfter не продвинуло нас от старта
                    windowFinished = true;
                }
            } // конец внутреннего цикла по одному окну Woo

            // 2.3 После того как ДОГРУЗИЛИ ВСЕ create'ы в рамках этого окна Woo,
            //     мы должны сдвинуть глобальный курсор дальше,
            //     но чем? Ответ: innerAfterSku после последнего diff.
            //
            // Почему не maxSKU из windowRaw? Потому что diff.nextAfter, когда last=true,
            // сам делает "якорение" на originalMaxWoo.
            //
            // Т.е. innerAfterSku уже корректно указывает позицию, с которой следующее окно должно начинаться.
            globalCursorAfter = innerAfterSku;

            // условия полного выхода:
            if (stopeedByLimit) {
                break;
            }
            if (globalCursorAfter == null || globalCursorAfter.isBlank()) {
                break;
            }
            if (Objects.equals(windowStartCursor, globalCursorAfter)) {
                // курсор глобально не сдвинулся -> мы в тупике (ничего больше не отгружается)
                break;
            }
            if (wooAlmostAtEnd) {
                // Woo за этим окном уже практически пуст, некуда двигаться дальше
                break;
            }
        } // конец внешнего цикла по окнам Woo

        if (!isDry && !categoryDescMap.isEmpty()) {
            try {
                lavkaLocationsClient.pushCategoryDescriptionsBatch(categoryDescMap);
            } catch (Exception e) {
                allErrors.add("catdesc_sync_failed: " + e.getMessage());
            }
        }
        // === 3. Ответ
        return new SyncRunResponse(
                true,
                totalProcessed,
                totalCreated,
                totalUpdated,
                totalDrafted,
                globalCursorAfter,
                stopeedByLimit, // "мы остановились по лимиту, то есть всё что могли"
                allErrors
        );
    }

    // чтобы не дублировать
    private static <T> List<T> nzList(List<T> in) {
        return (in == null) ? List.of() : in;
    }

    /**
     * Готовит тело для /wp-json/wc/v3/products/batch
     *
     *  {
     *    "create": [ {...}, {...} ],
     *    "update": [ {...}, {...} ]
     *  }
     *
     *  - toAdd    -> create[]
     *  - toUpd    -> update[]  (id если знаем; иначе sku)
     *  - toDelete -> update[]  (id/sku + status:"draft")
     *
     *  existingIdsBySku: карта sku -> product_id из Woo,
     *  собранная заранее из окна seen (wpProductDao.collectSeenWindowExtended).
     */
    private Map<String, Object> buildWooBatchPayload(
            List<CardTovExportOutDto> toUpd,
            List<CardTovExportOutDto> toAdd,
            List<String> toDelete,
            Map<String, Long> existingIdsBySku
    ) {

        toUpd    = (toUpd    == null) ? List.of() : toUpd;
        toAdd    = (toAdd    == null) ? List.of() : toAdd;
        toDelete = (toDelete == null) ? List.of() : toDelete;
        existingIdsBySku = (existingIdsBySku == null) ? java.util.Map.of() : existingIdsBySku;

        // ---------- create ----------
        java.util.List<java.util.Map<String,Object>> createList = new java.util.ArrayList<>();
        for (CardTovExportOutDto dto : toAdd) {
            if (dto == null) continue;

            Long knownId = null; // новых товаров в Woo ещё нет => id нет
            java.util.Map<String,Object> prod =
                    mapDtoToWooProduct(dto, /*forceDraft=*/false, /*forUpdate=*/false, knownId);

            // для create не указываем "id", Woo сам создаст
            createList.add(prod);
        }

        // ---------- update ----------
        java.util.List<java.util.Map<String,Object>> updateList = new java.util.ArrayList<>();

        // 2a. нормальные апдейты
        for (CardTovExportOutDto dto : toUpd) {
            if (dto == null) continue;

            Long knownId = null;
            if (dto.sku() != null) {
                knownId = existingIdsBySku.get(dto.sku());
            }

            java.util.Map<String,Object> prod =
                    mapDtoToWooProduct(dto, /*forceDraft=*/false, /*forUpdate=*/true, knownId);

            updateList.add(prod);
        }

        // 2b. "удаления": принудительно ставим draft
        for (String sku : toDelete) {
            if (sku == null || sku.isBlank()) continue;

            Long knownId = existingIdsBySku.get(sku.trim());

            java.util.Map<String,Object> prodDraft = new java.util.HashMap<>();
            if (knownId != null) {
                // Woo любит update по id
                prodDraft.put("id", knownId);
            } else {
                // fallback если по какой-то причине мы не знаем id
                prodDraft.put("sku", sku.trim());
            }
            prodDraft.put("status", "draft");

            updateList.add(prodDraft);
        }

        java.util.Map<String,Object> payload = new java.util.HashMap<>();
        if (!createList.isEmpty()) payload.put("create", createList);
        if (!updateList.isEmpty()) payload.put("update", updateList);

        return payload;
    }

    /**
     * Преобразует нашу карточку (DTO из MSSQL) в структуру товара WooCommerce.
     *
     * knownId   - если товар уже существует в Woo, это его product_id.
     *             Если не null -> кладём "id": knownId, чтобы Woo понял что это update.
     *
     * forceDraft - если true, статус товара жёстко "draft".
     * forUpdate  - просто флажок для логики, если вдруг потом захочешь вести себя по-разному
     *              при create vs update (например картинки не трогать на update).
     */
    private java.util.Map<String,Object> mapDtoToWooProduct(
            CardTovExportOutDto dto,
            boolean forceDraft,
            boolean forUpdate,
            Long knownId
    ) {
        java.util.Map<String,Object> m = new java.util.HashMap<>();

        // Если знаем id товара в Woo - ставим
        if (knownId != null && knownId > 0) {
            m.put("id", knownId);
        } else {
            // иначе Woo будет матчить по sku
            if (dto.sku() != null && !dto.sku().isBlank()) {
                m.put("sku", dto.sku().trim());
            }
        }

        // Название
        if (dto.name() != null && !dto.name().isBlank()) {
            m.put("name", dto.name().trim());
        }

        // Контент (описание)
        if (dto.description() != null && !dto.description().isBlank()) {
            m.put("description", dto.description());
        }

        // Статус публикации
        {
            String st = "draft";
            Integer srcStatus = dto.status();
            if (!forceDraft && srcStatus != null && srcStatus.intValue() == 1) {
                st = "publish";
            }
            m.put("status", st);
        }

        // Вес
        if (dto.weight() != null) {
            m.put("weight", dto.weight().toString());
        }

        // Габариты (length/width/height)
        java.util.Map<String,Object> dims = new java.util.HashMap<>();
        if (dto.length() != null) dims.put("length", dto.length().toString());
        if (dto.width()  != null) dims.put("width",  dto.width().toString());
        if (dto.height() != null) dims.put("height", dto.height().toString());
        if (!dims.isEmpty()) {
            m.put("dimensions", dims);
        }

        // Категория
        if (dto.groupId() != null && dto.groupId() > 0) {
            java.util.List<java.util.Map<String,Object>> cats = new java.util.ArrayList<>();
            java.util.Map<String,Object> catObj = new java.util.HashMap<>();
            catObj.put("id", dto.groupId());
            cats.add(catObj);
            m.put("categories", cats);
        }

        // Картинка
        // Политика такая:
        // - при create: всегда ставь картинку (новый товар, ок)
        // - при update: можно тоже ставить для простоты, пока не оптимизируем дубликаты
        /*if (dto.img() != null && !dto.img().isBlank()) {
            java.util.List<java.util.Map<String,Object>> images = new java.util.ArrayList<>();
            java.util.Map<String,Object> imgObj = new java.util.HashMap<>();
            imgObj.put("src", dto.img().trim());
            images.add(imgObj);
            m.put("images", images);
        }*/

        // --- meta_data ---
        java.util.List<java.util.Map<String,Object>> meta = new java.util.ArrayList<>();

        // 1) контрольная сумма карточки, чтобы Woo-side плагин мог понять что мы уже налили
        if (dto.hash() != null && !dto.hash().isBlank()) {
            java.util.Map<String,Object> md = new java.util.HashMap<>();
            md.put("key", "_ms_hash");
            md.put("value", dto.hash());
            meta.add(md);
        }

        // 2) GTIN (штрихкод) -> _wc_gtin_code
        if (dto.globalUniqueId() != null && !dto.globalUniqueId().isBlank()) {
            java.util.Map<String,Object> md = new java.util.HashMap<>();
            md.put("key", "_wc_gtin_code");
            md.put("value", dto.globalUniqueId().trim());
            meta.add(md);
        }

        // 3) Размеры/упаковка
        if (dto.edinIzmer() != null && !dto.edinIzmer().isBlank()) {
            java.util.Map<String,Object> md = new java.util.HashMap<>();
            md.put("key", "_edin_izmer");
            md.put("value", dto.edinIzmer().trim());
            meta.add(md);
        }
        if (dto.razmIzmer() != null && !dto.razmIzmer().isBlank()) {
            java.util.Map<String,Object> md = new java.util.HashMap<>();
            md.put("key", "_razm_izmer");
            md.put("value", dto.razmIzmer().trim());
            meta.add(md);
        }
        if (dto.vesEdinic() != null) {
            java.util.Map<String, Object> md = new java.util.HashMap<>();
            md.put("key", "_ves_edinic");
            md.put("value", String.format(java.util.Locale.US, "%.3f", dto.vesEdinic()));
            meta.add(md);
        }

        if (!meta.isEmpty()) {
            m.put("meta_data", meta);
        }

        return m;
    }

    private static void collectCategoryDesc(
            Map<Long,String> acc,
            Long groupId,
            String grDescr
    ) {
        if (groupId == null || groupId <= 0) return;
        if (grDescr == null || grDescr.isBlank()) return;
        acc.put(groupId, grDescr);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
    /**
     * Разбивает большой batch-пакет WooCommerce (/products/batch)
     * на подсписки не более maxBatchSize элементов (create+update).
     *
     * Пример:
     *   payload = { "create":[...120 шт...], "update":[...30 шт...] }
     *   splitBatchPayload(payload, 100)
     *   → вернёт список из 2 подпакаетов (100 и 50 элементов)
     */
    private List<Map<String, Object>> splitBatchPayload(Map<String, Object> payload, int maxBatchSize) {
        if (payload == null || payload.isEmpty()) {
            return List.of();
        }
        boolean big;
        if(payload.size()>99)
            big=true;
        // исходные списки
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> createList = (List<Map<String, Object>>) payload.getOrDefault("create", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> updateList = (List<Map<String, Object>>) payload.getOrDefault("update", List.of());

        List<Map<String, Object>> result = new ArrayList<>();

        // Объединяем create и update в одну "очередь", но помним тип
        List<Map.Entry<String, Map<String, Object>>> all = new ArrayList<>();
        createList.forEach(m -> all.add(Map.entry("create", m)));
        updateList.forEach(m -> all.add(Map.entry("update", m)));

        // Режем на чанки по maxBatchSize
        for (int i = 0; i < all.size(); i += maxBatchSize) {
            int end = Math.min(i + maxBatchSize, all.size());
            List<Map.Entry<String, Map<String, Object>>> sub = all.subList(i, end);

            // восстанавливаем структуру {create:[..], update:[..]}
            Map<String, Object> subPayload = new HashMap<>();
            List<Map<String, Object>> subCreate = new ArrayList<>();
            List<Map<String, Object>> subUpdate = new ArrayList<>();

            for (var e : sub) {
                if (e.getKey().equals("create")) {
                    subCreate.add(e.getValue());
                } else {
                    subUpdate.add(e.getValue());
                }
            }

            if (!subCreate.isEmpty()) subPayload.put("create", subCreate);
            if (!subUpdate.isEmpty()) subPayload.put("update", subUpdate);
            result.add(subPayload);
        }

        return result;
    }
}