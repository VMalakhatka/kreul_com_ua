ALTER TABLE folio_product_snapshot_generation
    ADD COLUMN as_of_date DATE NULL AFTER analytics_schema_version,
    ADD COLUMN warehouse_name VARCHAR(128) NULL AFTER warehouse_id;

ALTER TABLE folio_product_metric_current
    ADD COLUMN analytics_digest CHAR(64) NULL AFTER supplier_state,
    ADD COLUMN group_level_1_code VARCHAR(64) NULL AFTER analytics_digest,
    ADD COLUMN group_level_1_name VARCHAR(128) NULL AFTER group_level_1_code,
    ADD COLUMN group_level_2_code VARCHAR(64) NULL AFTER group_level_1_name,
    ADD COLUMN group_level_2_name VARCHAR(128) NULL AFTER group_level_2_code,
    ADD COLUMN group_level_3_code VARCHAR(64) NULL AFTER group_level_2_name,
    ADD COLUMN group_level_3_name VARCHAR(128) NULL AFTER group_level_3_code,
    ADD COLUMN group_level_4_code VARCHAR(64) NULL AFTER group_level_3_name,
    ADD COLUMN group_level_4_name VARCHAR(128) NULL AFTER group_level_4_code,
    ADD COLUMN group_level_5_code VARCHAR(64) NULL AFTER group_level_4_name,
    ADD COLUMN group_level_5_name VARCHAR(128) NULL AFTER group_level_5_code,
    ADD COLUMN group_level_6_code VARCHAR(64) NULL AFTER group_level_5_name,
    ADD COLUMN group_level_6_name VARCHAR(128) NULL AFTER group_level_6_code,
    ADD COLUMN department_code VARCHAR(32) NULL AFTER group_level_6_name,
    ADD COLUMN department_name VARCHAR(128) NULL AFTER department_code,
    ADD COLUMN product_type_code VARCHAR(32) NULL AFTER department_name,
    ADD COLUMN product_type_name VARCHAR(128) NULL AFTER product_type_code,
    ADD COLUMN unit_code VARCHAR(32) NULL AFTER product_type_name,
    ADD COLUMN unit_name VARCHAR(128) NULL AFTER unit_code,
    ADD COLUMN package_quantity DECIMAL(20,4) NULL AFTER unit_name,
    ADD COLUMN minimum_order_quantity DECIMAL(20,4) NULL AFTER package_quantity,
    ADD COLUMN minimum_stock DECIMAL(20,4) NULL AFTER minimum_order_quantity,
    ADD COLUMN maximum_stock DECIMAL(20,4) NULL AFTER minimum_stock,
    ADD COLUMN brand_code VARCHAR(64) NULL AFTER maximum_stock,
    ADD COLUMN brand_name VARCHAR(128) NULL AFTER brand_code,
    ADD KEY idx_folio_product_metric_group_1
        (source_database, warehouse_id, group_level_1_code, sku),
    ADD KEY idx_folio_product_metric_group_2
        (source_database, warehouse_id, group_level_2_code, sku),
    ADD KEY idx_folio_product_metric_type
        (source_database, warehouse_id, product_type_code, sku),
    ADD KEY idx_folio_product_metric_department
        (source_database, warehouse_id, department_code, sku),
    ADD KEY idx_folio_product_metric_unit
        (source_database, warehouse_id, unit_code, sku),
    ADD KEY idx_folio_product_metric_supplier_v3
        (source_database, warehouse_id, current_supplier, sku);

ALTER TABLE folio_product_metric_current_stage
    ADD COLUMN analytics_digest CHAR(64) NULL AFTER supplier_state,
    ADD COLUMN group_level_1_code VARCHAR(64) NULL AFTER analytics_digest,
    ADD COLUMN group_level_1_name VARCHAR(128) NULL AFTER group_level_1_code,
    ADD COLUMN group_level_2_code VARCHAR(64) NULL AFTER group_level_1_name,
    ADD COLUMN group_level_2_name VARCHAR(128) NULL AFTER group_level_2_code,
    ADD COLUMN group_level_3_code VARCHAR(64) NULL AFTER group_level_2_name,
    ADD COLUMN group_level_3_name VARCHAR(128) NULL AFTER group_level_3_code,
    ADD COLUMN group_level_4_code VARCHAR(64) NULL AFTER group_level_3_name,
    ADD COLUMN group_level_4_name VARCHAR(128) NULL AFTER group_level_4_code,
    ADD COLUMN group_level_5_code VARCHAR(64) NULL AFTER group_level_4_name,
    ADD COLUMN group_level_5_name VARCHAR(128) NULL AFTER group_level_5_code,
    ADD COLUMN group_level_6_code VARCHAR(64) NULL AFTER group_level_5_name,
    ADD COLUMN group_level_6_name VARCHAR(128) NULL AFTER group_level_6_code,
    ADD COLUMN department_code VARCHAR(32) NULL AFTER group_level_6_name,
    ADD COLUMN department_name VARCHAR(128) NULL AFTER department_code,
    ADD COLUMN product_type_code VARCHAR(32) NULL AFTER department_name,
    ADD COLUMN product_type_name VARCHAR(128) NULL AFTER product_type_code,
    ADD COLUMN unit_code VARCHAR(32) NULL AFTER product_type_name,
    ADD COLUMN unit_name VARCHAR(128) NULL AFTER unit_code,
    ADD COLUMN package_quantity DECIMAL(20,4) NULL AFTER unit_name,
    ADD COLUMN minimum_order_quantity DECIMAL(20,4) NULL AFTER package_quantity,
    ADD COLUMN minimum_stock DECIMAL(20,4) NULL AFTER minimum_order_quantity,
    ADD COLUMN maximum_stock DECIMAL(20,4) NULL AFTER minimum_stock,
    ADD COLUMN brand_code VARCHAR(64) NULL AFTER maximum_stock,
    ADD COLUMN brand_name VARCHAR(128) NULL AFTER brand_code;
