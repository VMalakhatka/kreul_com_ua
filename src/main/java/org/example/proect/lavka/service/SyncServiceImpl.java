package org.example.proect.lavka.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.example.proect.lavka.client.LavkaLocationsClient;
import org.example.proect.lavka.client.WooApiClient;
import org.example.proect.lavka.dao.wp.WpProductDao;
import org.example.proect.lavka.dto.CardTovExportOutDto;
import org.example.proect.lavka.dto.SeenItem;
import org.example.proect.lavka.dto.sync.SyncRunResponse;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SyncServiceImpl implements SyncService {

    private final WpProductDao wpProductDao;
    private final CardTovExportService cardTovExportService;
    private final WooApiClient wooApiClient;
    private final LavkaLocationsClient lavkaLocationsClient;

    private static final Marker OPS = MarkerFactory.getMarker("OPS");

    @Override
    public SyncRunResponse runOneBatch(
            Integer limit,
            Integer pageSizeWoo,
            String startCursorAfter,
            Boolean dryRun
    ) {
        final String reqId = java.util.UUID.randomUUID().toString();
        MDC.put("reqId", reqId);
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
        MDC.put("dry", String.valueOf(isDry));
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
        try {
            // ===== ВНЕШНИЙ ЦИКЛ =====
            while (true) {

                // Снимок перед началом обработки этого окна Woo
                final String windowStartCursor = globalCursorAfter;
                MDC.put("windowCursor", String.valueOf(windowStartCursor));

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

                // invert to id->sku for logging
                Map<Long,String> id2sku = existingIdsBySku.entrySet().stream()
                        .filter(e -> e.getKey()!=null && e.getValue()!=null)
                        .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey, (a,b)->a));
                org.example.proect.lavka.utils.LogCtx.ID2SKU.set(id2sku);

                // 2.2 В этом окне Woo мы будем крутить ВНУТРЕННИЙ ЦИКЛ,
                //     пока diffPage говорит, что есть ещё create'ов
                String innerAfterSku = globalCursorAfter;   // локальный курсор для diffPage
                boolean firstIterationForThisWindow = true; // чтобы не дублировать update/delete
                boolean windowFinished = false;             // станет true когда diff.last()==true
                // ===== ВНУТРЕННИЙ ЦИКЛ =====
                while (!windowFinished) {

                    MDC.put("innerAfter", String.valueOf(innerAfterSku));
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

                    // считаем, сколько всего штук в этой порции
                    int batchDel = firstIterationForThisWindow ? toDelete.size() : 0;
                    int batchUpd = firstIterationForThisWindow ? toUpdateFull.size() : 0;
                    int batchAdd = toCreateFull.size();
                    int batchProcessed = batchDel + batchUpd + batchAdd;

                    if (batchProcessed != 0) {
                        if (!isDry) {
                            for (CardTovExportOutDto dto : toUpdateFull) {
                                collectCategoryDesc(categoryDescMap, dto.groupId(), dto.grDescr());
                            }
                            for (CardTovExportOutDto dto : toCreateFull) {
                                collectCategoryDesc(categoryDescMap, dto.groupId(), dto.grDescr());
                            }
                        }

                        Map<String, Object> batchPayload = buildWooBatchPayload(
                                toUpdateFull, toCreateFull, toDelete, existingIdsBySku
                        );
                        List<Map<String, Object>> subPayloads = splitBatchPayload(batchPayload, 50); // 50–100 ок
                        if (firstIterationForThisWindow) {
                            totalDrafted += batchDel;  // намерение заdraftить
                            totalUpdated += batchUpd;
                        }
                        if (isDry) {
                            totalCreated += batchAdd;
                        } else {
                            final AtomicInteger batchNo = new AtomicInteger(0);
                            int createdNow = 0, updatedNow = 0;
                            for (Map<String, Object> sub : subPayloads) {
                                int no = batchNo.incrementAndGet();
                                MDC.put("batchNo", String.valueOf(no));

                                String skus = extractSkusFromBatch(sub);
                                log.info(OPS, "[sync.ops] upsert batch start size={} skus={}", countItems(sub), skus);

                                var res = postWithBisect(sub, 1, allErrors);

                                log.info(OPS, "[sync.ops] upsert batch done created={} updated={} size={} skus={}",
                                        res.createdCount(), res.updatedCount(), countItems(sub), skus);

                                if (log.isDebugEnabled()) {
                                    debugListOps(sub);
                                }

                                createdNow += res.createdCount();
                                updatedNow += res.updatedCount();
                            }
                            if (firstIterationForThisWindow) {
                                totalUpdated += batchUpd;   // как и было (логика «за окно» ок)
                                totalDrafted += batchDel;
                            }
                            totalCreated += createdNow;

                            MDC.remove("batchNo"); // очистим ключ после подпакетов
                        }
                    }

                    // 2.2.3 Двигаем локальный курсор и смотрим флаги
                    innerAfterSku = diff.nextAfter();   // может стать последний созданный SKU
                    windowFinished = diff.last();       // true => мы ДОКОНЧАЛИ все create для этого окна
                    firstIterationForThisWindow = false;

                    MDC.remove("innerAfter");

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

                org.example.proect.lavka.utils.LogCtx.ID2SKU.remove();

                MDC.remove("windowCursor");

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
        } finally {
            log.info(OPS, "[sync.ops] summary processed={} created={} updated={} drafted={} nextAfter={} stoppedByLimit={}",
                    totalProcessed, totalCreated, totalUpdated, totalDrafted, globalCursorAfter, stopeedByLimit);

            MDC.clear();
        }
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

    private WooApiClient.WooBatchResult postWithBisect(Map<String,Object> sub, int minSize,
                                                       List<String> allErrors) {
        try {
            return wooApiClient.upsertProductsBatch(sub);
        } catch (Exception e) {
            // соберём список sku/id для лога
            String skus = extractSkusFromBatch(sub);
            // если уже слишком мелко — логируем и сдаёмся
            int total = countItems(sub);
            log.warn("[sync.errors] woo_batch_failed size={} skus={} msg={}", total, skus, e.getMessage());
            if (total <= minSize) {
                allErrors.add("woo_batch_failed(final "+total+"): " + e.getMessage()
                        + " skus=[" + skus + "]");
                log.error("[sync.errors] final-fail size={} skus={} err", total, skus, e);
                return new WooApiClient.WooBatchResult(0,0);
            }
            // делим пополам
            var halves = splitInHalf(sub);
            var r1 = postWithBisect(halves.get(0), minSize, allErrors);
            var r2 = postWithBisect(halves.get(1), minSize, allErrors);
            return new WooApiClient.WooBatchResult(r1.createdCount()+r2.createdCount(),
                    r1.updatedCount()+r2.updatedCount());
        }
    }

    @SuppressWarnings("unchecked")
    private String extractSkusFromBatch(Map<String,Object> payload){
        List<Map<String,Object>> c = (List<Map<String,Object>>) payload.getOrDefault("create", List.of());
        List<Map<String,Object>> u = (List<Map<String,Object>>) payload.getOrDefault("update", List.of());
        List<String> out = new ArrayList<>();

        // доступ к id->sku из ThreadLocal
        Map<Long,String> id2sku = org.example.proect.lavka.utils.LogCtx.ID2SKU.get();

        for (var m: c) {
            String sku = valAsString(m.get("sku"));
            if (sku == null) {
                sku = skuFromIdMap(m.get("id"), id2sku);
            }
            out.add(sku != null ? sku : valAsString(m.get("id")));
        }
        for (var m: u) {
            String sku = valAsString(m.get("sku"));
            if (sku == null) {
                sku = skuFromIdMap(m.get("id"), id2sku);
            }
            out.add(sku != null ? sku : valAsString(m.get("id")));
        }
        return String.join(",", out);
    }

    private static String valAsString(Object o){
        return (o == null) ? null : String.valueOf(o);
    }
    private static String skuFromIdMap(Object idObj, Map<Long,String> id2sku){
        if (idObj == null || id2sku == null) return null;
        try {
            long id = Long.parseLong(String.valueOf(idObj));
            return id2sku.get(id);
        } catch (NumberFormatException ignore) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private int countItems(Map<String,Object> payload){
        return ((List<?>)payload.getOrDefault("create", List.of())).size()
                + ((List<?>)payload.getOrDefault("update", List.of())).size();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> splitInHalf(Map<String,Object> payload){
        List<Map.Entry<String, Map<String,Object>>> all = new ArrayList<>();
        ((List<Map<String,Object>>)payload.getOrDefault("create", List.of()))
                .forEach(m -> all.add(Map.entry("create", m)));
        ((List<Map<String,Object>>)payload.getOrDefault("update", List.of()))
                .forEach(m -> all.add(Map.entry("update", m)));
        int mid = Math.max(1, all.size()/2);
        return List.of(rebuildPayload(all.subList(0, mid)),
                rebuildPayload(all.subList(mid, all.size())));
    }

    private Map<String,Object> rebuildPayload(List<Map.Entry<String, Map<String,Object>>> items){
        Map<String,Object> p = new HashMap<>();
        List<Map<String,Object>> c = new ArrayList<>(), u = new ArrayList<>();
        for (var e: items) if ("create".equals(e.getKey())) c.add(e.getValue()); else u.add(e.getValue());
        if (!c.isEmpty()) p.put("create", c);
        if (!u.isEmpty()) p.put("update", u);
        return p;
    }

    @SuppressWarnings("unchecked")
    private void debugListOps(Map<String,Object> sub){
        try {
            List<Map<String,Object>> c = (List<Map<String,Object>>) sub.getOrDefault("create", List.of());
            List<Map<String,Object>> u = (List<Map<String,Object>>) sub.getOrDefault("update", List.of());
            for (var m: c) log.debug(OPS, "[sync.ops] CREATE sku={} id?={}", m.get("sku"), m.get("id"));
            for (var m: u) {
                String st = String.valueOf(m.getOrDefault("status", ""));
                if ("draft".equals(st)) log.debug(OPS, "[sync.ops] DRAFT sku/id={}/{}", m.get("sku"), m.get("id"));
                else                    log.debug(OPS, "[sync.ops] UPDATE sku/id={}/{}", m.get("sku"), m.get("id"));
            }
        } catch (Exception ignore) {}
    }

}