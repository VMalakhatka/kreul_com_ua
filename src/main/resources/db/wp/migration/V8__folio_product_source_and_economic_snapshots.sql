CREATE TABLE IF NOT EXISTS folio_product_snapshot_generation (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    source_database       VARCHAR(64)   NOT NULL,
    warehouse_id          INT           NOT NULL,
    horizon_months        SMALLINT      NOT NULL,
    status                VARCHAR(24)   NOT NULL,
    trigger_source        VARCHAR(24)   NOT NULL,
    started_at            DATETIME(3)   NOT NULL,
    completed_at          DATETIME(3)   NULL,
    last_heartbeat_at     DATETIME(3)   NOT NULL,
    total_products        INT           NOT NULL DEFAULT 0,
    movement_rows         BIGINT        NOT NULL DEFAULT 0,
    monthly_metric_rows   INT           NOT NULL DEFAULT 0,
    unverified_products   INT           NOT NULL DEFAULT 0,
    dirty_products        INT           NOT NULL DEFAULT 0,
    new_products          INT           NOT NULL DEFAULT 0,
    removed_products      INT           NOT NULL DEFAULT 0,
    warehouse_digest      CHAR(64)      NULL,
    error_message         VARCHAR(1000) NULL,
    PRIMARY KEY (id),
    KEY idx_folio_product_generation_scope
        (source_database, warehouse_id, id),
    KEY idx_folio_product_generation_status
        (status, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS folio_product_snapshot_item (
    source_database             VARCHAR(64)    NOT NULL,
    warehouse_id                INT            NOT NULL,
    sku                         VARCHAR(64)    NOT NULL,
    product_name                VARCHAR(500)   NOT NULL DEFAULT '',
    observed_digest             CHAR(64)       NULL,
    applied_digest              CHAR(64)       NULL,
    verification_state          VARCHAR(20)    NOT NULL,
    present_in_folio            TINYINT(1)     NOT NULL DEFAULT 1,
    movement_count              BIGINT         NOT NULL DEFAULT 0,
    min_movement_recno          BIGINT         NULL,
    max_movement_recno          BIGINT         NULL,
    first_movement_date         DATE           NULL,
    last_movement_date          DATE           NULL,
    price_rule_count            INT            NOT NULL DEFAULT 0,
    first_seen_at               DATETIME(3)     NOT NULL,
    last_seen_at                DATETIME(3)     NOT NULL,
    last_observed_at            DATETIME(3)     NOT NULL,
    applied_at                  DATETIME(3)     NULL,
    last_generation_id          BIGINT          NOT NULL,
    last_error                  VARCHAR(1000)   NULL,
    PRIMARY KEY (source_database, warehouse_id, sku),
    KEY idx_folio_product_item_state
        (source_database, warehouse_id, verification_state, sku),
    KEY idx_folio_product_item_generation (last_generation_id),
    CONSTRAINT fk_folio_product_item_generation
        FOREIGN KEY (last_generation_id)
        REFERENCES folio_product_snapshot_generation (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS folio_product_snapshot_change (
    id                    BIGINT        NOT NULL AUTO_INCREMENT,
    generation_id         BIGINT        NOT NULL,
    source_database       VARCHAR(64)   NOT NULL,
    warehouse_id          INT           NOT NULL,
    sku                   VARCHAR(64)   NOT NULL,
    change_type           VARCHAR(20)   NOT NULL,
    before_digest         CHAR(64)      NULL,
    after_digest          CHAR(64)      NULL,
    detected_at           DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_folio_product_change_generation
        (generation_id, source_database, warehouse_id, sku),
    KEY idx_folio_product_change_sku
        (source_database, warehouse_id, sku, detected_at),
    CONSTRAINT fk_folio_product_change_generation
        FOREIGN KEY (generation_id)
        REFERENCES folio_product_snapshot_generation (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS folio_product_metric_monthly (
    source_database          VARCHAR(64)   NOT NULL,
    warehouse_id             INT           NOT NULL,
    sku                      VARCHAR(64)   NOT NULL,
    month_start              DATE          NOT NULL,
    opening_quantity         DECIMAL(20,4) NOT NULL DEFAULT 0,
    closing_quantity         DECIMAL(20,4) NOT NULL DEFAULT 0,
    opening_inventory_value  DECIMAL(20,4) NOT NULL DEFAULT 0,
    closing_inventory_value  DECIMAL(20,4) NOT NULL DEFAULT 0,
    receipt_quantity         DECIMAL(20,4) NOT NULL DEFAULT 0,
    receipt_cost             DECIMAL(20,4) NOT NULL DEFAULT 0,
    sales_quantity           DECIMAL(20,4) NOT NULL DEFAULT 0,
    sales_revenue            DECIMAL(20,4) NOT NULL DEFAULT 0,
    sales_cogs               DECIMAL(20,4) NOT NULL DEFAULT 0,
    gross_profit             DECIMAL(20,4) NOT NULL DEFAULT 0,
    return_quantity          DECIMAL(20,4) NOT NULL DEFAULT 0,
    return_revenue           DECIMAL(20,4) NOT NULL DEFAULT 0,
    average_inventory_value  DECIMAL(20,4) NOT NULL DEFAULT 0,
    inventory_turns          DECIMAL(20,6) NULL,
    gmroi                    DECIMAL(20,6) NULL,
    sell_through_percent     DECIMAL(12,4) NULL,
    generation_id            BIGINT        NOT NULL,
    calculated_at            DATETIME(3)   NOT NULL,
    PRIMARY KEY (source_database, warehouse_id, sku, month_start),
    KEY idx_folio_product_monthly_period
        (source_database, warehouse_id, month_start, sku),
    CONSTRAINT fk_folio_product_monthly_generation
        FOREIGN KEY (generation_id)
        REFERENCES folio_product_snapshot_generation (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS folio_product_metric_current (
    source_database          VARCHAR(64)    NOT NULL,
    warehouse_id             INT            NOT NULL,
    sku                      VARCHAR(64)    NOT NULL,
    product_name             VARCHAR(500)   NOT NULL DEFAULT '',
    physical_quantity        DECIMAL(20,4)  NOT NULL DEFAULT 0,
    reserved_quantity        DECIMAL(20,4)  NOT NULL DEFAULT 0,
    available_quantity       DECIMAL(20,4)  NOT NULL DEFAULT 0,
    accounting_price         DECIMAL(20,6)  NOT NULL DEFAULT 0,
    inventory_value          DECIMAL(20,4)  NOT NULL DEFAULT 0,
    last_receipt_date        DATE           NULL,
    last_sale_date           DATE           NULL,
    sold_units_30d           DECIMAL(20,4)  NOT NULL DEFAULT 0,
    sold_units_90d           DECIMAL(20,4)  NOT NULL DEFAULT 0,
    sold_units_365d          DECIMAL(20,4)  NOT NULL DEFAULT 0,
    sold_units_730d          DECIMAL(20,4)  NOT NULL DEFAULT 0,
    revenue_90d              DECIMAL(20,4)  NOT NULL DEFAULT 0,
    revenue_365d             DECIMAL(20,4)  NOT NULL DEFAULT 0,
    gross_profit_90d         DECIMAL(20,4)  NOT NULL DEFAULT 0,
    gross_profit_365d        DECIMAL(20,4)  NOT NULL DEFAULT 0,
    average_inventory_90d    DECIMAL(20,4)  NOT NULL DEFAULT 0,
    average_inventory_365d   DECIMAL(20,4)  NOT NULL DEFAULT 0,
    inventory_turns_365d     DECIMAL(20,6)  NULL,
    gmroi_365d               DECIMAL(20,6)  NULL,
    coverage_days            DECIMAL(20,2)  NULL,
    health_status            VARCHAR(24)    NOT NULL,
    generation_id            BIGINT         NOT NULL,
    calculated_at            DATETIME(3)    NOT NULL,
    PRIMARY KEY (source_database, warehouse_id, sku),
    KEY idx_folio_product_metric_health
        (source_database, warehouse_id, health_status, sku),
    KEY idx_folio_product_metric_profit
        (source_database, warehouse_id, gross_profit_365d),
    CONSTRAINT fk_folio_product_current_generation
        FOREIGN KEY (generation_id)
        REFERENCES folio_product_snapshot_generation (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS folio_product_metric_alert (
    source_database       VARCHAR(64)   NOT NULL,
    warehouse_id          INT           NOT NULL,
    sku                   VARCHAR(64)   NOT NULL,
    alert_code            VARCHAR(32)   NOT NULL,
    status                VARCHAR(16)   NOT NULL,
    severity              VARCHAR(12)   NOT NULL,
    first_seen_at         DATETIME(3)   NOT NULL,
    last_seen_at          DATETIME(3)   NOT NULL,
    resolved_at           DATETIME(3)   NULL,
    details               VARCHAR(1000) NULL,
    generation_id         BIGINT        NOT NULL,
    PRIMARY KEY (source_database, warehouse_id, sku, alert_code),
    KEY idx_folio_product_alert_active
        (source_database, warehouse_id, status, severity, sku),
    CONSTRAINT fk_folio_product_alert_generation
        FOREIGN KEY (generation_id)
        REFERENCES folio_product_snapshot_generation (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS folio_product_snapshot_lock (
    scope_key             VARCHAR(100) NOT NULL,
    owner_id              VARCHAR(100) NULL,
    locked_until          DATETIME(3)  NULL,
    updated_at            DATETIME(3)  NOT NULL,
    PRIMARY KEY (scope_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
