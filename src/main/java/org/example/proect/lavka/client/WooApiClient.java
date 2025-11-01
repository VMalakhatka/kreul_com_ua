package org.example.proect.lavka.client;

import jakarta.annotation.Nullable;
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

    public WooApiClient(@Qualifier("wooRestTemplate") RestTemplate restTemplate,
                        WooProperties props) {
        this.restTemplate = restTemplate;
        this.props = props;
    }


    private static boolean eqName(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

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

            ResponseEntity<WooCategory[]> resp =
                    restTemplate.getForEntity(b.toUriString(), WooCategory[].class);

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

        ResponseEntity<WooCategory[]> resp =
                restTemplate.getForEntity(b.toUriString(), WooCategory[].class);

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
            return restTemplate.getForObject(url, WooCategory.class);
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
        return restTemplate.postForObject(url, payload, WooCategory.class);
    }

    public WooCategory findCategoryBySlug(String slug) {
        String url = props.getBaseUrl() + "/products/categories";
        UriComponentsBuilder b = UriComponentsBuilder.fromHttpUrl(url)
                .queryParam("slug", slug)
                .queryParam("per_page", 100)
                .queryParam("page", 1);
        ResponseEntity<WooCategory[]> resp =
                restTemplate.getForEntity(b.toUriString(), WooCategory[].class);
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
            int updatedCount
            // можно добавить ещё детали, если надо
    ) {}

    public WooBatchResult upsertProductsBatch(Map<String,Object> payload) {
        // если нет ни create ни update — просто ничего не делаем
        if (payload == null || payload.isEmpty()) {
            return new WooBatchResult(0,0);
        }

        String url = props.getBaseUrl() + "/products/batch";

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> resp = restTemplate.postForEntity(url, payload, Map.class);

        Map body = resp.getBody();
        if (body == null) {
            return new WooBatchResult(0,0);
        }
        if (resp.getStatusCode().is2xxSuccessful()) {
            // Woo отвечает примерно:
            // {
            //   "create": [ {id:..., sku:"..."}, ... ],
            //   "update": [ {id:..., sku:"..."}, ... ],
            //   "delete": [...]
            // }
            int c = 0;
            int u = 0;

            Object createdArr = body.get("create");
            if (createdArr instanceof List<?> listC) {
                c = listC.size();
                // при желании можно тут прочитать id/sku и обновить локальную карту knownPostId
                // (завести Map<String,Long> sku->id и вернуть наружу)
            }

            Object updatedArr = body.get("update");
            if (updatedArr instanceof List<?> listU) {
                u = listU.size();
            }

            return new WooBatchResult(c, u);
        }else{
            // ошибка - считаем всё как 0, логируем
            // log.error("Woo batch failed {}", code);
            return new WooBatchResult(0,0);
        }
    }

    public void pushCategoryDescriptionsBatch(Map<Long,String> catMap) {
        // соберём payload
        List<Map<String,Object>> items = new ArrayList<>();
        for (Map.Entry<Long,String> e : catMap.entrySet()) {
            Long termId = e.getKey();
            String html = e.getValue();
            if (termId == null || termId <= 0) continue;
            if (html == null || html.isBlank()) continue;

            Map<String,Object> one = new HashMap<>();
            one.put("term_id", termId);
            one.put("html", html);
            items.add(one);
        }

        if (items.isEmpty()) {
            return; // нечего постить
        }

        String url = props.getBaseUrl() + "/lavka/v1/catdesc/batch";
        // props.getLavkaBaseUrl() — это типа https://site/wp-json
        // (мы можем добавить это поле в WooProperties как baseRestUrl без /wc/v3)

        Map<String,Object> payload = new HashMap<>();
        payload.put("items", items);

        // auth так же, как мы делаем для wc/v3 (Basic / Bearer)
        restTemplate.postForObject(url, payload, Void.class);
    }

}