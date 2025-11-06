CREATE TABLE IF NOT EXISTS s3_media_index (
                                              id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                                              filename_lower VARCHAR(260) NOT NULL,         -- "con-200297237.jpg"
    full_key       VARCHAR(1024) NOT NULL,        -- "wp-content/uploads/2025/06/con-200297237.jpg"
    full_key_md5   BINARY(16) AS (UNHEX(MD5(full_key))) STORED,
    size_bytes     BIGINT NOT NULL,
    last_modified  TIMESTAMP NULL,
    etag           VARCHAR(64) NULL,
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_filename_key (filename_lower, full_key_md5),
    KEY idx_filename (filename_lower),
    KEY idx_md5 (full_key_md5)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;