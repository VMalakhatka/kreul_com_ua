package org.example.proect.lavka.client;

import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dto.category.WooCategory;
import org.example.proect.lavka.property.WooProperties;
import org.example.proect.lavka.utils.category.CatPathUtil;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Arrays;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class WooApiClient {
    private final RestTemplate restTemplate;
    private final WooProperties props;

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
                    .queryParam("search", name)       // сузим на стороне Woo
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
}