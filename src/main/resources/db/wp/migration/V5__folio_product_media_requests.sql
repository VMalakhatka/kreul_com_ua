CREATE TABLE IF NOT EXISTS folio_product_media_requests (
    external_request_id VARCHAR(190) NOT NULL,
    request_hash        CHAR(64)     NOT NULL,
    response_json       LONGTEXT     NOT NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (external_request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
