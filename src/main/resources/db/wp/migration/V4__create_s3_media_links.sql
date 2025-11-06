-- V3__s3_media_links.sql
-- Если была старая версия таблицы связей — удалим
DROP TABLE IF EXISTS s3_media_links;

CREATE TABLE IF NOT EXISTS s3_media_links (
                                              id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                                              image_id       BIGINT UNSIGNED NOT NULL,     -- FK -> s3_media_index.id
                                              sku            VARCHAR(64) NULL,             -- может быть NULL (общие картинки / категорийные)
    product_id     BIGINT NULL,                  -- Woo post_id (если известен)
    position       INT NOT NULL DEFAULT 0,       -- порядок в галерее для этого SKU/товара
    alt_text       VARCHAR(255) NULL,
    title_text     VARCHAR(190) NULL,
    pending_meta   TINYINT(1) NOT NULL DEFAULT 1,
    pending_link   TINYINT(1) NOT NULL DEFAULT 1,
    last_error     VARCHAR(500) NULL,
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),

    CONSTRAINT fk_s3_links_image
    FOREIGN KEY (image_id) REFERENCES s3_media_index(id) ON DELETE CASCADE,

    -- защита от дублей: отдельно по SKU и отдельно по product_id
    UNIQUE KEY uq_by_sku  (image_id, sku,        position),
    UNIQUE KEY uq_by_prod (image_id, product_id, position),

    KEY idx_sku        (sku),
    KEY idx_product_id (product_id),
    KEY idx_pending    (pending_meta, pending_link)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;