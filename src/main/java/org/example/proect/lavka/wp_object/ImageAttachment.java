package org.example.proect.lavka.wp_object;

import lombok.RequiredArgsConstructor;
import org.example.proect.lavka.service.S3MediaIndexService;
import org.example.proect.lavka.utils.AttachmentNaming;

public class ImageAttachment {
    private final AttachmentNaming naming;

    private final String sku;
    private final Long productId;
    private final String fileName;
    private final String s3Key;
    private final String url;
    private final String mime;
    private final String titleHuman;
    private final String alt;

    // --- фабрика «из товара + индекса» ---
    public static ImageAttachment fromProduct(
            ProductRef p,
            S3MediaIndexService s3,      // чтобы найти full_key и построить URL
            AttachmentNaming naming      // чтобы сгенерить title/slug/alt и т.п.
    ) {
        // имя файла
        String file = p.imgFileName();
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("Product has no image fileName");
        }

        // full_key из индекса (возьмём самый свежий/крупный)
        var rows = s3.find(file);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Image not found in s3_media_index by file: " + file);
        }
        var r = rows.get(0); // best match
        String fullKey = r.fullKey();
        String url     = s3.toPublicUrl(fullKey);

        // title/alt
        String title = naming.limitTitle(
                naming.makePostTitle(p.sku(), p.name(), file)
        );
        String alt = naming.limitAlt(
                String.join(" ",
                        nz(p.name()),
                        nz(p.volumeOrSize()),
                        nz(p.angle())
                ).replaceAll("\\s+"," ").trim()
        );
        if (alt.isBlank()) alt = naming.fileStem(file);

        return new ImageAttachment.Builder()
                .sku(p.sku())
                .productId(p.productId())
                .fileName(file)
                .s3Key(fullKey)
                .url(url)
                .mime("image/jpeg")
                .titleHuman(title)
                .alt(alt)
                .build(naming);
    }

    private static String nz(String s){ return s==null? "": s.trim(); }

    public String postTitle() {
        return titleHuman != null && !titleHuman.isBlank() ? titleHuman : fileName;
    }

    public String postName()  {
        return naming.makePostSlug(postTitle(), 200);
    }

    public String wpAttachedFile() {
        return s3Key;
    }

    public String guid() {
        return url;
    }

    public ImageAttachment(Builder b, AttachmentNaming naming) {
        this.naming = naming;
        this.sku = b.sku;
        this.productId = b.productId;
        this.fileName = b.fileName;
        this.s3Key = b.s3Key;
        this.url = b.url;
        this.mime = b.mime == null ? "image/jpeg" : b.mime;
        this.titleHuman = b.titleHuman;
        this.alt = b.alt;
    }

    public static class Builder {
        private String sku;
        private Long productId;
        private String fileName;
        private String s3Key;
        private String url;
        private String mime;
        private String titleHuman;
        private String alt;

        public Builder sku(String v){this.sku=v;return this;}
        public Builder productId(Long v){this.productId=v;return this;}
        public Builder fileName(String v){this.fileName=v;return this;}
        public Builder s3Key(String v){this.s3Key=v;return this;}
        public Builder url(String v){this.url=v;return this;}
        public Builder mime(String v){this.mime=v;return this;}
        public Builder titleHuman(String v){this.titleHuman=v;return this;}
        public Builder alt(String v){this.alt=v;return this;}

        public ImageAttachment build(AttachmentNaming naming) {
            return new ImageAttachment(this, naming);
        }
    }

    // getters...
    public Long getProductId(){return productId;}
    public String getAlt(){return alt;}
    public String getMime(){return mime;}
    public String getSku(){return sku;}
}