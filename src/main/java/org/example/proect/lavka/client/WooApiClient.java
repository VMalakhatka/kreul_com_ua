package org.example.proect.lavka.client;

import jakarta.annotation.Nullable;
import org.example.proect.lavka.client.support.RetryingRestExecutor;
import org.example.proect.lavka.dto.category.WooCategory;
import org.example.proect.lavka.property.WooProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WooApiClient {
    private final RestTemplate restTemplate;
    private final WooProperties props;
    private final RetryingRestExecutor rex;

    public WooApiClient(@Qualifier("wooRestTemplate") RestTemplate restTemplate,
                        WooProperties props,
                        RetryingRestExecutor rex) {
        this.restTemplate = restTemplate;
        this.props = props;
        this.rex = rex;
    }


    private static boolean eqName(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    // Внутрь WooApiClient
    public record MediaLinkOnlyPayload(
            long product_id,
            String s3_key,
            String url,
            String mime,
            boolean set_featured,
            boolean add_to_gallery,
            int gallery_position
    ) {}
    /**
     * Точный поиск по ИМЕНИ + РОДИТЕЛЮ (parent id).
     * Фильтруем на сервере по parent, а на клиенте — по точному совпадению имени.
     */
    @Nullable
    public WooCategory findCategoryByNameAndParent(String name, @Nullable Long parentId) {
        final long parent = (parentId == null ? 0L : parentId);
        final int perPage = props.getPerPage(); // например 100
        int page = 1;

        while (true) {
            String url = props.getBaseUrl() + "/products/categories";
            UriComponentsBuilder b = UriComponentsBuilder.fromHttpUrl(url)
                        .queryParam("parent", parent)     // фильтр по родителю
                    .queryParam("per_page", perPage)
                    .queryParam("page", page);

            ResponseEntity<WooCategory[]> resp =rex.execUnsafe("woo.findCategoryByNameAndParent", () ->
                    restTemplate.getForEntity(b.toUriString(), WooCategory[].class)
            );

            WooCategory[] arr = resp.getBody();
            if (arr == null || arr.length == 0) return null;

            // строгое сравнение имени + родителя
            for (WooCategory c : arr) {
                long p = (c.getParent() == null ? 0L : c.getParent());
                if (p == parent && c.getName() != null
                        && c.getName().trim().equalsIgnoreCase(name.trim())) {
                    return c; // нашли — сразу выходим, не листаем дальше
                }
            }

            // пагинация: если страниц больше — идем дальше
            // Woo обычно кладёт X-WP-TotalPages в headers
            String totalPagesHeader = resp.getHeaders().getFirst("X-WP-TotalPages");
            int totalPages = (totalPagesHeader != null) ? Integer.parseInt(totalPagesHeader) : page;
            if (page >= totalPages) {
                return null; // прошли всё — нет точного совпадения
            }
            page++;
        }
    }

    /**
     * Более узкий поиск: по SLUG + РОДИТЕЛЮ.
     * Удобно, если slug детерминированный и заранее известен.
     */
    @Nullable
    public WooCategory findCategoryBySlugAndParent(String slug, @Nullable Long parentId) {
        long parent = (parentId == null ? 0L : parentId);

        String url = props.getBaseUrl() + "/products/categories";
        UriComponentsBuilder b = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("slug", slug)
                .queryParam("parent", parent)
                .queryParam("per_page", props.getPerPage());

        ResponseEntity<WooCategory[]> resp =rex.execUnsafe("woo.findCategoryBySlugAndParent", () ->
                restTemplate.getForEntity(b.toUriString(), WooCategory[].class)
        );

        WooCategory[] arr = resp.getBody();
        if (arr == null || arr.length == 0) return null;

        return Arrays.stream(arr)
                .filter(c -> Objects.equals(
                        (c.getParent() == null ? 0L : c.getParent()),
                        parent))
                .findFirst()
                .orElse(null);
    }

    /**
     * Прямое чтение категории по её term_id.
     */
    @Nullable
    public WooCategory getCategoryById(Long id) {
        if (id == null) return null;
        String url = props.getBaseUrl() + "/products/categories/" + id;
        try {
            return rex.execUnsafe("woo.getCategoryById", () ->
                    restTemplate.getForObject(url, WooCategory.class)
            );
        } catch (Exception ignore) {
            return null;
        }
    }

    /**
     * Создание категории.
     */
    public WooCategory createCategory(String name, String slug, @Nullable Long parentId) {
        String url = props.getBaseUrl() + "/products/categories";
        WooCategory payload = new WooCategory();
        payload.setName(name);
        payload.setSlug(slug);
        payload.setParent(parentId == null ? 0L : parentId);
        return rex.execUnsafe("woo.createCategory", () ->
                restTemplate.postForObject(url, payload, WooCategory.class)
        );
    }

    public WooCategory findCategoryBySlug(String slug) {
        String url = props.getBaseUrl() + "/products/categories";
        UriComponentsBuilder b = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("slug", slug)
                .queryParam("per_page", 100)
                .queryParam("page", 1);
        ResponseEntity<WooCategory[]> resp =rex.execUnsafe("woo.findCategoryBySlug", () ->
                restTemplate.getForEntity(b.toUriString(), WooCategory[].class)
        );
        WooCategory[] arr = resp.getBody();
        if (arr == null || arr.length == 0) return null;
        // slug в пределах taxonomy уникален — вернётся 1 шт (на всякий — берём первый)
        return arr[0];
    }

    public boolean slugAvailable(String slug) {
        return findCategoryBySlug(slug) == null;
    }

    public WooCategory createCategoryUnique(String name, String baseSlug, Long parentId, String pathForHash) {
        // 1) Если slug свободен — создаём сразу
        String candidate = baseSlug;
        if (!slugAvailable(candidate)) {
            // 2) Занят: добавим стабильный суффикс от родителя/пути (чтобы не плодить лишнего)
            String suffix = shortHash((parentId == null ? 0L : parentId) + ":" + pathForHash);
            candidate = baseSlug + "--" + suffix;
            // если и он занят — докручиваем счётчик
            int i = 2;
            while (!slugAvailable(candidate)) {
                candidate = baseSlug + "--" + suffix + "-" + i;
                i++;
                if (i > 20) throw new IllegalStateException("Can't find free slug for: " + baseSlug);
            }
        }

        try {
            return createCategory(name, candidate, parentId);
        } catch (HttpClientErrorException e) {
            // страховка от гонки: если между проверкой и POST кто-то занял slug
            if (e.getStatusCode() == HttpStatus.BAD_REQUEST && isTermExists(e)) {
                long id = extractResourceId(e);
                WooCategory exists = getCategoryById(id);
                long wantParent = parentId == null ? 0L : parentId;
                long haveParent = exists.getParent() == null ? 0L : exists.getParent();
                if (haveParent == wantParent) {
                    return exists; // наш терм, всё ок
                }
                // slug заняли «чужим» parent — докручиваем ещё один суффикс и пробуем снова
                String fallback = candidate + "--" + shortHash("race:" + System.nanoTime());
                return createCategory(name, fallback, parentId);
            }
            throw e;
        }
    }

    // ======== утилиты для обработки ошибок Woo ========

    /** Проверяет, что ошибка WooCommerce — именно term_exists (категория уже есть). */
    public static boolean isTermExists(HttpClientErrorException e) {
        return e.getStatusCode() == HttpStatus.BAD_REQUEST &&
                e.getResponseBodyAsString() != null &&
                e.getResponseBodyAsString().contains("\"term_exists\"");
    }

    /** Извлекает resource_id из JSON-ответа Woo на term_exists. */
    public static long extractResourceId(HttpClientErrorException e) {
        if (e.getResponseBodyAsString() == null) return -1L;
        // простой RegExp, без зависимостей от JSON-парсера
        Matcher m = Pattern.compile("\"resource_id\"\\s*:\\s*(\\d+)").matcher(e.getResponseBodyAsString());
        if (m.find()) {
            return Long.parseLong(m.group(1));
        }
        return -1L;
    }

    /** Короткий стабильный хеш для добавления в slug. */
    public static String shortHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            // берём первые 5 байт SHA-1 → 8 Base32-символов, чтобы было читаемо
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, 8).toLowerCase();
        } catch (Exception ex) {
            // fallback на timestamp, если вдруг
            return "x" + Long.toHexString(System.nanoTime());
        }
    }

    public record WooBatchResult(
            int createdCount,
            int updatedCount,
            int deletedCount
    ) {}

    /** Resolve the existing global attribute in this target Woo, never a local hardcoded ID. */
    public long requireUnitAttributeId() {
        Map[] attributes = rex.execSafe("woo.unitAttribute", () -> restTemplate.getForObject(
                props.getBaseUrl() + "/products/attributes", Map[].class));
        if (attributes == null) throw new IllegalStateException("WOO_UNIT_ATTRIBUTE_LOOKUP_FAILED");
        Long found = null;
        for (Map<?, ?> attribute : attributes) {
            if (!"pa_edin_izmer".equals(attribute.get("slug")) && !"edin_izmer".equals(attribute.get("slug"))) continue;
            if (!(attribute.get("id") instanceof Number id) || id.longValue() <= 0 || found != null)
                throw new IllegalStateException("WOO_UNIT_ATTRIBUTE_INVALID: expected one global pa_edin_izmer");
            found = id.longValue();
        }
        if (found == null) throw new IllegalStateException(
                "WOO_UNIT_ATTRIBUTE_MISSING: create the global attribute edin_izmer (taxonomy pa_edin_izmer) in the target Woo before full sync");
        return found;
    }

    /** Bounded REST reads; an unavailable/malformed collection must never become an empty replacement. */
    public Map<Long, List<Map<String, Object>>> readProductAttributes(Collection<Long> productIds) {
        var ids = productIds.stream().distinct().toList();
        var result = new LinkedHashMap<Long, List<Map<String, Object>>>();
        for (int from = 0; from < ids.size(); from += 100) {
            var batch = ids.subList(from, Math.min(from + 100, ids.size()));
            if (batch.stream().anyMatch(id -> id == null || id <= 0))
                throw new IllegalStateException("WOO_PRODUCT_ATTRIBUTE_ID_INVALID");
            String url = UriComponentsBuilder.fromHttpUrl(props.getBaseUrl() + "/products")
                    .queryParam("include", batch.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")))
                    .queryParam("per_page", 100).queryParam("_fields", "id,attributes").toUriString();
            Map[] products = rex.execSafe("woo.productAttributes", () -> restTemplate.getForObject(url, Map[].class));
            if (products == null) throw new IllegalStateException("WOO_PRODUCT_ATTRIBUTES_UNAVAILABLE");
            for (Map<?, ?> product : products) {
                if (!(product.get("id") instanceof Number id) || !batch.contains(id.longValue())
                        || !(product.get("attributes") instanceof List<?> attrs))
                    throw new IllegalStateException("WOO_PRODUCT_ATTRIBUTES_INVALID");
                var copy = new ArrayList<Map<String, Object>>();
                for (Object value : attrs) {
                    if (!(value instanceof Map<?, ?> attr) || !(attr.get("id") instanceof Number)
                            || !(attr.get("name") instanceof String) || !(attr.get("position") instanceof Number)
                            || !(attr.get("visible") instanceof Boolean) || !(attr.get("variation") instanceof Boolean)
                            || !(attr.get("options") instanceof List<?> options)
                            || options.stream().anyMatch(option -> !(option instanceof String)))
                        throw new IllegalStateException("WOO_PRODUCT_ATTRIBUTES_INVALID");
                    var a = new LinkedHashMap<String, Object>();
                    attr.forEach((key, item) -> a.put(String.valueOf(key), item));
                    copy.add(a);
                }
                result.put(id.longValue(), copy);
            }
            if (!result.keySet().containsAll(batch)) throw new IllegalStateException("WOO_PRODUCT_ATTRIBUTES_INCOMPLETE");
        }
        return result;
    }

    public WooBatchResult upsertProductsBatch(Map<String,Object> payload) {

        if (payload == null || payload.isEmpty()) {
            return new WooBatchResult(0, 0, 0);
        }

        String url = props.getBaseUrl() + "/products/batch";

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> resp = rex.execUnsafe("woo.products.batch", () ->
                restTemplate.postForEntity(url, payload, Map.class));

        Map body = resp.getBody();
        if (body == null) {
            return new WooBatchResult(0, 0, 0);
        }

        if (resp.getStatusCode().is2xxSuccessful()) {

            int c = 0;
            int u = 0;
            int d = 0;

            Object createdArr = body.get("create");
            if (createdArr instanceof List<?> listC) {
                c = listC.size();
            }

            Object updatedArr = body.get("update");
            if (updatedArr instanceof List<?> listU) {
                u = listU.size();
            }

            Object deletedArr = body.get("delete");
            if (deletedArr instanceof List<?> listD) {
                d = listD.size();
            }

            return new WooBatchResult(c, u, d);

        } else {
            return new WooBatchResult(0, 0, 0);
        }
    }

}
