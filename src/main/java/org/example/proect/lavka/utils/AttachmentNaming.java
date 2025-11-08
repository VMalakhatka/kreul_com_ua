package org.example.proect.lavka.utils;

import jakarta.annotation.Nullable;
import org.example.proect.lavka.utils.category.Slugify;
import org.springframework.stereotype.Component;

@Component
public class AttachmentNaming {

    private final Slugify slugify;
    public AttachmentNaming(Slugify slugify) { this.slugify = slugify; }

    /** Title для attachment: обычно "SKU Имя товара", fallback — имя файла без расширения. */
    public String makePostTitle(@Nullable String sku, @Nullable String productName, @Nullable String fileName) {
        String base = joinNonBlank(" ", sku, productName);
        if (!base.isBlank()) return base;
        return fileStem(fileName); // без .jpg/.png
    }

    /** Slug (post_name) из title. Ограничиваем длину и чистим повтор. */
    public String makePostSlug(String postTitle, int maxLen) {
        String s = slugify.slug(postTitle);
        if (s.length() > maxLen) s = s.substring(0, maxLen);
        if (s.isBlank()) s = "img";
        return s;
    }

    /** Имя файла без расширения. */
    public String fileStem(@Nullable String fileName) {
        if (fileName == null) return "";
        int slash = fileName.lastIndexOf('/');
        String only = (slash >= 0) ? fileName.substring(slash + 1) : fileName;
        int dot = only.lastIndexOf('.');
        return (dot > 0) ? only.substring(0, dot) : only;
    }

    // ========================================================================
    // ✅ НОВЫЙ МЕТОД: нормализация пути для WP (_wp_attached_file)
    // ========================================================================
    public String keyToAttachedFile(String fullKeyOrUrl) {
        if (fullKeyOrUrl == null || fullKeyOrUrl.isBlank()) return "";

        String s = fullKeyOrUrl.trim();

        // ---- Если URL (https://...) → берём path ----
        int schemePos = s.indexOf("://");
        if (schemePos > 0) {
            int firstSlash = s.indexOf('/', schemePos + 3);
            if (firstSlash >= 0) {
                s = s.substring(firstSlash); // "/wp-content/uploads/2025/06/xxx.jpg"
            }
        }

        // ---- Нормализуем ----
        s = s.replace('\\', '/').replaceAll("^/+", "");

        // ---- Убираем префиксы ----
        if (s.startsWith("wp-content/uploads/")) {
            s = s.substring("wp-content/uploads/".length());
        } else if (s.startsWith("uploads/")) {
            s = s.substring("uploads/".length());
        }

        // удалить возможные ведущие "/"
        s = s.replaceAll("^/+", "");

        return s; // например "2025/06/ser_1110.jpg"
    }

    /** Безопасное относительное значение для _wp_attached_file (wp-content/uploads/YY/MM/xxx.jpg). */
    public String normalizeAttachedFile(String s3Key) {
        // приводим слеши, убираем ведущие /
        String t = s3Key.replace('\\','/').replaceAll("^/+", "");
        // никакой URL здесь не нужен — только относительный путь
        return t;
    }

    /** ALT: режем до 100 символов. */
    public String limitAlt(String alt) {
        if (alt == null) return "";
        return (alt.length() > 100) ? alt.substring(0, 100) : alt;
    }

    /** Title для attachment: режем до 190 символов. */
    public String limitTitle(String title) {
        if (title == null) return "";
        return (title.length() > 190) ? title.substring(0, 190) : title;
    }

    // helpers
    private static String joinNonBlank(String sep, String... parts) {
        return java.util.Arrays.stream(parts)
                .filter(p -> p != null && !p.isBlank())
                .reduce((a,b)-> a + sep + b).orElse("");
    }
}