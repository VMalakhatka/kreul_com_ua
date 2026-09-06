-- Canonical physical-warehouse history; groups are evaluated at query time.
CREATE TABLE IF NOT EXISTS folio_product_availability_monthly (
    generation_id BIGINT NOT NULL,
    source_database VARCHAR(64) NOT NULL,
    warehouse_id INT NOT NULL,
    sku VARCHAR(64) NOT NULL,
    month_start DATE NOT NULL,
    known_mask BIGINT NOT NULL,
    available_mask BIGINT NOT NULL,
    negative_mask BIGINT NOT NULL,
    quality VARCHAR(32) NOT NULL,
    reconciliation_difference DECIMAL(24,8) NOT NULL,
    PRIMARY KEY (source_database, warehouse_id, sku, month_start),
    KEY idx_availability_generation (generation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS folio_product_availability_monthly_stage LIKE folio_product_availability_monthly;
ALTER TABLE folio_product_availability_monthly_stage
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (generation_id, source_database, warehouse_id, sku, month_start);
