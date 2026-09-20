-- Keep live/staging column order identical: publication uses INSERT ... SELECT *.
ALTER TABLE folio_product_movement_fact
    ADD COLUMN IF NOT EXISTS source_info VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS internal_transfer_reservation TINYINT(1) NOT NULL DEFAULT 0,
    ADD INDEX IF NOT EXISTS ix_internal_reservation (source_database,warehouse_id,internal_transfer_reservation,sku);
ALTER TABLE folio_product_movement_fact_stage
    ADD COLUMN IF NOT EXISTS source_info VARCHAR(255) NULL,
    ADD COLUMN IF NOT EXISTS internal_transfer_reservation TINYINT(1) NOT NULL DEFAULT 0;
-- Existing generations are deliberately not promoted to schema 7. Rebuild snapshots.
