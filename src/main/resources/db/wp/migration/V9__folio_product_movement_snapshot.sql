ALTER TABLE folio_product_snapshot_generation
    ADD COLUMN analytics_schema_version SMALLINT NOT NULL DEFAULT 1
        AFTER horizon_months,
    ADD COLUMN movement_fact_rows BIGINT NOT NULL DEFAULT 0
        AFTER movement_rows;

ALTER TABLE folio_product_snapshot_item
    ADD COLUMN current_supplier VARCHAR(100) NULL
        AFTER product_name,
    ADD COLUMN supplier_state VARCHAR(16) NOT NULL DEFAULT 'MISSING'
        AFTER current_supplier;

ALTER TABLE folio_product_metric_monthly
    ADD COLUMN regular_sales_quantity DECIMAL(20,4) NOT NULL DEFAULT 0
        AFTER gross_profit,
    ADD COLUMN regular_sales_revenue DECIMAL(20,4) NOT NULL DEFAULT 0
        AFTER regular_sales_quantity,
    ADD COLUMN regular_sales_cogs DECIMAL(20,4) NOT NULL DEFAULT 0
        AFTER regular_sales_revenue,
    ADD COLUMN regular_gross_profit DECIMAL(20,4) NOT NULL DEFAULT 0
        AFTER regular_sales_cogs,
    ADD COLUMN one_off_sales_quantity DECIMAL(20,4) NOT NULL DEFAULT 0
        AFTER regular_gross_profit,
    ADD COLUMN one_off_sales_revenue DECIMAL(20,4) NOT NULL DEFAULT 0
        AFTER one_off_sales_quantity,
    ADD COLUMN one_off_sales_cogs DECIMAL(20,4) NOT NULL DEFAULT 0
        AFTER one_off_sales_revenue,
    ADD COLUMN one_off_gross_profit DECIMAL(20,4) NOT NULL DEFAULT 0
        AFTER one_off_sales_cogs;

ALTER TABLE folio_product_metric_current
    ADD COLUMN current_supplier VARCHAR(100) NULL
        AFTER product_name,
    ADD COLUMN supplier_state VARCHAR(16) NOT NULL DEFAULT 'MISSING'
        AFTER current_supplier,
    ADD COLUMN last_regular_sale_date DATE NULL
        AFTER last_sale_date,
    ADD COLUMN regular_sold_units_30d DECIMAL(20,4) NOT NULL DEFAULT 0
        AFTER sold_units_730d,
    ADD COLUMN regular_sold_units_90d DECIMAL(20,4) NOT NULL DEFAULT 0
        AFTER regular_sold_units_30d,
    ADD COLUMN regular_sold_units_365d DECIMAL(20,4) NOT NULL DEFAULT 0
        AFTER regular_sold_units_90d,
    ADD COLUMN regular_sold_units_730d DECIMAL(20,4) NOT NULL DEFAULT 0
        AFTER regular_sold_units_365d,
    ADD COLUMN one_off_sold_units_30d DECIMAL(20,4) NOT NULL DEFAULT 0
        AFTER regular_sold_units_730d,
    ADD COLUMN one_off_sold_units_90d DECIMAL(20,4) NOT NULL DEFAULT 0
        AFTER one_off_sold_units_30d,
    ADD COLUMN one_off_sold_units_365d DECIMAL(20,4) NOT NULL DEFAULT 0
        AFTER one_off_sold_units_90d,
    ADD COLUMN one_off_sold_units_730d DECIMAL(20,4) NOT NULL DEFAULT 0
        AFTER one_off_sold_units_365d,
    ADD COLUMN regular_revenue_90d DECIMAL(20,4) NOT NULL DEFAULT 0
        AFTER revenue_365d,
    ADD COLUMN regular_revenue_365d DECIMAL(20,4) NOT NULL DEFAULT 0
        AFTER regular_revenue_90d,
    ADD COLUMN one_off_revenue_90d DECIMAL(20,4) NOT NULL DEFAULT 0
        AFTER regular_revenue_365d,
    ADD COLUMN one_off_revenue_365d DECIMAL(20,4) NOT NULL DEFAULT 0
        AFTER one_off_revenue_90d,
    ADD COLUMN regular_gross_profit_90d DECIMAL(20,4) NOT NULL DEFAULT 0
        AFTER gross_profit_365d,
    ADD COLUMN regular_gross_profit_365d DECIMAL(20,4) NOT NULL DEFAULT 0
        AFTER regular_gross_profit_90d,
    ADD COLUMN one_off_gross_profit_90d DECIMAL(20,4) NOT NULL DEFAULT 0
        AFTER regular_gross_profit_365d,
    ADD COLUMN one_off_gross_profit_365d DECIMAL(20,4) NOT NULL DEFAULT 0
        AFTER one_off_gross_profit_90d;

CREATE TABLE folio_product_movement_fact (
    source_database          VARCHAR(64)    NOT NULL,
    warehouse_id            INT            NOT NULL,
    movement_recno          BIGINT         NOT NULL,
    generation_id           BIGINT         NOT NULL,
    document_id             BIGINT         NULL,
    document_number         DECIMAL(20,4)  NULL,
    document_date           DATE           NOT NULL,
    sku                     VARCHAR(64)    NOT NULL,
    quantity                DECIMAL(20,4)  NOT NULL DEFAULT 0,
    signed_quantity         DECIMAL(20,4)  NOT NULL DEFAULT 0,
    sale_amount             DECIMAL(20,4)  NOT NULL DEFAULT 0,
    accounting_value        DECIMAL(20,4)  NOT NULL DEFAULT 0,
    signed_accounting_value DECIMAL(20,4)  NOT NULL DEFAULT 0,
    movement_type           VARCHAR(4)     NULL,
    document_type           VARCHAR(4)     NULL,
    operation_kind          VARCHAR(64)    NULL,
    accounted               TINYINT(1)     NOT NULL DEFAULT 0,
    return_flag             TINYINT(1)     NOT NULL DEFAULT 0,
    movement_class          VARCHAR(40)    NOT NULL,
    stock_direction         VARCHAR(8)     NOT NULL,
    demand_mode             VARCHAR(24)    NOT NULL,
    payment_terms           VARCHAR(24)    NOT NULL,
    customer_segment        VARCHAR(20)    NOT NULL,
    counterparty_short_name VARCHAR(32)    NULL,
    counterparty_name       VARCHAR(250)   NULL,
    organization_type       VARCHAR(10)    NULL,
    current_supplier        VARCHAR(100)   NULL,
    supplier_state          VARCHAR(16)    NOT NULL,
    affects_stock           TINYINT(1)     NOT NULL DEFAULT 0,
    affects_financial_sales TINYINT(1)     NOT NULL DEFAULT 0,
    affects_planning_demand TINYINT(1)     NOT NULL DEFAULT 0,
    captured_at             DATETIME(3)    NOT NULL,
    PRIMARY KEY (source_database, warehouse_id, movement_recno),
    KEY idx_folio_product_movement_generation (generation_id),
    KEY idx_folio_product_movement_sku_date
        (source_database, warehouse_id, sku, document_date),
    KEY idx_folio_product_movement_class_date
        (source_database, warehouse_id, movement_class, demand_mode, document_date),
    KEY idx_folio_product_movement_supplier
        (source_database, warehouse_id, current_supplier, document_date),
    KEY idx_folio_product_movement_segment
        (source_database, warehouse_id, customer_segment, document_date),
    CONSTRAINT fk_folio_product_movement_generation
        FOREIGN KEY (generation_id)
        REFERENCES folio_product_snapshot_generation (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
