package org.example.proect.lavka.wp_object;

import lombok.Getter;
import org.example.proect.lavka.client.LavkaLocationsClient;
import org.example.proect.lavka.client.WooApiClient;
import org.example.proect.lavka.dao.wp.S3MediaIndexDao;
import org.example.proect.lavka.service.S3MediaIndexService;
import org.example.proect.lavka.utils.AttachmentNaming;

import java.util.List;
import java.util.Locale;

/** Единый переносимый «пакет» данных для одной картинки товара. */
@Getter
public class ImageAttachment {
    // — товар —
    private final String sku;
    private final Long   productId;
    private final String productName; // msNameArtic или p.name()

    // — имена/индекс —
    private final String rawFilePath;   // как пришло из MS (может быть "pic/.../IMG.JPG")
    private final String fileName;      // basename в оригинальном регистре
    private final String fileNameLower; // basename lower — ключ поиска в индексе

    // — индекс / публичные данные —
    private final Long   imageId;       // id в s3_media_index
    private final String s3Key;         // full_key, например "wp-content/uploads/2025/06/ser_1110.jpg"
    private final String attachedFile;  // обычно == s3Key (то, что пишется в _wp_attached_file)
    private final String url;           // публичный absolute URL (guid)
    private final String mime;          // "image/jpeg" по умолчанию

    // — презентация —
    private final String titleHuman;
    private final String alt;
    private final String postSlug;

    // ==== фабрики ==============================================================================

    /** НОРМАЛИЗАЦИЯ: basename + lower — только для поиска в индексе. */
    public static String normalizeForIndex(String rawPath) {
        if (rawPath == null) return "";
        String t = rawPath.trim();
        int i = Math.max(t.lastIndexOf('/'), t.lastIndexOf('\\'));
        String base = (i >= 0) ? t.substring(i + 1) : t;
        return base.toLowerCase(Locale.ROOT);
    }

    private static String basenameOrig(String rawPath) {
        if (rawPath == null) return "";
        String t = rawPath.trim();
        int i = Math.max(t.lastIndexOf('/'), t.lastIndexOf('\\'));
        return (i >= 0) ? t.substring(i + 1) : t;
    }

    /** Основная картинка из ProductRef. */
    public static ImageAttachment fromProduct(
            ProductRef p,
            S3MediaIndexDao s3dao,
            S3MediaIndexService s3,
            AttachmentNaming naming
    ) {
        String raw = nz(p.imgFileName());
        if (raw.isBlank()) throw new IllegalArgumentException("Product has no image filename");

        String baseOrig  = basenameOrig(raw);                 // "Cr-CE0900056027_4.JPG"
        String baseLower = baseOrig.toLowerCase(Locale.ROOT);  // "cr-ce0900056027_4.jpg"

        Long imageId = s3dao.resolveImageIdByFilename(baseLower);
        if (imageId == null) throw new IllegalStateException("Not found in s3_media_index: " + baseOrig);

        var rows = s3dao.findByFileName(baseLower);
        if (rows.isEmpty()) throw new IllegalStateException("Index row disappeared: " + baseLower);

        var best     = rows.get(0);
        String full  = best.fullKey();         // правильный регистр!
        String url   = s3.toPublicUrl(full);
        String afile = naming.normalizeAttachedFile(full);

        String human = nz(p.msNameArtic());    // если пусто — можно подменить на p.name()
        if (human.isBlank()) human = nz(p.name());

        String title = naming.limitTitle(naming.makePostTitle(p.sku(), human, baseOrig));
        String alt   = naming.limitAlt((human + " " + nz(p.volumeOrSize()) + " " + nz(p.angle()))
                .replaceAll("\\s+"," ").trim());
        if (alt.isBlank()) alt = naming.fileStem(baseOrig);
        String slug  = naming.makePostSlug(title, 200);

        return new Builder()
                .sku(p.sku())
                .productId(p.productId())
                .productName(human)
                .rawFilePath(raw)
                .fileName(baseOrig)
                .fileNameLower(baseLower)
                .imageId(imageId)
                .s3Key(full)
                .attachedFile(afile)
                .url(url)
                .mime("image/jpeg")
                .titleHuman(title)
                .alt(alt)
                .postSlug(slug)
                .build();
    }

