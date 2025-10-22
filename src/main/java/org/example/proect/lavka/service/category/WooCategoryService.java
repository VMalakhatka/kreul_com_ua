package org.example.proect.lavka.service.category;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface WooCategoryService {

    long ensureCategoryPath(@Nullable String l1, @Nullable String l2, @Nullable String l3,
                            @Nullable String l4, @Nullable String l5, @Nullable String l6);

    // Удобный оверлоад: уровни списком
    long ensureCategoryPath(List<String> levels);

    // Пакетный режим: на вход набор путей, на выход map fullHash -> wcTermId конечной категории
    Map<String, Long> ensureCategoryPathsBulk(List<List<String>> paths);

    // Эталонный способ получить хеш полного пути (стандартизируем)
    static String pathHashOf(List<String> levels) {
        var lv = org.example.proect.lavka.utils.category.CatPathUtil.levelsArrayList(levels);
        if (lv.isEmpty()) throw new IllegalArgumentException("Category path is empty");
        String fullPath = org.example.proect.lavka.utils.category.CatPathUtil.buildSlicePath(lv, lv.size() - 1);
        return org.example.proect.lavka.utils.category.CatPathUtil.sha1(fullPath);
    }

    // (опционально) Работа с кэшем извне — если пригодится
    Optional<Long> getCachedTermIdByHash(String fullHash);
    void putCache(String fullHash, long termId);
}