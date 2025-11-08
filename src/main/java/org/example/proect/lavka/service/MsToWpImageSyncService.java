package org.example.proect.lavka.service;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.client.LavkaLocationsClient;
import org.example.proect.lavka.client.WooApiClient;
import org.example.proect.lavka.dao.CardTovExportDaoImpl;
import org.example.proect.lavka.dao.wp.S3MediaIndexDao;
import org.example.proect.lavka.dao.wp.WpProductDao;
import org.example.proect.lavka.utils.AttachmentNaming;
import org.example.proect.lavka.wp_object.ImageAttachment;
import org.example.proect.lavka.wp_object.ProductRef;
import org.springframework.stereotype.Service;


import java.util.*;

@Service
@RequiredArgsConstructor
public class MsToWpImageSyncService {

    private final CardTovExportDaoImpl msDao;
    private final WpProductDao wp;
    private final S3MediaIndexDao s3dao;
    private final S3MediaIndexService s3;
    private final AttachmentNaming naming;
    private final LavkaLocationsClient mediaClient;

    public Map<String,Object> syncFromMs(List<String> skus,
                                         String mode, int galleryStartPos,
                                         int limitPerSku, boolean dry) {

        var ids = wp.findIdsBySkus(skus);
        var out = new ArrayList<Map<String,Object>>();

        for (String sku : skus) {
            var pid = ids.get(sku);
            var one = new LinkedHashMap<String,Object>();
            one.put("sku", sku);
            one.put("productId", pid);
            if (pid == null) { one.put("warnings", List.of("product_not_found")); out.add(one); continue; }

            var bundle  = msDao.findImagesBundleBySku(sku);
            var gallery = (bundle == null) ? List.<CardTovExportDaoImpl.MsGalleryImage>of() : bundle.gallery();
            var pref    = new ProductRef(pid, sku,
                    /*name*/ null,
                    /*msNameArtic*/ (bundle==null? null : bundle.nameArtic()),
                    null, null, null, null,
                    (bundle==null? null : bundle.mainFileName()));

            var applied  = new ArrayList<Map<String,Object>>();
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
                } catch (Exception e) {
                    warnings.add("featured_error:" + e.getMessage());
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
                        pos++;
                    } catch (Exception e) {
                        warnings.add("gallery_error:" + g.fileName() + ":" + e.getMessage());
                    }
                }
            }

            one.put("applied", applied);
            if (!warnings.isEmpty()) one.put("warnings", warnings);
            out.add(one);
        }

        return Map.of("ok", true, "processed", skus.size(), "results", out);
    }
}