    /** Любая картинка по явному имени из MS (для галереи). */
    public static ImageAttachment fromProductAndFile(
            ProductRef p,
            String rawMsFilePath,
            S3MediaIndexDao s3dao,
            S3MediaIndexService s3,
            AttachmentNaming naming
    ) {
        String baseOrig = basenameOrig(rawMsFilePath);
        if (baseOrig.isBlank()) throw new IllegalArgumentException("Empty gallery filename");

        String baseLower = baseOrig.toLowerCase(Locale.ROOT);
        Long imageId = s3dao.resolveImageIdByFilename(baseLower);
        if (imageId == null) throw new IllegalStateException("Not found in s3_media_index: " + baseOrig);

        var rows = s3dao.findByFileName(baseLower);
        var best = rows.get(0);
        String full  = best.fullKey();
        String url   = s3.toPublicUrl(full);
        String afile = naming.normalizeAttachedFile(full);

        String human = nz(p.msNameArtic());
        if (human.isBlank()) human = nz(p.name());

        String title = naming.limitTitle(naming.makePostTitle(p.sku(), human, baseOrig));
        String alt   = naming.limitAlt((human + " " + nz(p.volumeOrSize()) + " " + nz(p.angle()))
                .replaceAll("\\s+"," ").trim());
        if (alt.isBlank()) alt = naming.fileStem(baseOrig);
        String slug  = naming.makePostSlug(title, 200);

        return new Builder()
                .sku(p.sku())
                .productId(p.productId())
                .productName(human)
                .rawFilePath(rawMsFilePath)
                .fileName(baseOrig)
                .fileNameLower(baseLower)
                .imageId(imageId)
                .s3Key(full)
                .attachedFile(afile)
                .url(url)
                .mime("image/jpeg")
                .titleHuman(title)
                .alt(alt)
                .postSlug(slug)
                .build();
    }

    // ==== действия (то, чего «не хватало») =====================================================

    /** Записать связь и мета (alt/title) в наши таблицы индекса. */
    public void persistLinkAndMeta(S3MediaIndexDao s3dao, int position) {
        if (imageId == null) throw new IllegalStateException("imageId is null");
        s3dao.upsertOneLinkByImageId(imageId, sku, productId, position);
        s3dao.upsertAltTitle(imageId, sku, productId, position, alt, postTitle());
    }

    // ImageAttachment
    public void attachAsFeatured(LavkaLocationsClient mediaClient) {
        mediaClient.mediaLinkOnly(new LavkaLocationsClient.MediaLinkOnlyPayload(
                productId, attachedFile, url, mime,
                true, false, 0,
                alt, titleHuman   // ← добавили
        ));
    }

    public void attachToGallery(LavkaLocationsClient mediaClient, int position) {
        mediaClient.mediaLinkOnly(new LavkaLocationsClient.MediaLinkOnlyPayload(
                productId, attachedFile, url, mime,
                false, true, Math.max(0, position),
                alt, titleHuman   // ← добавили
        ));
    }
    // ==== builder / ctor =======================================================================

    public static class Builder {
        private String sku;
        private Long   productId;
        private String productName;

        private String rawFilePath;
        private String fileName;
        private String fileNameLower;

        private Long   imageId;
        private String s3Key;
        private String attachedFile;
        private String url;
        private String mime;
        private String titleHuman;
        private String alt;
        private String postSlug;

        public Builder sku(String v){ this.sku=v; return this; }
        public Builder productId(Long v){ this.productId=v; return this; }
        public Builder productName(String v){ this.productName=v; return this; }
        public Builder rawFilePath(String v){ this.rawFilePath=v; return this; }
        public Builder fileName(String v){ this.fileName=v; return this; }
        public Builder fileNameLower(String v){ this.fileNameLower=v; return this; }
        public Builder imageId(Long v){ this.imageId=v; return this; }
        public Builder s3Key(String v){ this.s3Key=v; return this; }
        public Builder attachedFile(String v){ this.attachedFile=v; return this; }
        public Builder url(String v){ this.url=v; return this; }
        public Builder mime(String v){ this.mime=v; return this; }
        public Builder titleHuman(String v){ this.titleHuman=v; return this; }
        public Builder alt(String v){ this.alt=v; return this; }
        public Builder postSlug(String v){ this.postSlug=v; return this; }

        public ImageAttachment build(){ return new ImageAttachment(this); }
    }

    private ImageAttachment(Builder b) {
        this.sku = b.sku;
        this.productId = b.productId;
        this.productName = b.productName;

        this.rawFilePath = b.rawFilePath;
        this.fileName = b.fileName;
        this.fileNameLower = b.fileNameLower;

        this.imageId = b.imageId;
        this.s3Key = b.s3Key;
        this.attachedFile = b.attachedFile;
        this.url = b.url;
        this.mime = (b.mime == null) ? "image/jpeg" : b.mime;

        this.titleHuman = b.titleHuman;
        this.alt = b.alt;
        this.postSlug = b.postSlug;
    }

    // ==== helpers ===============================================================================
    private static String nz(String s){ return (s == null) ? "" : s; }

    public String wpAttachedFile() { return attachedFile; } // alias
    public String guid()           { return url; }          // alias

    public String postTitle(){
        return (titleHuman != null && !titleHuman.isBlank()) ? titleHuman : fileName;
    }
    public String postName(){ return postSlug; }
}