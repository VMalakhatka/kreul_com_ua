-- Проверка автогенерации path_hash
SELECT path_text, path_hash, SHA1(path_text) AS should_be
FROM wp_lavka_catmap ORDER BY id DESC LIMIT 5;

-- Проверка parent_path_hash для depth=3
SELECT id, path_text, depth, parent_path_hash
FROM wp_lavka_catmap
WHERE depth=3
ORDER BY id DESC LIMIT 1;

-- Контрольный расчёт ожидаемого parent SHA1
SELECT SHA1('Скульптура > Пластичні маси') AS expected_parent_hash;

-- Быстрый поиск по path_hash (должен брать uk_path_hash)
EXPLAIN SELECT * FROM wp_lavka_catmap WHERE path_hash = SHA1('A > B');

-- Быстрый выбор «не привязанных к Woo» (должен брать ix_wc_term)
EXPLAIN SELECT * FROM wp_lavka_catmap WHERE wc_term_id IS NULL ORDER BY id DESC LIMIT 10;

-- Быстрый поиск по родителю (должен брать idx_parent_hash)
EXPLAIN SELECT * FROM wp_lavka_catmap WHERE parent_path_hash = SHA1('A');