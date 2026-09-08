CREATE TABLE IF NOT EXISTS folio_accounting_price_diagnostic (
    id BIGINT NOT NULL AUTO_INCREMENT,
    job_id VARCHAR(36) CHARACTER SET ascii NOT NULL,
    source_database VARCHAR(64) NOT NULL,
    warehouse_id INT NOT NULL,
    sku VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    preview_only BOOLEAN NOT NULL,
    error_code VARCHAR(80) NOT NULL,
    message TEXT NOT NULL,
    diagnostics_json LONGTEXT NOT NULL,
    created_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_arithmetic_job_sku (job_id,source_database,warehouse_id,sku,preview_only),
    KEY ix_arithmetic_warehouse_time (source_database,warehouse_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
