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
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

@Service
public class WooCategoryServiceImpl implements WooCategoryService {

    private final LavkaCatmapRepository repo;
    private final WooApiClient woo;
    private final Slugify slugify;

    public WooCategoryServiceImpl(LavkaCatmapRepository repo, WooApiClient woo, Slugify slugify) {
        this.repo = repo;
        this.woo = woo;
        this.slugify = slugify;
    }

    /**
     * Создаёт (или находит) цепочку категорий в WooCommerce и
     * записывает маппинг в wp_lavka_catmap.
     * Возвращает term_id конечной категории.
     */
    @Override
    @Transactional
    public long ensureCategoryPath(@Nullable String l1, @Nullable String l2, @Nullable String l3,
                                   @Nullable String l4, @Nullable String l5, @Nullable String l6) {

        // 0) уровни/хэши
        final List<String> levels = CatPathUtil.levelsArrayList(l1,l2,l3,l4,l5,l6);
        if (levels.isEmpty()) throw new IllegalArgumentException("Category path is empty");
        final int depth = levels.size();
        final String fullPath = CatPathUtil.buildSlicePath(levels, depth - 1);
        final String fullHash = CatPathUtil.sha1(fullPath);

        // 1) Быстрый выход, если маппинг полного пути уже привязан
        var fullOpt = repo.findByPathHash(fullHash);
        if (fullOpt.isPresent() && fullOpt.get().getWcTermId() != null) {
            //TODO может надо проверить что все соответствует тому что есть в WooComerce? ID Slag and Name?
            //TODO или это просто регулярную проверку запускать ночью - проверка целосности и всех соответствий ?
            return fullOpt.get().getWcTermId();
        }


// 2) ищем самый длинный ПРИВЯЗАННЫЙ префикс в нашей БД
        int startIdx = -1;
        Long parentId = null;
        for (int i = depth - 2; i >= 0; i--) {
            String slice = CatPathUtil.buildSlicePath(levels, i);
            var opt = repo.findByPathHash(CatPathUtil.sha1(slice));
            if (opt.isPresent() && opt.get().getWcTermId() != null) {
                startIdx = i;
                parentId = opt.get().getWcTermId(); // известный parent в Woo
                break;
            }
        }

// Если ничего не нашли, стартуем с «виртуального корня» (parent=0 в Woo)
        if (startIdx < 0) {
            parentId = null; // в Woo это будет 0
        }



// 3) строго сверху вниз: от (startIdx+1) до листа
        WooCategory last = null;
        for (int i = startIdx + 1; i < depth; i++) {
            String name = levels.get(i);

            // сначала точный поиск по имени+родителю в Woo
            WooCategory found = woo.findCategoryByNameAndParent(name, parentId);
            if (found == null) {
                // базовый slug: можешь взять по имени уровня,
                // либо (ещё лучше) по полному пути до этого уровня
                String baseSlug = slugify.slug(name);
                // если у тебя здесь уже есть levels/i, используй путь:
                // String baseSlug = slugify.slug(CatPathUtil.buildSlicePath(levels, i));

                // создаём с гарантией уникальности slug + защита от гонок
                found = woo.createCategoryUnique(
                        name,
                        baseSlug,
                        parentId,
                        CatPathUtil.buildSlicePath(levels, i) // pathForHash для стабильного суффикса
                );
            }

            // upsert среза [0..i] в нашу БД (идемпотентно)
            String slice     = CatPathUtil.buildSlicePath(levels, i);
            String sliceHash = CatPathUtil.sha1(slice);

// нормализуем parent из Woo: 0 -> NULL
            Long wcParent = (found.getParent() == null || found.getParent() == 0L) ? null : found.getParent();

// 1) Пытаемся просто «подровнять» существующую запись (если есть) под Woo.
//    Если записи не было — update вернёт 0.
            int updated = repo.updateWooBinding(sliceHash, wcParent, found.getId(), found.getSlug());

            if (updated == 0) {
                // 2) Записи нет — создаём её полностью
                LavkaCatmap rec = new LavkaCatmap();
                rec.setPathText(slice);
                rec.setDepth(i + 1);

                // уровни l1..l6 по месту
                if (i >= 0) rec.setL1(levels.get(0));
                if (i >= 1) rec.setL2(levels.get(1));
                if (i >= 2) rec.setL3(levels.get(2));
                if (i >= 3) rec.setL4(levels.get(3));
                if (i >= 4) rec.setL5(levels.get(4));
                if (i >= 5) rec.setL6(levels.get(5));

                // parent_path_hash для этого среза
                String parentSlice = (i > 0) ? CatPathUtil.buildSlicePath(levels, i - 1) : null;
                rec.setParentPathHash(parentSlice != null ? CatPathUtil.sha1(parentSlice) : null);

                // привязка к Woo
                rec.setWcParentId(wcParent);
                rec.setWcTermId(found.getId());
                rec.setSlug(found.getSlug());

                // нормализация (для уже существующих уровней)
                var norm = CatPathUtil.cleanedLevels(levels.toArray(new String[0]));
                if (i >= 0 && norm.size() > 0) rec.setL1Norm(norm.get(0));
                if (i >= 1 && norm.size() > 1) rec.setL2Norm(norm.get(1));
                if (i >= 2 && norm.size() > 2) rec.setL3Norm(norm.get(2));
                if (i >= 3 && norm.size() > 3) rec.setL4Norm(norm.get(3));
                if (i >= 4 && norm.size() > 4) rec.setL5Norm(norm.get(4));
                if (i >= 5 && norm.size() > 5) rec.setL6Norm(norm.get(5));

                repo.upsert(rec);
            }

            last = found;
            parentId = found.getId(); // следующий уровень — ребёнок найденного/созданного
        }
        return Objects.requireNonNull(last, "Woo creation failed").getId();
    }
}