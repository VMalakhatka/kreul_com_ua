package org.example.proect.lavka.service;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dao.CardTovExportDao;
import org.example.proect.lavka.dao.wp.WpProductDao;
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
    private final WpProductDao wpProductDao;

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
// 1) Собрали норм-представление Woo
        List<String> wooSkusRaw = (seen == null ? List.<ItemHash>of() : seen).stream()
                .filter(it -> it != null && notBlank(it.sku()))
                .map(ItemHash::sku)
                .toList();

        Map<String, String> wooHashByNorm = (seen == null ? List.<ItemHash>of() : seen).stream()
                .filter(it -> it != null && notBlank(it.sku()))
                .collect(Collectors.toMap(
                        it -> nz(it.sku()),  // КЛЮЧ = НОРМАЛИЗОВАННЫЙ SKU
                        it -> nz(it.hash()),
                        (a, b) -> a
                ));

        List<String> wooSkusNorm = new ArrayList<>(wooHashByNorm.keySet());
        Collections.sort(wooSkusNorm);

        final String originalMaxWooNorm = wooSkusNorm.isEmpty() ? null : wooSkusNorm.get(wooSkusNorm.size() - 1);

// norm → raw (для возврата RAW в toDelete и для якоря nextAfter)
        Map<String, String> normToRaw = new HashMap<>();
        for (String raw : wooSkusRaw) normToRaw.putIfAbsent(nz(raw), raw);

// Пустое окно Woo → обычная страница
        if (wooSkusNorm.isEmpty()) {
            var page = page(afterSku, limit);
            return new DiffResult(page.nextAfter(), page.last(), List.of(), List.of(), page.items());
        }

// 2) MS снимок по RAW-списку
        List<CardTovExportDto> fromMsByIn = dao.findBySkus(wooSkusRaw);
        Set<String> foundInMsNorm = fromMsByIn.stream()
                .map(CardTovExportDto::getSku)
                .map(CardTovExportService::nz)
                .collect(Collectors.toSet());

// toDelete: те норм-SKU, которых нет в MS
        List<String> toDeleteRaw = wooSkusNorm.stream()
                .filter(norm -> !foundInMsNorm.contains(norm))
                .map(normToRaw::get)
                .filter(Objects::nonNull)
                .toList();

// ❗️Остаток окна Woo после удаления
        Set<String> remainingNorm = new LinkedHashSet<>(wooSkusNorm);
        remainingNorm.removeAll(toDeleteRaw.stream().map(CardTovExportService::nz).collect(Collectors.toSet()));

// Если после удаления никого не осталось → нет UPDATE, только ADD
        if (remainingNorm.isEmpty()) {
            int cap = Math.min(1000, Math.max(1, limit));
            int capPlus1 = cap + 1;
            boolean moreCreates = false;

            // Берём кандидатов на ADD без SQL-exclude, фильтруем в Java по норм-ключам
            List<CardTovExportDto> addRawDtos =
                    (afterSku == null || afterSku.isEmpty())
                            ? dao.findPage(null, capPlus1)
                            : dao.findGreaterThan(afterSku, capPlus1);
            if(!addRawDtos.isEmpty())
                addRawDtos=filterCreatesThatAlreadyExistInWooDtos(addRawDtos);

            addRawDtos = addRawDtos.stream()
                    .filter(d -> !wooSkusNorm.contains(nz(d.getSku())))
                    .toList();

            Map<String, Long> idsByHashAdd = ensureCategoriesForDtos(addRawDtos);
            List<CardTovExportOutDto> toCreateFull = addRawDtos.stream()
                    .map(d -> mapWithGroupCached(d, idsByHashAdd))
                    .map(this::withHash)
                    .toList();

            if (toCreateFull.size() > cap) {
                moreCreates = true;
                toCreateFull = toCreateFull.subList(0, cap);
            }

            String lastAddRaw = toCreateFull.isEmpty() ? null : toCreateFull.get(toCreateFull.size() - 1).sku();
            String anchorRaw = (originalMaxWooNorm == null) ? null : normToRaw.get(originalMaxWooNorm);

            String nextAfter = moreCreates ? lastAddRaw : maxSku(lastAddRaw, anchorRaw);
            boolean last = !moreCreates;

            return new DiffResult(nextAfter, last, List.of(), toDeleteRaw, toCreateFull);
        }

       // ===UPDATE - путь:работаем по ОСТАТКУ == =

