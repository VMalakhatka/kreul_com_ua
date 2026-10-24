CREATE TABLE folio_profit_scope_settings (
    id INT NOT NULL,
    version BIGINT NOT NULL,
    settings_json LONGTEXT NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
