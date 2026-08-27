CREATE TABLE folio_product_movement_fact_stage
    LIKE folio_product_movement_fact;

ALTER TABLE folio_product_movement_fact_stage
    DROP PRIMARY KEY,
    ADD PRIMARY KEY
        (generation_id, source_database, warehouse_id, movement_recno);

CREATE TABLE folio_product_metric_monthly_stage
    LIKE folio_product_metric_monthly;

ALTER TABLE folio_product_metric_monthly_stage
    DROP PRIMARY KEY,
    ADD PRIMARY KEY
        (generation_id, source_database, warehouse_id, sku, month_start);

CREATE TABLE folio_product_metric_current_stage
    LIKE folio_product_metric_current;

ALTER TABLE folio_product_metric_current_stage
    DROP PRIMARY KEY,
    ADD PRIMARY KEY
        (generation_id, source_database, warehouse_id, sku);

CREATE TABLE folio_product_metric_alert_stage
    LIKE folio_product_metric_alert;

ALTER TABLE folio_product_metric_alert_stage
    DROP PRIMARY KEY,
    ADD PRIMARY KEY
        (generation_id, source_database, warehouse_id, sku, alert_code);
