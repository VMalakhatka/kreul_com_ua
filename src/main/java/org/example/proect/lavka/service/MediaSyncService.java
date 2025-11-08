package org.example.proect.lavka.service;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.client.WooApiClient;
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
public class MediaSyncService {

    private final WpProductDao wp;
    private final S3MediaIndexDao s3dao;
    private final S3MediaIndexService s3;
    private final WooApiClient woo; // уже умеет mediaLinkOnly
    private final AttachmentNaming naming;

    public Map<String,Object> syncSkus(List<String> skus, String mode,
                                       boolean touchOnUpdate,
                                       int galleryStartPos,
                                       int limitPerSku,
                                       boolean dry) {
        Map<String, Long> ids = wp.findIdsBySkus(skus); // sku -> product_id
        List<Map<String,Object>> results = new ArrayList<>();

        for (String sku : skus) {
            Map<String,Object> one = new LinkedHashMap<>();
            one.put("sku", sku);

            Long pid = ids.get(sku);
            one.put("productId", pid);

            List<String> keys = s3dao.findFullKeysBySku(sku); // отсортируйте в DAO: last_modified desc, size desc
            if (keys == null) keys = List.of();

            List<String> urls = keys.stream().map(s3::toPublicUrl).toList();

            Map<String,Object> featuredRes = null;
            Map<String,Object> galleryRes  = null;
            List<String> warnings = new ArrayList<>();
            List<String> errors   = new ArrayList<>();

            try {
                if (pid == null) {
                    warnings.add("product_not_found");
                } else if (keys.isEmpty()) {
                    warnings.add("no_images_in_index");
                } else {
                    int used = 0;

                    if (!"gallery".equalsIgnoreCase(mode)) {
                        // FEATURED
                        String fKey = keys.get(0);
                        String fUrl = urls.get(0);
                        if (!dry) {
                            woo.mediaLinkOnly(new WooApiClient.MediaLinkOnlyPayload(
                                    pid, naming.keyToAttachedFile(fKey), fUrl, "image/jpeg",
                                    /*set_featured*/ true,
                                    /*add_to_gallery*/ false,
                                    /*gallery_position*/ 0
                            ));
                        }
                        featuredRes = Map.of(
                                "set", true,
                                "s3Key", naming.keyToAttachedFile(fKey),
                                "url", fUrl
                        );
                        used++;
                    }

                    if (!"featured".equalsIgnoreCase(mode)) {
                        // GALLERY
                        int toTake = Math.max(0, limitPerSku - ("both".equalsIgnoreCase(mode) ? 1 : 0));
                        List<String> galKeys = keys.stream().skip(used).limit(toTake).toList();
                        List<String> galUrls = urls.stream().skip(used).limit(toTake).toList();

                        List<Integer> attIds = new ArrayList<>();
                        int pos = galleryStartPos;
                        for (int i = 0; i < galKeys.size(); i++) {
                            String k = galKeys.get(i), u = galUrls.get(i);
                            if (!dry) {
                                woo.mediaLinkOnly(new WooApiClient.MediaLinkOnlyPayload(
                                        pid, naming.keyToAttachedFile(k), u, "image/jpeg",
                                        /*set_featured*/ false,
                                        /*add_to_gallery*/ true,
                                        /*gallery_position*/ pos++
                                ));
                            }
                        }
                        galleryRes = Map.of("added", galKeys.size());
                    }

                    // помечаем ALT/TITLE в наших таблицах (pending_meta=1) — воркер WP потом запишет
                    if (!dry) {
                        String title = naming.makePostTitle(sku, /*productName*/ null, /*fileName*/ null);
                        String slug  = naming.makePostSlug(title, 200);
                        String alt   = naming.limitAlt(title); // или ваша отдельная генерация

                        for (int i = 0; i < keys.size(); i++) {
                            Long imgId = s3dao.resolveImageIdByFullKey(keys.get(i));
                            if (imgId != null) {
                                s3dao.upsertAltTitle(imgId, sku, pid, i, alt, title);
                                s3dao.upsertOneLinkByImageId(imgId, sku, pid, i);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                errors.add(e.getMessage());
            }

            one.put("featured", featuredRes);
            one.put("gallery",  galleryRes);
            one.put("warnings", warnings);
            one.put("errors",   errors);
            results.add(one);
        }

        return Map.of("ok", true, "processed", skus.size(), "results", results);
    }
}