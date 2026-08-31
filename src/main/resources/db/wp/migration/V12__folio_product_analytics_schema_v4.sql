ALTER TABLE folio_product_metric_current
    ADD COLUMN primary_barcode VARCHAR(128) NULL AFTER maximum_stock,
    ADD KEY idx_folio_product_metric_primary_barcode
        (source_database, primary_barcode, warehouse_id, sku);

ALTER TABLE folio_product_metric_current_stage
    ADD COLUMN primary_barcode VARCHAR(128) NULL AFTER maximum_stock;
