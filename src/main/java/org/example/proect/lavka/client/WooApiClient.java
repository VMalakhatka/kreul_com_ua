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
        if (singleAttemptWrites.get()) {
            try { return restTemplate.postForObject(url, payload, WooCategory.class); }
            catch (RuntimeException e) { throw new org.example.proect.lavka.service.sync.SyncOutcomeUnknownException(); }
        }
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
            int deletedCount,
            List<String> errors
    ) {
        public WooBatchResult(int createdCount, int updatedCount, int deletedCount) {
            this(createdCount, updatedCount, deletedCount, List.of());
        }
    }

    /** Fail before diff/category writes if the target cannot persist the supplier field. */
    public void requireFullSyncSupplierSupport() {
        Map<?, ?> schema = rex.execSafe("woo.fullSyncSchema", () -> restTemplate.exchange(
                props.getBaseUrl() + "/products", org.springframework.http.HttpMethod.OPTIONS,
                org.springframework.http.HttpEntity.EMPTY, Map.class).getBody());
        if (schema == null || !(schema.get("schema") instanceof Map<?, ?> item)
                || !(item.get("properties") instanceof Map<?, ?> properties)
                || !(properties.get("lavka_supplier") instanceof Map<?, ?> field)
                || !"string".equals(field.get("type"))
                || !Boolean.TRUE.equals(field.get("x-lavka-full-sync-supplier"))) {
            throw new IllegalStateException("WOO_FULL_SYNC_SUPPLIER_UNSUPPORTED: deploy Lavka Total Sync first");
        }
    }

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
        return upsertProductsBatch(payload, false);
    }

    public WooBatchResult upsertProductsBatchOnce(Map<String,Object> payload) {
        return upsertProductsBatch(payload, true);
    }

    private final ThreadLocal<Boolean> singleAttemptWrites = ThreadLocal.withInitial(() -> false);

    /** Applies to category creation inside diff preparation as well as product writes. */
    public <T> T withoutWriteRetries(java.util.function.Supplier<T> operation) {
        boolean previous = singleAttemptWrites.get();
        singleAttemptWrites.set(true);
        try { return operation.get(); }
        finally { if (previous) singleAttemptWrites.set(true); else singleAttemptWrites.remove(); }
    }

    public boolean ownsFullSyncLease(String jobId, String token) {
        Map<?, ?> result = restTemplate.postForObject(props.getBaseUrl() + "/lavka-full-sync/lease",
                Map.of("jobId", jobId, "token", token), Map.class);
        return result != null && Boolean.TRUE.equals(result.get("owned"));
    }

    private WooBatchResult upsertProductsBatch(Map<String,Object> payload, boolean once) {

        if (payload == null || payload.isEmpty()) {
            return new WooBatchResult(0, 0, 0);
        }

        String url = props.getBaseUrl() + "/products/batch";

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> resp = once ? restTemplate.postForEntity(url, payload, Map.class)
                : rex.execUnsafe("woo.products.batch", () -> restTemplate.postForEntity(url, payload, Map.class));

        Map body = resp.getBody();
        if (resp.getStatusCode().is2xxSuccessful()) {
            var errors = new ArrayList<String>();
            int c = batchSuccessCount("create", payload, body, errors);
            int u = batchSuccessCount("update", payload, body, errors);
            int d = batchSuccessCount("delete", payload, body, errors);
            return new WooBatchResult(c, u, d, List.copyOf(errors));
        } else {
            return new WooBatchResult(0, 0, 0, List.of("WOO_BATCH_RESPONSE_FAILED"));
        }
    }

    private static int batchSuccessCount(String section, Map<String, Object> request,
            Map<?, ?> response, List<String> errors) {
        if (!(request.get(section) instanceof List<?> sent) || sent.isEmpty()) return 0;
        List<?> received = response != null && response.get(section) instanceof List<?> rows ? rows : List.of();
        if (received.size() != sent.size()) errors.add("WOO_BATCH_RESPONSE_INCOMPLETE: " + section);
        int count = 0;
        for (int i = 0; i < Math.min(sent.size(), received.size()); i++) {
            if (received.get(i) instanceof Map<?, ?> row && !row.containsKey("error")
                    && row.get("id") instanceof Number id && id.longValue() > 0) {
                count++;
            } else {
                String code = "INVALID_RESULT";
                if (received.get(i) instanceof Map<?, ?> row && row.get("error") instanceof Map<?, ?> error
                        && error.get("code") instanceof String value && value.matches("[A-Za-z0-9_-]{1,100}")) code = value;
                errors.add(("INVALID_RESULT".equals(code) ? "WOO_BATCH_RESPONSE_INVALID: " : "WOO_BATCH_ITEM_FAILED: ")
                        + section + "[" + i + "] " + code);
            }
        }
        return count;
    }
}
