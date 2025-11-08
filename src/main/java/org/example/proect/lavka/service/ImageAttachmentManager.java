package org.example.proect.lavka.service;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.client.LavkaLocationsClient;
import org.example.proect.lavka.client.WooApiClient;
import org.example.proect.lavka.dao.wp.S3MediaIndexDao;
import org.example.proect.lavka.dao.wp.WpProductDao;
import org.example.proect.lavka.utils.ImageAttachmentFactory;
import org.example.proect.lavka.wp_object.ImageAttachment;
import org.example.proect.lavka.wp_object.ProductRef;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ImageAttachmentManager {

    private final ImageAttachmentFactory factory;
    private final S3MediaIndexService s3;
    private final S3MediaIndexDao s3dao;
    private final WpProductDao wp;
    private final WooApiClient mediaClient;

    @Transactional
    public void ensurePrimaryAttached(ProductRef p) {
        // 1) собрать объект картинки
        ImageAttachment img = ImageAttachment.fromProduct(
                p,
                s3dao,
                s3,
                factory.getNaming()
        );

        // 2) ALT/TITLE + связь в наших таблицах
        // достаём имя файла из s3Key
        String s3Key = img.wpAttachedFile(); // напр. "wp-content/uploads/2025/06/ser_1110.jpg"
        String fileName = s3Key.substring(s3Key.lastIndexOf('/') + 1); // "ser_1110.jpg"

        Long imageId = s3dao.resolveImageIdByFilename(fileName);
        if (imageId != null) {
            // pending_meta=1, pending_link=1
            s3dao.upsertAltTitle(imageId, p.sku(), p.productId(), 0, img.getAlt(), img.postTitle());
            s3dao.upsertOneLinkByImageId(imageId, p.sku(), p.productId(), 0);
        }

        // 3) проверить и привязать attachment в Woo
        Long attId = wp.findAttachmentIdByS3KeyOrGuid(s3Key, img.guid());
        Long currentFeatured = wp.findFeaturedId(p.productId());

        boolean needLink = (attId == null) || !Objects.equals(currentFeatured, attId);
        if (needLink) {
            mediaClient.mediaLinkOnly(new WooApiClient.MediaLinkOnlyPayload(
                    p.productId(),
                    s3Key,        // _wp_attached_file (относительный путь)
                    img.guid(),   // guid (абсолютный URL)
                    img.getMime(),
                    true,         // featured
                    false,        // галерею не трогаем тут
                    0
            ));
        }
    }
}