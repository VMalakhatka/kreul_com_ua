-- Таблица состояния фоновой синхронизации товаров
-- одна строка с id=1, мы идём по каталогу постранично
CREATE TABLE IF NOT EXISTS sync_product_state (
                                                  id INT NOT NULL,
                                                  after_sku VARCHAR(190) NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
    );

-- Инициализируем единственную строку, если её нет
INSERT INTO sync_product_state (id, after_sku)
VALUES (1, NULL)
    ON DUPLICATE KEY UPDATE after_sku = sync_product_state.after_sku;