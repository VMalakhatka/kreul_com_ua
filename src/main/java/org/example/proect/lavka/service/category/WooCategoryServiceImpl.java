package org.example.proect.lavka.service.category;

import jakarta.annotation.Nullable;
import org.example.proect.lavka.client.WooApiClient;
import org.example.proect.lavka.dao.category.LavkaCatmapRepository;
import org.example.proect.lavka.dto.category.WooCategory;
import org.example.proect.lavka.entity.category.LavkaCatmap;
import org.example.proect.lavka.utils.category.CatPathUtil;
import org.example.proect.lavka.utils.category.Slugify;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class WooCategoryServiceImpl implements WooCategoryService {

    private final LavkaCatmapRepository repo;
    private final WooApiClient woo;
    private final Slugify slugify;

    // Кэш: полный хеш пути -> term_id конечной категории
    private final ConcurrentHashMap<String, Long> termByHash = new ConcurrentHashMap<>();
    // (опционально) обратный кэш, если понадобятся быстрые обратные lookup
    private final ConcurrentHashMap<Long, String> hashByTerm = new ConcurrentHashMap<>();

    public WooCategoryServiceImpl(LavkaCatmapRepository repo, WooApiClient woo, Slugify slugify) {
        this.repo = repo;
        this.woo = woo;
        this.slugify = slugify;
    }

    // ---------- Публичные методы ----------

    // --- public API остаётся прежним ---
// 1) Одиночный путь (строки):
    @Override
    @Transactional
    public long ensureCategoryPath(@Nullable String l1, @Nullable String l2, @Nullable String l3,
                                   @Nullable String l4, @Nullable String l5, @Nullable String l6) {
        return ensureCategoryPath(CatPathUtil.levelsArrayList(l1, l2, l3, l4, l5, l6));
    }

    // 2) Одиночный путь (List)
    @Override
    @Transactional
    public long ensureCategoryPath(List<String> levelsRaw) {
        final List<String> levels = CatPathUtil.levelsArrayList(levelsRaw);
        if (levels.isEmpty()) throw new IllegalArgumentException("Category path is empty");

        final String fullPath = CatPathUtil.buildSlicePath(levels, levels.size() - 1);
        final String fullHash = CatPathUtil.sha1(fullPath);

        // a) кэш
        Long cached = termByHash.get(fullHash);
        if (cached != null) return cached;

        // b) БД (конечный срез есть? — тогда всё)
        var fullOpt = repo.findByPathHash(fullHash);
        if (fullOpt.isPresent() && fullOpt.get().getWcTermId() != null) {
            long tid = fullOpt.get().getWcTermId();
            cachePut(fullHash, tid);
            return tid;
        }

        // c) тяжёлая часть вынесена сюда:
        return buildAndBindPath(levels, fullHash);
    }

    // 3) Батч (List<List<String>>) — вызываем buildAndBindPath() только для «остатков»
    @Override
    @Transactional
    public Map<String, Long> ensureCategoryPathsBulk(List<List<String>> pathsRaw) {
        if (pathsRaw == null || pathsRaw.isEmpty()) return Map.of();

        record Req(List<String> levels, String fullPath, String fullHash) {}
        // дедуп по fullHash
        Map<String, Req> unique = new LinkedHashMap<>();
        for (List<String> raw : pathsRaw) {
            List<String> lv = CatPathUtil.levelsArrayList(raw);
            if (lv.isEmpty()) continue;
            String fullPath = CatPathUtil.buildSlicePath(lv, lv.size() - 1);
            String fullHash = CatPathUtil.sha1(fullPath);
            unique.putIfAbsent(fullHash, new Req(lv, fullPath, fullHash));
        }

        Map<String, Long> result = new HashMap<>();
        List<Req> toLookup = new ArrayList<>();

        // 1) кэш
        for (Req r : unique.values()) {
            Long cached = termByHash.get(r.fullHash);
            if (cached != null) result.put(r.fullHash, cached);
            else toLookup.add(r);
        }
        if (toLookup.isEmpty()) return result;

        // 2) БД пачкой
        List<String> hashes = toLookup.stream().map(Req::fullHash).toList();
        List<LavkaCatmap> rows = repo.findAllByPathHashIn(hashes);
        Map<String, LavkaCatmap> dbByHash = rows.stream()
                .collect(Collectors.toMap(LavkaCatmap::getPathHash, x -> x, (a,b)->a));

        List<Req> toCreate = new ArrayList<>();
        for (Req r : toLookup) {
            LavkaCatmap cm = dbByHash.get(r.fullHash);
            if (cm != null && cm.getWcTermId() != null) {
                long tid = cm.getWcTermId();
                result.put(r.fullHash, tid);
                cachePut(r.fullHash, tid);
            } else {
                toCreate.add(r);
            }
        }

        // 3) Остатки — только здесь запускаем «тяжёлую» сборку
        for (Req r : toCreate) {
            long tid = buildAndBindPath(r.levels, r.fullHash);
            result.put(r.fullHash, tid);
            // cachePut внутри buildAndBindPath уже сделает, но второй раз не повредит
            cachePut(r.fullHash, tid);
        }

        return result;
    }

// --- новая инкапсулированная «тяжёлая» часть ---
    /**
     * «Тяжёлая» стадия: найти самый длинный привязанный префикс в БД,
     * достроить недостающие уровни в Woo, сделать upsert всех срезов,
     * закешировать результат. Предполагается, что кэш/БД уже проверены снаружи.
     */
    private long buildAndBindPath(List<String> levels, String fullHash) {
        BoundPrefix p = findLongestBoundPrefix(levels);
        WooCategory last = buildChainFromIndex(levels, p.index(), p.parentTermId());
        long termId = Objects.requireNonNull(last, "Woo creation failed").getId();
        cachePut(fullHash, termId);
        return termId;
    }


    @Override
    public Optional<Long> getCachedTermIdByHash(String fullHash) {
        return Optional.ofNullable(termByHash.get(fullHash));
    }

    @Override
    public void putCache(String fullHash, long termId) {
        cachePut(fullHash, termId);
    }

    // ---------- Приватные шаги/хелперы ----------

    private void cachePut(String fullHash, long termId) {
        termByHash.put(fullHash, termId);
        hashByTerm.put(termId, fullHash);
    }

    /** Результат поиска привязанного префикса: индекс последнего привязанного уровня и его termId (как parent). */
    private record BoundPrefix(int index, Long parentTermId) {}

    /** Ищем самый длинный уже привязанный префикс в БД. Если ничего нет — index = -1, parentTermId = null. */
    private BoundPrefix findLongestBoundPrefix(List<String> levels) {
        if (levels == null || levels.size() <= 1) {
            return new BoundPrefix(-1, null);
        }
        // идём от самого длинного префикса к короткому
        for (int i = levels.size() - 2; i >= 0; i--) {
            String slice     = CatPathUtil.buildSlicePath(levels, i);
            String sliceHash = CatPathUtil.sha1(slice);

            // 1) пробуем кэш
            Long cachedTid = termByHash.get(sliceHash);
            if (cachedTid != null) {
                return new BoundPrefix(i, cachedTid);
            }
            var opt = repo.findByPathHash(sliceHash);
            if (opt.isPresent()) {
                Long tid = opt.get().getWcTermId();
                if (tid != null) {
                    cachePut(sliceHash, tid);
                    return new BoundPrefix(i, tid);
                }
            }
        }
        return new BoundPrefix(-1, null);
    }

    /**
     * Строит (или находит) цепочку уровней в Woo с i = startIdx+1 до листа.
     * На каждом уровне делает upsert в wp_lavka_catmap.
     * Возвращает найденную/созданную конечную категорию.
     */
    private WooCategory buildChainFromIndex(List<String> levels, int startIdx, Long parentId) {
        WooCategory last = null;
        for (int i = startIdx + 1; i < levels.size(); i++) {
            String name = levels.get(i);

            // 1) найти по имени+parent в Woo
            WooCategory found = woo.findCategoryByNameAndParent(name, parentId);

            // 2) если нет — создать с предсказуемым slug (по полному пути до уровня)
            if (found == null) {
                String pathToLevel = CatPathUtil.buildSlicePath(levels, i);
                String baseSlug = slugify.slug(pathToLevel);
                found = woo.createCategoryUnique(name, baseSlug, parentId, pathToLevel);
            }

            // 3) upsert среза [0..i] в нашу БД (идемпотентно)
            upsertSlice(levels, i, found);

            last = found;
            parentId = found.getId();
        }
        return last;
    }

    /** UPSERT одного среза пути [0..i] в wp_lavka_catmap с «выравниванием» к Woo. */
    private void upsertSlice(List<String> levels, int i, WooCategory found) {
        String slice     = CatPathUtil.buildSlicePath(levels, i);
        String sliceHash = CatPathUtil.sha1(slice);

        Long wcParent = (found.getParent() == null || found.getParent() == 0L) ? null : found.getParent();
        int updated   = repo.updateWooBinding(sliceHash, wcParent, found.getId(), found.getSlug());

        if (updated == 0) {
            LavkaCatmap rec = new LavkaCatmap();
            rec.setPathText(slice);
            rec.setDepth(i + 1);

            // l1..l6 «по месту», с безопасной адресацией
            String[] lv = levels.toArray(new String[0]);
            if (i >= 0) rec.setL1(lv[0]);
            if (i >= 1) rec.setL2(lv[1]);
            if (i >= 2) rec.setL3(lv[2]);
            if (i >= 3) rec.setL4(lv[3]);
            if (i >= 4) rec.setL5(lv[4]);
            if (i >= 5) rec.setL6(lv[5]);

            // parent_path_hash
            String parentSlice = (i > 0) ? CatPathUtil.buildSlicePath(levels, i - 1) : null;
            rec.setParentPathHash(parentSlice != null ? CatPathUtil.sha1(parentSlice) : null);

            // привязка к Woo
            rec.setWcParentId(wcParent);
            rec.setWcTermId(found.getId());
            rec.setSlug(found.getSlug());

            // нормализация (для унификации поиска/сравнения)
            var norm = CatPathUtil.cleanedLevels(lv);
            if (i >= 0 && norm.size() > 0) rec.setL1Norm(norm.get(0));
            if (i >= 1 && norm.size() > 1) rec.setL2Norm(norm.get(1));
            if (i >= 2 && norm.size() > 2) rec.setL3Norm(norm.get(2));
            if (i >= 3 && norm.size() > 3) rec.setL4Norm(norm.get(3));
            if (i >= 4 && norm.size() > 4) rec.setL5Norm(norm.get(4));
            if (i >= 5 && norm.size() > 5) rec.setL6Norm(norm.get(5));

            repo.upsert(rec);
        }
        cachePut(sliceHash, found.getId());
    }
    public Optional<Long> getCachedTermIdByLevels(List<String> levelsRaw) {
        List<String> lv = CatPathUtil.levelsArrayList(levelsRaw);
        if (lv.isEmpty()) return Optional.empty();
        String fullPath = CatPathUtil.buildSlicePath(lv, lv.size() - 1);
        String fullHash = CatPathUtil.sha1(fullPath);
        return getCachedTermIdByHash(fullHash);
    }
}