package org.example.proect.lavka.service;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.client.LavkaLocationsClient;
import org.example.proect.lavka.client.WooApiClient;
import org.example.proect.lavka.dao.wp.WpAttachmentDao;
import org.example.proect.lavka.utils.AttachmentNaming;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class MediaOnlineService {
    private final LavkaLocationsClient woo;          // уже умеет mediaLinkOnly
    private final WpAttachmentDao wpAttDao;
    private final AttachmentNaming naming;   // makePostTitle/Slug/limit...
    private final OvhS3MediaService s3;

    @Transactional
    public void linkFeaturedAndSetMeta(long productId,
                                       String sku,
                                       String fullKey,
                                       String publicUrl,
                                       String mime // "image/jpeg"
    ){
        // 1) линкуем (получаем attachment_id)
        Map<String,Object> resp = woo.mediaLinkOnly(new LavkaLocationsClient.MediaLinkOnlyPayload(
                productId,
                naming.keyToAttachedFile(fullKey), // "2025/06/ser_1110.jpg"
                publicUrl,                          // https://.../2025/06/ser_1110.jpg
                mime,
                true,   // set_featured
                false,  // add_to_gallery
                0,null,null
        ));
        long attId = ((Number)resp.get("attachment_id")).longValue();

        // 2) alt/title/slug в SQL
        String title = naming.limitTitle(naming.makePostTitle(sku, /*productName*/ null, /*fileName*/ null));
        String slug  = naming.makePostSlug(title, 200);
        String alt   = naming.limitAlt(title); // или твоя генерация alt отдельно

        wpAttDao.ensureAttachedFile(attId, naming.keyToAttachedFile(fullKey)); // подстраховка
        wpAttDao.updateTitleAndSlug(attId, title, slug);
        wpAttDao.upsertAlt(attId, alt);
    }

    @Transactional
    public void linkGalleryAndSetMeta(long productId,
                                      String sku,
                                      List<String> fullKeys,
                                      int startPos,
                                      String mime
    ) {
        int pos = startPos;

        for (String fullKey : fullKeys) {

            // ✅ Получаем реальный URL (виртуальный хостинг)
            String publicUrl = s3.toPublicUrl(fullKey);

            Map<String,Object> resp = woo.mediaLinkOnly(
                    new LavkaLocationsClient.MediaLinkOnlyPayload(
                            productId,
                            naming.keyToAttachedFile(fullKey),  // относительный путь
                            publicUrl,                         // реальный URL
                            (mime != null ? mime : "image/jpeg"),
                            false,   // set_featured
                            true,    // add_to_gallery
                            pos++,null,null
                    )
            );

            // ✅ attachment_id возвращается WP-эндпоинтом
            long attId = ((Number)resp.get("attachment_id")).longValue();

            // ✅ Генерируем alt/title
            String title = naming.limitTitle(naming.makePostTitle(sku, null, null));
            String slug  = naming.makePostSlug(title, 200);
            String alt   = naming.limitAlt(title);

            // ✅ Запись в WP
            wpAttDao.ensureAttachedFile(attId, naming.keyToAttachedFile(fullKey));
            wpAttDao.updateTitleAndSlug(attId, title, slug);
            wpAttDao.upsertAlt(attId, alt);
        }
    }
}