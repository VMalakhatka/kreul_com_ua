CREATE TABLE IF NOT EXISTS folio_balance_snapshot_generation (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    status              VARCHAR(20)  NOT NULL,
    trigger_source      VARCHAR(20)  NOT NULL,
    as_of_date          DATE         NOT NULL,
    started_at          DATETIME(3)  NOT NULL,
    completed_at        DATETIME(3)  NULL,
    total_clients       INT          NOT NULL DEFAULT 0,
    error_message       VARCHAR(1000) NULL,

    PRIMARY KEY (id),
    KEY idx_folio_balance_generation_status (status, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS folio_balance_snapshot_client (
    generation_id              BIGINT         NOT NULL,
    partner_short_name         VARCHAR(8)     NOT NULL,
    partner_name               VARCHAR(255)   NOT NULL,
    partner_type               VARCHAR(10)    NULL,
    city                       VARCHAR(255)   NOT NULL DEFAULT '',
    phone                      VARCHAR(100)   NOT NULL DEFAULT '',
    common_debt                DECIMAL(19,2)  NOT NULL,
    deferred_amount            DECIMAL(19,2)  NOT NULL,
    overdue_deferred_amount    DECIMAL(19,2)  NOT NULL,
    prepayment_amount          DECIMAL(19,2)  NOT NULL,
    payable_now                DECIMAL(19,2)  NOT NULL,
    calculated_at              DATETIME(3)    NOT NULL,

    PRIMARY KEY (generation_id, partner_short_name),
    KEY idx_folio_balance_client_payable (generation_id, payable_now, partner_short_name),
    KEY idx_folio_balance_client_type (generation_id, partner_type),
    CONSTRAINT fk_folio_balance_client_generation
        FOREIGN KEY (generation_id)
        REFERENCES folio_balance_snapshot_generation (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS folio_balance_snapshot_state (
    id                    TINYINT      NOT NULL,
    active_generation_id  BIGINT       NULL,
    updated_at            DATETIME(3)  NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT fk_folio_balance_state_generation
        FOREIGN KEY (active_generation_id)
        REFERENCES folio_balance_snapshot_generation (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO folio_balance_snapshot_state (id, active_generation_id, updated_at)
VALUES (1, NULL, NOW(3))
ON DUPLICATE KEY UPDATE id = id;

CREATE TABLE IF NOT EXISTS folio_balance_snapshot_live_client (
    partner_short_name         VARCHAR(8)     NOT NULL,
    as_of_date                 DATE           NOT NULL,
    partner_name               VARCHAR(255)   NOT NULL,
    common_debt                DECIMAL(19,2)  NOT NULL,
    deferred_amount            DECIMAL(19,2)  NOT NULL,
    overdue_deferred_amount    DECIMAL(19,2)  NOT NULL,
    prepayment_amount          DECIMAL(19,2)  NOT NULL,
    payable_now                DECIMAL(19,2)  NOT NULL,
    calculated_at              DATETIME(3)    NOT NULL,

    PRIMARY KEY (partner_short_name),
    KEY idx_folio_balance_live_date (as_of_date, calculated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS folio_balance_snapshot_lock (
    id            TINYINT       NOT NULL,
    owner_id      VARCHAR(100)  NULL,
    locked_until  DATETIME(3)   NULL,
    updated_at    DATETIME(3)   NOT NULL,

    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO folio_balance_snapshot_lock (id, owner_id, locked_until, updated_at)
VALUES (1, NULL, NULL, NOW(3))
ON DUPLICATE KEY UPDATE id = id;