// Снимок MS → out + hash
                Map < String, Long > idsByHashForSeen = ensureCategoriesForDtos(fromMsByIn);
        List<CardTovExportOutDto> msSnapshot = fromMsByIn.stream()
                .map(d -> mapWithGroupCached(d, idsByHashForSeen))
                .map(this::withHash)
                .toList();

// Индексируем MS по НОРМу
        Map<String, CardTovExportOutDto> msByNorm = msSnapshot.stream()
                .collect(Collectors.toMap(o -> nz(o.sku()), x -> x, (a, b) -> a));

// toUpdateFull: только по оставшимся элементам окна Woo
        List<CardTovExportOutDto> toUpdateFull = remainingNorm.stream()
                .map(msByNorm::get)
                .filter(Objects::nonNull)
                .filter(o -> !Objects.equals(o.hash(), wooHashByNorm.get(nz(o.sku()))))
                .toList();

// ADD: также без SQL-exclude, фильтр по norm в Java
        int cap = Math.min(1000, Math.max(1, limit));
        int capPlus1 = cap + 1;
        boolean moreCreates = false;
        List<CardTovExportOutDto> toCreateFull;
        {
            String anchorRaw = (originalMaxWooNorm == null) ? null : normToRaw.get(originalMaxWooNorm);
            Set<String> presentNorm = new HashSet<>(wooSkusNorm);

            List<CardTovExportDto> addRawDtos =
                    (afterSku == null || afterSku.isEmpty())
                            ? dao.findLessThanExcluding(anchorRaw, presentNorm, capPlus1)
                            : dao.findBetweenExcluding(afterSku, anchorRaw, presentNorm, capPlus1);
            if(!addRawDtos.isEmpty())
                addRawDtos=filterCreatesThatAlreadyExistInWooDtos(addRawDtos);

            addRawDtos = addRawDtos.stream()
                    .map(d -> d) // no-op
                    .filter(d -> !presentNorm.contains(nz(d.getSku())))
                    .toList();

            Map<String, Long> idsByHashAdd = ensureCategoriesForDtos(addRawDtos);
            toCreateFull = addRawDtos.stream()
                    .map(d -> mapWithGroupCached(d, idsByHashAdd))
                    .map(this::withHash)
                    .toList();

            if (toCreateFull.size() > cap) {
                moreCreates = true;
                toCreateFull = toCreateFull.subList(0, cap);
            }

            String lastAddRaw = toCreateFull.isEmpty() ? null : toCreateFull.get(toCreateFull.size() - 1).sku();
            String nextAfter = moreCreates ? lastAddRaw : maxSku(lastAddRaw, anchorRaw);
            boolean last = !moreCreates;

            return new DiffResult(nextAfter, last, toUpdateFull, toDeleteRaw, toCreateFull);
        }
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
    private static String nz(String s) {
        if (s == null) return "";
        return s.replace('\u00A0', ' ').trim();
    }

    private static boolean notBlank(String s) { return !nz(s).isEmpty(); }
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

    // внутри CardTovExportService

    /** Фильтрует кандидатов на CREATE (DTO) которые уже существуют в Woo (по нормализованному SKU). */
    private List<CardTovExportDto> filterCreatesThatAlreadyExistInWooDtos(List<CardTovExportDto> creates) {
        if (creates == null || creates.isEmpty()) return creates;

        // Соберём raw-список SKU из MSSQL DTO
        List<String> candSkusRaw = creates.stream()
                .map(CardTovExportDto::getSku)
                .filter(Objects::nonNull)
                .toList();
        if (candSkusRaw.isEmpty()) return creates;

        // 1) Узнаём какие из этих SKU уже есть в Woo (sku -> postId)
        //    Реализация: SELECT post_id, meta_value AS sku FROM wp_postmeta WHERE meta_key='_sku' AND meta_value IN (:candSkusRaw)
        Map<String, Long> existsBySkuRaw = wpProductDao.findIdsBySkus(candSkusRaw);

        // 2) Нормализуем ключи Woo для сравнения (NBSP→space + trim)
        Set<String> existsNorm = existsBySkuRaw.keySet().stream()
                .filter(Objects::nonNull)
                .map(CardTovExportService::nz)
                .collect(Collectors.toSet());

        // 3) Выкидываем из creates те, чей нормализованный SKU уже найден в Woo
        return creates.stream()
                .filter(d -> d != null && !existsNorm.contains(nz(d.getSku())))
                .toList();
    }
}