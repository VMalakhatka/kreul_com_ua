package org.example.proect.lavka.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.example.proect.lavka.client.LavkaLocationsClient;
import org.example.proect.lavka.dao.CardTovExportDaoImpl;
import org.example.proect.lavka.dao.wp.S3MediaIndexDao;
import org.example.proect.lavka.dao.wp.WpProductDao;
import org.example.proect.lavka.utils.AttachmentNaming;
import org.example.proect.lavka.wp_object.ImageAttachment;
import org.example.proect.lavka.wp_object.ProductRef;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.springframework.stereotype.Service;


import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class MsToWpImageSyncService {

    private static final Marker OPS = MarkerFactory.getMarker("OPS");
    private static final Marker MISMATCH = MarkerFactory.getMarker("MISMATCH");

    private final CardTovExportDaoImpl msDao;
    private final WpProductDao wp;
    private final S3MediaIndexDao s3dao;
    private final S3MediaIndexService s3;
    private final AttachmentNaming naming;
    private final LavkaLocationsClient mediaClient;

    public Map<String,Object> syncFromMs(List<String> skus,
                                         String mode, int galleryStartPos,
                                         int limitPerSku, boolean dry) {
        final String reqId = java.util.UUID.randomUUID().toString();
        MDC.put("reqId", reqId);
        MDC.put("mode", String.valueOf(mode));
        MDC.put("dry", String.valueOf(dry));

        int totalApplied = 0;
        int totalWarn    = 0;

        try {

            var ids = wp.findIdsBySkus(skus);
            var out = new ArrayList<Map<String, Object>>();

            log.info(OPS, "[sync.ops] img-sync start skus={} mode={} galleryStartPos={} limitPerSku={} dry={}",
                    skus.size(), mode, galleryStartPos, limitPerSku, dry);

            for (String sku : skus) {
                Long pid = ids.get(sku);
                MDC.put("sku", sku);
                MDC.put("pid", pid == null ? "" : String.valueOf(pid));
                try {
                    var one = new LinkedHashMap<String, Object>();
                    one.put("sku", sku);
                    one.put("productId", pid);

                    if (pid == null) {
                        String w = "product_not_found";
                        one.put("warnings", List.of(w));
                        totalWarn++;
                        // WARN → sync-errors.log
                        log.warn(MISMATCH, "[sync.mismatch] img-sync product_not_found sku={}", sku);
                        out.add(one);
                        MDC.remove("pid");
                        MDC.remove("sku");
                        continue;
                    }

                    CardTovExportDaoImpl.MsImagesBundle bundle;
                    try {
                        bundle = msDao.findImagesBundleBySku(sku);
                    } catch (Exception e) {
                        String msg = "bundle_error:" + e.getMessage();
                        one.put("warnings", List.of(msg));
                        totalWarn++;
                        log.warn(MISMATCH, "[sync.mismatch] bundle_failed sku={} pid={} msg={}",
                                sku, pid, e.getMessage());
                        out.add(one);
                        continue;
                    }
                    if (bundle == null) {
                        String msg = "bundle_error:Folio image bundle not found";
                        one.put("warnings", List.of(msg));
                        totalWarn++;
                        log.warn(MISMATCH, "[sync.mismatch] bundle_failed sku={} pid={} msg={}",
                                sku, pid, "Folio image bundle not found");
                        out.add(one);
                        continue;
                    }

                    var gallery = bundle.gallery() == null
                            ? List.<CardTovExportDaoImpl.MsGalleryImage>of()
                            : bundle.gallery();

                    var pref = new ProductRef(pid, sku,
                            /*name*/ null,
                            /*msNameArtic*/ bundle.nameArtic(),
                            null, null, null, null,
                            bundle.mainFileName());

                    var applied = new ArrayList<Map<String, Object>>();
                    var warnings = new ArrayList<String>();
                    var desiredLinks = new ArrayList<S3MediaIndexDao.DesiredLink>();
                    var desiredGallery = new ArrayList<LavkaLocationsClient.MediaDescriptor>();
                    LavkaLocationsClient.MediaDescriptor desiredFeatured = null;
                    boolean incomplete = false;
                    boolean includeFeatured = !"gallery".equalsIgnoreCase(mode);
                    boolean includeGallery = !"featured".equalsIgnoreCase(mode);

                    // Полный reconcile всегда читает всю галерею. galleryStartPos/limitPerSku
                    // остаются в контракте старого endpoint, но не могут ограничивать desired state.
                    if (includeFeatured && pref.imgFileName() != null && !pref.imgFileName().isBlank()) {
                        try {
                            var img = ImageAttachment.fromProduct(pref, s3dao, s3, naming);
                            verifyIndexedObject(img);
                            desiredFeatured = toDescriptor(img, null);
                            desiredLinks.add(toDesiredLink(img, 0));
                            applied.add(Map.of("file", img.getFileName(), "featured", true, "applied", !dry));
                        } catch (Exception e) {
                            String msg = "featured_error:" + e.getMessage();
                            warnings.add(msg);
                            totalWarn++;
                            incomplete = true;
                            log.warn(MISMATCH, "[sync.mismatch] featured_failed sku={} pid={} msg={}", sku, pid, e.getMessage());
                        }
                    }

                    if (includeGallery) {
                        int pos = 1;
                        Set<String> desiredKeys = new HashSet<>();
                        for (var g : gallery) {
                            try {
                                var img = ImageAttachment.fromProductAndFile(pref, g.fileName(), s3dao, s3, naming);
                                verifyIndexedObject(img);
                                if (!desiredKeys.add(img.getS3Key())) {
                                    throw new IllegalStateException("Duplicate gallery object in Folio bundle: " + img.getS3Key());
                                }
                                desiredGallery.add(toDescriptor(img, pos));
                                desiredLinks.add(toDesiredLink(img, pos));
                                applied.add(Map.of("file", img.getFileName(), "position", pos, "applied", !dry));
                                pos++;
                            } catch (Exception e) {
                                String w = "gallery_error:" + g.fileName() + ":" + e.getMessage();
                                warnings.add(w);
                                totalWarn++;
                                incomplete = true;
                                log.warn(MISMATCH, "[sync.mismatch] gallery_failed sku={} pid={} file={} msg={}",
                                        sku, pid, g.fileName(), e.getMessage());
                            }
                        }
                    }

                    boolean replaceGallery = includeGallery && !incomplete;
                    var reconcilePayload = new LavkaLocationsClient.MediaReconcilePayload(
                            pid,
                            sku,
                            desiredFeatured,
                            desiredGallery,
                            replaceGallery,
                            dry
                    );

                    try {
                        Map<String, Object> reconcile = mediaClient.mediaReconcile(reconcilePayload);
                        if (!Boolean.TRUE.equals(reconcile.get("ok"))) {
                            throw new IllegalStateException("Woo reconcile returned ok=false");
                        }
                        one.put("reconcile", reconcile);

                        if (!dry) {
                            try {
                                boolean replaceFeaturedLinks = desiredFeatured != null;
                                s3.reconcileLinksForSku(
                                        sku,
                                        desiredLinks,
                                        replaceFeaturedLinks,
                                        replaceGallery
                                );
                            } catch (Exception e) {
                                String msg = "link_persist_error:" + e.getMessage();
                                warnings.add(msg);
                                totalWarn++;
                                log.warn(MISMATCH,
                                        "[sync.mismatch] link_persist_failed sku={} pid={} msg={}",
                                        sku, pid, e.getMessage());
                            }
                        }
                        totalApplied += applied.size();
                        for (var item : applied) {
                            if (Boolean.TRUE.equals(item.get("featured"))) {
                                log.info(OPS, "[sync.ops] featured attached sku={} pid={} file={} dry={}",
                                        sku, pid, item.get("file"), dry);
                            } else {
                                log.info(OPS, "[sync.ops] gallery attached sku={} pid={} file={} pos={} dry={}",
                                        sku, pid, item.get("file"), item.get("position"), dry);
                            }
                        }
                    } catch (Exception e) {
                        String msg = "reconcile_error:" + e.getMessage();
                        warnings.add(msg);
                        totalWarn++;
                        log.warn(MISMATCH, "[sync.mismatch] reconcile_failed sku={} pid={} msg={}",
                                sku, pid, e.getMessage());
                        // Woo reconcile не применён: локальные связи тоже не считаем применёнными.
                        applied.replaceAll(item -> {
                            var failed = new LinkedHashMap<>(item);
                            failed.put("applied", false);
                            return failed;
                        });
                    }

                    one.put("applied", applied);
                    if (!warnings.isEmpty()) one.put("warnings", warnings);
                    out.add(one);

                    // короткий итог по SKU в ops-лог
                    log.info(OPS, "[sync.ops] img-sync sku-done sku={} pid={} applied={} warnings={}",
                            sku, pid, applied.size(), warnings.size());
                }finally {
                    MDC.remove("pid");
                    MDC.remove("sku");
                }
            }
            // Финальный итог
            log.info(OPS, "[sync.ops] img-sync done processed={} applied={} warnings={}",
                    skus.size(), totalApplied, totalWarn);

            return Map.of("ok", true, "processed", skus.size(), "results", out);
        }finally {
            MDC.clear();
        }
    }

    private void verifyIndexedObject(ImageAttachment image) {
        List<S3MediaIndexDao.Row> rows = s3dao.findByFileName(image.getFileNameLower());
        S3MediaIndexDao.Row selected = rows.stream()
                .filter(row -> Objects.equals(row.fullKey(), image.getS3Key()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Selected S3 index row disappeared: " + image.getS3Key()
                ));

        String selectedEtag = normalizeEtag(selected.etag());
        boolean ambiguous = rows.stream().anyMatch(row ->
                row.sizeBytes() != selected.sizeBytes()
                        || (!selectedEtag.isBlank()
                        && !normalizeEtag(row.etag()).isBlank()
                        && !Objects.equals(selectedEtag, normalizeEtag(row.etag())))
        );
        if (ambiguous) {
            throw new IllegalStateException("Ambiguous S3 objects with the same filename: " + image.getFileName());
        }
        s3.assertPhysicalObject(selected);
    }

    private LavkaLocationsClient.MediaDescriptor toDescriptor(ImageAttachment image, Integer position) {
        return new LavkaLocationsClient.MediaDescriptor(
                image.getAttachedFile(),
                image.getUrl(),
                image.getMime(),
                position,
                image.getAlt(),
                image.postTitle()
        );
    }

    private S3MediaIndexDao.DesiredLink toDesiredLink(ImageAttachment image, int position) {
        return new S3MediaIndexDao.DesiredLink(
                image.getImageId(),
                position,
                image.getAlt(),
                image.postTitle()
        );
    }

    private String normalizeEtag(String etag) {
        return etag == null ? "" : etag.replace("\"", "").trim();
    }

    public Map<String,Object> syncRangeBySku(String fromSku,
                                             String toSku,
                                             int chunkSize,
                                             String mode,
                                             int galleryStartPos,
                                             int limitPerSku,
                                             boolean dry) {
        final String reqId = java.util.UUID.randomUUID().toString();
        MDC.put("reqId", reqId);
        try {
            int totalProcessed = 0;
            int totalApplied   = 0;
            int totalWarns     = 0;

            String after = null; // keyset-курсор
            List<String> batch;

            log.info("[sync.ops] img-range start from={} to={} chunk={} mode={} galPos={} limitPerSku={} dry={}",
                    fromSku, toSku, chunkSize, mode, galleryStartPos, limitPerSku, dry);

            while (true) {
                batch = wp.listSkusBetween(fromSku, toSku, chunkSize, after); // см. DAO ниже
                if (batch.isEmpty()) break;

                Map<String, Object> one = syncFromMs(batch, mode, galleryStartPos, limitPerSku, dry);
                totalProcessed += (int) one.getOrDefault("processed", batch.size());

                // посчитаем applied+warnings из результата, если есть
                @SuppressWarnings("unchecked")
                List<Map<String,Object>> results = (List<Map<String,Object>>) one.getOrDefault("results", List.of());
                for (var r : results) {
                    @SuppressWarnings("unchecked")
                    List<Map<String,Object>> applied = (List<Map<String,Object>>) r.getOrDefault("applied", List.of());
                    @SuppressWarnings("unchecked")
                    List<String> warnings = (List<String>) r.getOrDefault("warnings", List.of());
                    totalApplied += applied.size();
                    totalWarns   += warnings.size();
                }

                // лог по чанку
                log.info("[sync.ops] img-range chunk done size={} applied+= {} warns+= {} lastSku={}",
                        batch.size(), totalApplied, totalWarns, batch.get(batch.size()-1));

                // продвинем курсор
                String last = batch.get(batch.size() - 1);
                after = last;

                // защита: если last превысил верхнюю границу — выходим
                if (last.compareTo(toSku) >= 0) break;
            }

            log.info("[sync.ops] img-range done processed={} applied={} warnings={}", totalProcessed, totalApplied, totalWarns);

            return Map.of(
                    "ok", true,
                    "processed", totalProcessed,
                    "applied", totalApplied,
                    "warnings", totalWarns,
                    "fromSku", fromSku,
                    "toSku", toSku,
                    "chunkSize", chunkSize
            );
        } finally {
            MDC.clear();
        }
    }

}
