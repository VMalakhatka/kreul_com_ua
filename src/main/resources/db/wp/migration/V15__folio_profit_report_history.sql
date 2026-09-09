CREATE TABLE folio_profit_report_revision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_database VARCHAR(64) NOT NULL,
    report_month CHAR(7) CHARACTER SET ascii NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii NOT NULL,
    request_json LONGTEXT NOT NULL,
    status VARCHAR(20) CHARACTER SET ascii NOT NULL,
    report_json LONGTEXT NULL,
    audit_complete BOOLEAN NOT NULL DEFAULT FALSE,
    error_code VARCHAR(80) CHARACTER SET ascii NULL,
    created_at DATETIME(3) NOT NULL,
    completed_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_profit_request (source_database, request_id),
    KEY ix_profit_month_history (source_database, report_month, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE folio_profit_report_month (
    source_database VARCHAR(64) NOT NULL,
    report_month CHAR(7) CHARACTER SET ascii NOT NULL,
    published_revision_id BIGINT NULL,
    latest_revision_id BIGINT NOT NULL,
    PRIMARY KEY (source_database, report_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
