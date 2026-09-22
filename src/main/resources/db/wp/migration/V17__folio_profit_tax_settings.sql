CREATE TABLE folio_profit_tax_settings (
    id INT NOT NULL,
    version BIGINT NOT NULL,
    retail_firm_codes LONGTEXT NOT NULL,
    wholesale_firm_codes LONGTEXT NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
