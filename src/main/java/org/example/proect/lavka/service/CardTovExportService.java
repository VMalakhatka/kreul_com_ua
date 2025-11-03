package org.example.proect.lavka.service;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dao.CardTovExportDao;
import org.example.proect.lavka.dto.CardTovExportDto;
import org.example.proect.lavka.dto.CardTovExportOutDto;
import org.example.proect.lavka.service.category.WooCategoryService;
import org.example.proect.lavka.utils.category.CatPathUtil;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CardTovExportService {

    private final CardTovExportDao dao;
    private final WooCategoryService wooCategoryService;

    // ====== вход от фронта (без изменений) ======
    public record ItemHash(String sku, @Nullable String hash) {}

    // 🔁 НОВЫЙ ответ: только полный список карточек + пагинация
    public record DiffResult(
            String nextAfter,
            boolean last,
            List<CardTovExportOutDto> toUpdateFull,
            List<String> toDelete,
            List<CardTovExportOutDto> toCreateFull
    ) {}

    // ====== Публичные методы ======

    /** Старая страничная выдача — без изменений (только добавьте hash в out DTO) */
    public PageResult page(String afterSku, int limit) {
        List<CardTovExportDto> items = dao.findPage(afterSku, limit);

        // батч-обеспечение категорий для всех элементов страницы
        Map<String, Long> idsByHash = ensureCategoriesForDtos(items);

        List<CardTovExportOutDto> mapped = items.stream()
                .map(d -> mapWithGroupCached(d, idsByHash))
                .map(this::withHash)
                .toList();

        String nextAfter = mapped.isEmpty() ? null : mapped.get(mapped.size() - 1).sku();
        boolean lastPage = mapped.isEmpty() || mapped.size() < Math.max(1, limit);
        return new PageResult(mapped, nextAfter, lastPage);
    }

    public DiffResult diffPage(String afterSku, int limit, List<ItemHash> seen) {
        // 1) Woo → карта sku->hash
        Map<String, String> wooBySku = (seen == null ? List.<ItemHash>of() : seen).stream()
                .filter(it -> it != null && notBlank(it.sku()))
                .collect(Collectors.toMap(it -> it.sku().trim(), it -> nz(it.hash()), (a,b)->a));

        List<String> wooSkus = new ArrayList<>(wooBySku.keySet());
        Collections.sort(wooSkus);


        // исходный максимум из входного окна Woo (до удаления toDelete)
        final String originalMaxWoo = wooSkus.isEmpty() ? null : wooSkus.get(wooSkus.size() - 1);

        // Если Woo ничего не прислал — вернём обычную страницу (инициализация до диффа)
        if (wooSkus.isEmpty()) {
            var page = page(afterSku, limit); // тут уже withHash() считает с groupId
            return new DiffResult(page.nextAfter(), page.last(), List.of(), List.of(), page.items());
        }

        // 2) По присланным SKU получаем данные из MSSQL (ровно по списку)
        List<CardTovExportDto> fromMsByIn = dao.findBySkus(wooSkus);
        Set<String> foundInMs = fromMsByIn.stream().map(CardTovExportDto::getSku).collect(Collectors.toSet());

        // toDelete: есть в Woo, нет в MSSQL
        List<String> toDelete = wooSkus.stream()
                .filter(sku -> !foundInMs.contains(sku))
                .toList();

        // Вычистим кандидатов на удаление из рабочих множеств, чтобы не попасть в update/add
        wooSkus.removeAll(toDelete);
        wooBySku.keySet().removeAll(toDelete);

        // ⚠️ Если всё, что прислал Woo, оказалось к удалению — апдейтов нет
        if (wooSkus.isEmpty()) {
            int cap      = Math.min(1000, Math.max(1, limit));
            int capPlus1 = cap + 1;
            boolean moreCreates = false;

            // 1) берём «сырой» список кандидатов на ADD (без вызова категорий в стриме)
            List<CardTovExportDto> addRaw =
                    (afterSku == null || afterSku.isEmpty())
                            ? dao.findPage(null, capPlus1)
                            : dao.findGreaterThan(afterSku, capPlus1);

            // 2) одним батчем обеспечиваем категории
            Map<String, Long> idsByHashAdd = ensureCategoriesForDtos(addRaw);

            // 3) маппим с использованием кэша категорий и считаем hash
            List<CardTovExportOutDto> toCreateFull = addRaw.stream()
                    .map(d -> mapWithGroupCached(d, idsByHashAdd))
                    .map(this::withHash)
                    .toList();

            // 4) пагинация
            if (toCreateFull.size() > cap) {
                moreCreates = true;
                toCreateFull = toCreateFull.subList(0, cap);
            }

            String lastAdd = toCreateFull.isEmpty() ? null : toCreateFull.get(toCreateFull.size() - 1).sku();

            // ► nextAfter по правилу:
            String nextAfter = moreCreates
                    ? lastAdd                          // продолжаем листать ADD
                    : maxSku(lastAdd, originalMaxWoo); // ADD закончился — якоримся на максимум окна

            boolean last = !moreCreates;
            return new DiffResult(nextAfter, last, List.of(), toDelete, toCreateFull);
        }

// 3) Снимок для UPDATE: сначала обеспечим категории батчем, затем мапим + hash
        Map<String, Long> idsByHashForSeen = ensureCategoriesForDtos(fromMsByIn);

        List<CardTovExportOutDto> msSnapshot = fromMsByIn.stream()
                .map(d -> mapWithGroupCached(d, idsByHashForSeen))
                .map(this::withHash)
                .toList();

        Map<String, CardTovExportOutDto> msBySku = msSnapshot.stream()
                .collect(Collectors.toMap(CardTovExportOutDto::sku, x -> x, (a, b) -> a));

        List<CardTovExportOutDto> toUpdateFull = wooSkus.stream()
                .map(msBySku::get)
                .filter(Objects::nonNull)
                .filter(o -> !Objects.equals(o.hash(), wooBySku.get(o.sku())))
                .toList();

        // 5) ADD (toCreateFull) — работаем через сырой список DTO → батч категорий → маппинг + hash
        int cap      = Math.min(1000, Math.max(1, limit));
        int capPlus1 = cap + 1;
        boolean moreCreates = false;
        List<CardTovExportOutDto> toCreateFull;

        if (afterSku == null || afterSku.isEmpty()) {
            // CASE A: забираем сырьё - if 0 - can break
            List<CardTovExportDto> addRaw = dao.findLessThanExcluding(originalMaxWoo, wooSkus, capPlus1);
            // батч категорий
            Map<String, Long> idsByHashAdd = ensureCategoriesForDtos(addRaw);
            // маппинг + hash
            toCreateFull = addRaw.stream()
                    .map(d -> mapWithGroupCached(d, idsByHashAdd))
                    .map(this::withHash)
                    .toList();
        } else {
            // CASE B: забираем сырьё
            List<CardTovExportDto> addRaw = dao.findBetweenExcluding(afterSku, originalMaxWoo, wooSkus, capPlus1);
            Map<String, Long> idsByHashAdd = ensureCategoriesForDtos(addRaw);
            toCreateFull = addRaw.stream()
                    .map(d -> mapWithGroupCached(d, idsByHashAdd))
                    .map(this::withHash)
                    .toList();
        }

        if (toCreateFull.size() > cap) {
            moreCreates = true;
            toCreateFull = toCreateFull.subList(0, cap);
        }

        String lastAdd = toCreateFull.isEmpty() ? null : toCreateFull.get(toCreateFull.size() - 1).sku();
        String nextAfter = moreCreates ? lastAdd : maxSku(lastAdd, originalMaxWoo);
        boolean last = !moreCreates;

            return new DiffResult(nextAfter, last, toUpdateFull, toDelete, toCreateFull);
    }
    // ====== приватные вспомогалки ======

    /** Ваш существующий маппер + получение groupId */
    private CardTovExportOutDto mapWithGroup(CardTovExportDto d) {
        String l1 = nz(d.getNGROUP_TVR());
        String l2 = nz(d.getNGROUP_TV2());
        String l3 = nz(d.getNGROUP_TV3());
        String l4 = nz(d.getNGROUP_TV4());
        String l5 = nz(d.getNGROUP_TV5());
        String l6 = nz(d.getNGROUP_TV6());

        Long groupId = null;
        if (notBlank(l1) || notBlank(l2) || notBlank(l3) || notBlank(l4) || notBlank(l5) || notBlank(l6)) {
            groupId = wooCategoryService.ensureCategoryPath(
                    emptyToNull(l1), emptyToNull(l2), emptyToNull(l3),
                    emptyToNull(l4), emptyToNull(l5), emptyToNull(l6)
            );
        }

        return new CardTovExportOutDto(
                d.getSku(),
                d.getName(),
                d.getImg(),
                d.getEDIN_IZMER(),
                d.getGlobal_unique_id(),
                d.getWeight(),
                d.getLength(),
                d.getWidth(),
                d.getHeight(),
                d.getStatus(),
                d.getVES_EDINIC(),
                d.getDESCRIPTION(),
                d.getRAZM_IZMER(),
                d.getGr_descr(),
                groupId,
                null // hash добавим ниже
        );
    }

    /** Добавляем hash в out-DTO (record с полем hash в конце) */
    private CardTovExportOutDto withHash(CardTovExportOutDto o) {
        String h = calcHash(o); // теперь включает groupId
        return new CardTovExportOutDto(
                o.sku(), o.name(), o.img(), o.edinIzmer(), o.globalUniqueId(),
                o.weight(), o.length(), o.width(), o.height(), o.status(),
                o.vesEdinic(), o.description(), o.razmIzmer(), o.grDescr(), o.groupId(),
                h
        );
    }

    /** Хеш считаем по канонизированным данным источника + groupId. */
    private String calcHash(CardTovExportOutDto o) {
        String payload = String.join("|",
                nz(o.sku()),
                nz(o.name()),
                nz(o.img()),
                nz(o.edinIzmer()),
                nz(o.globalUniqueId()),
                fmt(o.weight()),
                fmt(o.length()),
                fmt(o.width()),
                fmt(o.height()),
                String.valueOf(o.status() == null ? 0 : o.status()),
                fmt(o.vesEdinic()),
                nz(o.description()),
                nz(o.razmIzmer()),
                nz(o.grDescr()),
                // ВАЖНО: теперь учитываем категорию (groupId)
                String.valueOf(o.groupId() == null ? 0L : o.groupId())
        );
        return sha256hex(payload);
    }

    private static String sha256hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("hash error", e);
        }
    }

    private static String fmt(Number n) { return n == null ? "" : (n instanceof Integer || n instanceof Long) ? String.valueOf(n) : stripTrailingZeros(n.doubleValue()); }
    private static String stripTrailingZeros(double v) {
        String s = Double.toString(v);
        if (s.contains("E") || s.contains("e")) return s; // не трогаем экспоненту
        if (s.indexOf('.') >= 0) {
            while (s.endsWith("0")) s = s.substring(0, s.length()-1);
            if (s.endsWith(".")) s = s.substring(0, s.length()-1);
        }
        return s;
    }
    private static String nz(String s) { return s == null ? "" : s.trim(); }
    private static boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }
    private static String emptyToNull(String s) { return (s == null || s.trim().isEmpty()) ? null : s.trim(); }

    // старая страничная выдача
    public record PageResult(List<CardTovExportOutDto> items, String nextAfter, boolean last) {}

    private static String maxSku(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return (a.compareTo(b) >= 0) ? a : b;
    }

    // ====== приватные вспомогалки для категорий ======

    /** Собирает нормализованный список уровней категории из DTO. Пустые игнорятся. */
    private static List<String> levelsOf(CardTovExportDto d) {
        List<String> lv = new ArrayList<>(6);
        if (notBlank(d.getNGROUP_TVR())) lv.add(d.getNGROUP_TVR().trim());
        if (notBlank(d.getNGROUP_TV2())) lv.add(d.getNGROUP_TV2().trim());
        if (notBlank(d.getNGROUP_TV3())) lv.add(d.getNGROUP_TV3().trim());
        if (notBlank(d.getNGROUP_TV4())) lv.add(d.getNGROUP_TV4().trim());
        if (notBlank(d.getNGROUP_TV5())) lv.add(d.getNGROUP_TV5().trim());
        if (notBlank(d.getNGROUP_TV6())) lv.add(d.getNGROUP_TV6().trim());
        return lv;
    }

    /** Полный путь категории для DTO (на основе уровней). Может быть null, если уровней нет. */
    private static String fullPathOf(CardTovExportDto d) {
        List<String> lv = levelsOf(d);
        if (lv.isEmpty()) return null;
        return CatPathUtil.buildSlicePath(lv, lv.size() - 1);
    }

    /** Полный хеш пути для DTO (или null, если пути нет). */
    private static String fullHashOf(CardTovExportDto d) {
        String p = fullPathOf(d);
        if (p == null) return null;
        return CatPathUtil.sha1(p);
    }

    /** Батч-обеспечение categoryId для набора DTO: возвращает Map<fullHash, termId>. */
    private Map<String, Long> ensureCategoriesForDtos(Collection<CardTovExportDto> dtos) {
        // собираем уникальные пути (List<String> уровней)
        Set<List<String>> uniqPaths = new LinkedHashSet<>();
        for (CardTovExportDto d : dtos) {
            List<String> lv = levelsOf(d);
            if (!lv.isEmpty()) uniqPaths.add(lv);
        }
        if (uniqPaths.isEmpty()) return Map.of();

        // батч в сервис категорий
        return wooCategoryService.ensureCategoryPathsBulk(new ArrayList<>(uniqPaths));
    }

    /** Маппер DTO -> OutDTO, но groupId берём из idsByHash (без вызовов внешнего сервиса). */
    private CardTovExportOutDto mapWithGroupCached(CardTovExportDto d, Map<String, Long> idsByHash) {
        String h = fullHashOf(d);
        Long groupId = (h == null) ? null : idsByHash.get(h);

        return new CardTovExportOutDto(
                d.getSku(),
                d.getName(),
                d.getImg(),
                d.getEDIN_IZMER(),
                d.getGlobal_unique_id(),
                d.getWeight(),
                d.getLength(),
                d.getWidth(),
                d.getHeight(),
                d.getStatus(),
                d.getVES_EDINIC(),
                d.getDESCRIPTION(),
                d.getRAZM_IZMER(),
                d.getGr_descr(),
                groupId,
                null // hash добавим позже через withHash()
        );
    }
}