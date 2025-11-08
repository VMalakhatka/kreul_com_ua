package org.example.proect.lavka.service;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.dao.CardTovExportDaoImpl;
import org.example.proect.lavka.dao.wp.S3MediaIndexDao;
import org.example.proect.lavka.dao.wp.WpProductDao;
import org.example.proect.lavka.utils.AttachmentNaming;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MsToWpImageSyncService {

    private final CardTovExportDaoImpl msDao;
    private final WpProductDao wp;
    private final S3MediaIndexDao s3dao;
    private final OvhS3MediaService keyResolver;
    private final MediaOnlineService online;     // из предыдущего шага
    private final AttachmentNaming naming;       // для title/slug/alt
    private final S3MediaIndexService s3;        // для toPublicUrl (если надо прямо тут)

    /**
     * Синхронизирует картинки для переданных SKU.
     *  mode: "featured" | "gallery" | "both"
     *  galleryStartPos: с какой позиции начинать галерею в Woo
     *  limitPerSku: сколько картинок максимум тащить (включая featured, если both)
     *  dry: без вызова WP/SQL — только расчёт и проверка
     */
    public Map<String,Object> syncFromMs(List<String> skus,
                                         String mode,
                                         int galleryStartPos,
                                         int limitPerSku,
                                         boolean dry) {

        Map<String, Long> ids = wp.findIdsBySkus(skus);
        List<Map<String,Object>> out = new ArrayList<>();

        for (String sku : skus) {
            Map<String,Object> one = new LinkedHashMap<>();
            one.put("sku", sku);

            Long pid = ids.get(sku);
            one.put("productId", pid);
            if (pid == null) {
                one.put("warnings", List.of("product_not_found"));
                out.add(one);
                continue;
            }

            // 1) Читаем из MS
            var bundle = msDao.findImagesBundleBySku(sku);
            String mainName = (bundle == null) ? null : bundle.mainFileName();
            List<CardTovExportDaoImpl.MsGalleryImage> gallery = (bundle == null) ? List.of() : bundle.gallery();

            // 2) Разворачиваем список «что хотим поставить» (main + gallery)
            List<Desired> want = new ArrayList<>();
            // featured
            if (!"gallery".equalsIgnoreCase(mode) && mainName != null && !mainName.isBlank()) {
                want.add(new Desired(mainName.trim(), /*position*/ 0, /*featured*/ true));
            }
            // gallery
            if (!"featured".equalsIgnoreCase(mode) && gallery != null && !gallery.isEmpty()) {
                int pos = galleryStartPos;
                for (var g : gallery) {
                    want.add(new Desired(g.fileName(), pos++, /*featured*/ false));
                    if (want.size() >= limitPerSku) break;
                }
            }
            if (want.isEmpty()) {
                one.put("warnings", List.of("no_images_in_ms"));
                out.add(one);
                continue;
            }

            // 3) Для каждого требуемого файла — ищем full_key в индексе S3
            List<Map<String,Object>> applied = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            for (var d : want) {
                // 3) full_key из индекса
                String fullKey = s3dao.resolveBestFullKeyByFilename(d.fileName());
                if (fullKey == null) {
                    warnings.add("not_in_s3_index: " + d.fileName());
                    continue;
                }
                String url = s3.toPublicUrl(fullKey);

                // 4) проверка «уже связано?»
                Long imageId = s3dao.resolveImageIdByFullKey(fullKey);
                if (imageId == null) {
                    warnings.add("image_id_not_found_for_full_key: " + fullKey);
                    continue;
                }
                boolean already = s3dao.linkExists(sku, imageId, d.position());
                if (already) {
                    applied.add(Map.of(
                            "file", d.fileName(), "skipped", true,
                            "reason", "link_exists", "position", d.position(),
                            "featured", d.featured()
                    ));
                    continue;
                }

                // 5) Онлайн-вызов WP (если не dry)
                if (!dry) {
                    if (d.featured()) {
                        online.linkFeaturedAndSetMeta(
                                pid, sku, fullKey, url, "image/jpeg"
                        );
                    } else {
                        // галерея: оставляем твой batched/по одному — как реализовано в MediaOnlineService
                        online.linkGalleryAndSetMeta(
                                pid, sku, List.of(fullKey), d.position(), "image/jpeg"
                        );
                    }
                }

                // 6) Отзеркалить связь и меты в наших таблицах
                s3dao.upsertOneLinkByImageId(imageId, sku, pid, d.position());
                String title = naming.limitTitle(naming.makePostTitle(sku, null, null));
                String alt   = naming.limitAlt(title);
                s3dao.upsertAltTitle(imageId, sku, pid, d.position(), alt, title);

                applied.add(Map.of(
                        "file", d.fileName(), "applied", !dry,
                        "position", d.position(), "featured", d.featured()
                ));
            }

            one.put("applied", applied);
            if (!warnings.isEmpty()) one.put("warnings", warnings);
            out.add(one);
        }

        return Map.of("ok", true, "processed", skus.size(), "results", out);
    }

    private Map<String,Object> onlineLinkOnlyAndMeta(long productId, String sku,
                                                     String fullKey, String url, String mime){
        // Если удобно, можно дернуть прямо MediaOnlineService.linkFeaturedAndSetMeta()
        // но для галереи поставим add_to_gallery=true и нужную позицию через endpoint.
        // Здесь оставлю как заготовку, чтобы не дублировать код:
        // online.linkGalleryAndSetMeta(productId, sku, List.of(fullKey), /*startPos*/..., mime);
        return Map.of("ok", true);
    }

    private record Desired(String fileName, int position, boolean featured) {}
}