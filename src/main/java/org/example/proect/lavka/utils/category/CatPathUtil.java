package org.example.proect.lavka.utils.category;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

public final class CatPathUtil {
    /** ВАЖНО: тот же разделитель, что и в БД/триггерах */
    private static final String SEP = " > ";

    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern PUNCT = Pattern.compile("[^\\p{L}\\p{N}\\s]+");

    private CatPathUtil() {}

    // ---------- Нормализация уровней (для *_norm, не для path_text) ----------

    /** Привести строку к «нормальной» форме: Unicode NFKC → трим → убрать пунктуацию → схлопнуть пробелы → lower. */
    public static String normalizeLevel(String s) {
        if (s == null) return null;
        String x = Normalizer.normalize(s, Normalizer.Form.NFKC);
        x = x.trim();
        x = PUNCT.matcher(x).replaceAll(" ");
        x = SPACE.matcher(x).replaceAll(" ");
        x = x.toLowerCase(Locale.ROOT);
        return x.isBlank() ? null : x;
    }

    /** Список непустых нормализованных уровней. */
    public static List<String> cleanedLevels(String... levels) {
        List<String> out = new ArrayList<>();
        for (String lv : levels) {
            String n = normalizeLevel(lv);
            if (n != null) out.add(n);
        }
        return out;
    }

    /** Список непустых «сырых» уровней (без нормализации, с trim). */
    public static List<String> levelsArrayList(String... rawLevels) {
        List<String> lv = new ArrayList<>();
        for (String s : rawLevels) {
            if (s != null && !s.isBlank()) lv.add(s.trim());
        }
        return lv;
    }

    public static List<String> levelsArrayList(List<String> rawLevels) {
        if (rawLevels == null || rawLevels.isEmpty()) return List.of();
        List<String> lv = new ArrayList<>(rawLevels.size());
        for (String s : rawLevels) {
            if (s != null && !s.isBlank()) lv.add(s.trim());
        }
        return lv;
    }

    // ---------------------- Сборка путей (единый конструктор) ----------------------

    /** Срез пути: уровни [0 .. endInclusive]. Возвращает null, если нечего собирать. */
    public static String buildSlicePath(List<String> levels, int endInclusive) {
        if (levels == null || levels.isEmpty()) return null;
        if (endInclusive < 0) return null;
        int k = Math.min(endInclusive + 1, levels.size());
        if (k <= 0) return null;
        return String.join(SEP, levels.subList(0, k));
    }

    /** Полный путь (до последнего уровня). */
    public static String buildPathText(List<String> levels) {
        if (levels == null) return null;
        return buildSlicePath(levels, levels.size() - 1);
    }

    /** Полный путь (удобная перегрузка для varargs). */
    public static String buildPathText(String... rawLevels) {
        return buildPathText(levelsArrayList(rawLevels));
    }

    /** Родительский путь для последнего уровня. */
    public static String buildParentPathText(List<String> levels) {
        if (levels == null) return null;
        return buildSlicePath(levels, levels.size() - 2);
    }

    /** Родительский путь (удобная перегрузка для varargs). */
    public static String buildParentPathText(String... rawLevels) {
        return buildParentPathText(levelsArrayList(rawLevels));
    }

    /** Родитель для произвольного уровня [0..endInclusive]. */
    public static String buildSliceParentPath(List<String> levels, int endInclusive) {
        return buildSlicePath(levels, endInclusive - 1);
    }

    // ------------------------------- Хэш -------------------------------

    public static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-1 error", e);
        }
    }
}