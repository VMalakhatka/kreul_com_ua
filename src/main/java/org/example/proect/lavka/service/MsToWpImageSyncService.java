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
            List<String> allWarnings = new ArrayList<>();

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
                        allWarnings.add("sku=" + sku + " " + w);
                        totalWarn++;
                        // WARN → sync-errors.log
                        log.warn("[sync.errors] img-sync product_not_found sku={}", sku);
                        out.add(one);
                        MDC.remove("pid");
                        MDC.remove("sku");
                        continue;
                    }

                    var bundle = msDao.findImagesBundleBySku(sku);
                    var gallery = (bundle == null) ? List.<CardTovExportDaoImpl.MsGalleryImage>of() : bundle.gallery();

                    var pref = new ProductRef(pid, sku,
                            /*name*/ null,
                            /*msNameArtic*/ (bundle == null ? null : bundle.nameArtic()),
                            null, null, null, null,
                            (bundle == null ? null : bundle.mainFileName()));

                    var applied = new ArrayList<Map<String, Object>>();
                    var warnings = new ArrayList<String>();

                    // featured
                    if (!"gallery".equalsIgnoreCase(mode) && pref.imgFileName() != null && !pref.imgFileName().isBlank()) {
                        try {
                            var img = ImageAttachment.fromProduct(pref, s3dao, s3, naming);
                            if (!dry) {
                                img.attachAsFeatured(mediaClient);
                                img.persistLinkAndMeta(s3dao, 0);
                            }
                            applied.add(Map.of("file", img.getFileName(), "featured", true, "applied", !dry));
                            totalApplied++;
                            // INFO c маркером OPS → sync-ops.log
                            log.info(OPS, "[sync.ops] featured attached sku={} pid={} file={} dry={}",
                                    sku, pid, img.getFileName(), dry);
                        } catch (Exception e) {
                            String msg = "featured_error:" + e.getMessage();
                            warnings.add(msg);
                            allWarnings.add("sku=" + sku + " " + msg);
                            totalWarn++;
                            // WARN → sync-errors.log
                            log.warn("[sync.errors] featured_failed sku={} pid={} msg={}", sku, pid, e.getMessage(), e);
                        }
                    }

                    // gallery
                    if (!"featured".equalsIgnoreCase(mode) && gallery != null && !gallery.isEmpty()) {
                        int pos = galleryStartPos;
                        for (var g : gallery) {
                            if (applied.size() >= limitPerSku) break;
                            try {
                                var img = ImageAttachment.fromProductAndFile(pref, g.fileName(), s3dao, s3, naming);
                                if (!dry) {
                                    img.attachToGallery(mediaClient, pos);
                                    img.persistLinkAndMeta(s3dao, pos);
                                }
                                applied.add(Map.of("file", img.getFileName(), "position", pos, "applied", !dry));
                                totalApplied++;
                                log.info(OPS, "[sync.ops] gallery attached sku={} pid={} file={} pos={} dry={}",
                                        sku, pid, img.getFileName(), pos, dry);
                                pos++;
                            } catch (Exception e) {
                                String w = "gallery_error:" + g.fileName() + ":" + e.getMessage();
                                warnings.add(w);
                                allWarnings.add("sku=" + sku + " " + w);
                                totalWarn++;
                                log.warn("[sync.errors] gallery_failed sku={} pid={} file={} msg={}",
                                        sku, pid, g.fileName(), e.getMessage(), e);
                            }
                        }
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

            // если хочешь ещё раз «свести» все варнинги в errors-лог одной строкой:
            if (!allWarnings.isEmpty()) {
                logWarningsChunked(allWarnings, 50);
            }

            return Map.of("ok", true, "processed", skus.size(), "results", out);
        }finally {
            MDC.clear();
        }
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

    private static void logWarningsChunked(List<String> lines, int chunkSize) {
        for (int i = 0; i < lines.size(); i += chunkSize) {
            int end = Math.min(i + chunkSize, lines.size());
            String part = String.join(System.lineSeparator(), lines.subList(i, end));
            log.warn("[sync.errors] img-sync warnings {}/{}:\n{}", end, lines.size(), part);
        }
    }
}