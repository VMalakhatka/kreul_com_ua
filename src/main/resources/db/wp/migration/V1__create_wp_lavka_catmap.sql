-- Таблица маппинга категорий Woo <-> ФОЛИО-путь
CREATE TABLE IF NOT EXISTS wp_lavka_catmap (
                                               id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
                                               path_text         VARCHAR(700)    NOT NULL,
    path_hash         CHAR(40)
    GENERATED ALWAYS AS (sha1(path_text))
    STORED UNIQUE,

    l1                VARCHAR(190)    NULL,
    l2                VARCHAR(190)    NULL,
    l3                VARCHAR(190)    NULL,
    l4                VARCHAR(190)    NULL,
    l5                VARCHAR(190)    NULL,
    l6                VARCHAR(190)    NULL,
    depth             TINYINT UNSIGNED NOT NULL DEFAULT 1,

    parent_path_hash  CHAR(40)        NULL,

    wc_parent_id      BIGINT UNSIGNED NULL,
    wc_term_id        BIGINT UNSIGNED NULL,
    slug              VARCHAR(200)    NULL,

    l1_norm           VARCHAR(190)    NULL,
    l2_norm           VARCHAR(190)    NULL,
    l3_norm           VARCHAR(190)    NULL,
    l4_norm           VARCHAR(190)    NULL,
    l5_norm           VARCHAR(190)    NULL,
    l6_norm           VARCHAR(190)    NULL,

    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_path_hash (path_hash),
    KEY idx_parent_hash (parent_path_hash),
    KEY idx_wc_parent  (wc_parent_id),
    KEY ix_wc_term     (wc_term_id),
    KEY idx_levels     (l1(50), l2(50), l3(50), l4(50), l5(50), l6(50))
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Функция для извлечения «родительского» текста пути (нужна для триггеров).
-- ВАЖНО: Flyway исполняет через JDBC без DELIMITER; эта функция ДОЛЖНА идти одним statement.
DROP FUNCTION IF EXISTS lavka_parent_path_text;
CREATE FUNCTION lavka_parent_path_text(
    p_path_text VARCHAR(700) CHARACTER SET utf8mb4,
    p_depth     TINYINT UNSIGNED
)
    RETURNS VARCHAR(700) CHARACTER SET utf8mb4
    DETERMINISTIC
BEGIN
  IF p_depth IS NULL OR p_depth <= 1 THEN
    RETURN NULL;
END IF;
RETURN TRIM(SUBSTRING_INDEX(p_path_text, ' > ', p_depth-1));
END;

-- Триггеры для parent_path_hash (никаких DELIMITER не пишем, каждый CREATE TRIGGER одним statement)
DROP TRIGGER IF EXISTS trg_lavka_catmap_bi;
CREATE TRIGGER trg_lavka_catmap_bi
    BEFORE INSERT ON wp_lavka_catmap
    FOR EACH ROW
BEGIN
    SET NEW.parent_path_hash =
    CASE
      WHEN NEW.depth IS NULL OR NEW.depth <= 1 THEN NULL
      ELSE SHA1(lavka_parent_path_text(NEW.path_text, NEW.depth))
END;
END;

DROP TRIGGER IF EXISTS trg_lavka_catmap_bu;
CREATE TRIGGER trg_lavka_catmap_bu
    BEFORE UPDATE ON wp_lavka_catmap
    FOR EACH ROW
BEGIN
    SET NEW.parent_path_hash =
    CASE
      WHEN NEW.depth IS NULL OR NEW.depth <= 1 THEN NULL
      ELSE SHA1(lavka_parent_path_text(NEW.path_text, NEW.depth))
END;
END;