-- 1. Корневая категория
INSERT INTO wp_lavka_catmap (path_text, l1, depth, slug, l1_norm)
VALUES ('Скульптура', 'Скульптура', 1, 'skulptura', 'skulptura');

-- 2. Ветка из трёх уровней
INSERT INTO wp_lavka_catmap
(path_text, l1,l2,l3, depth, slug, l1_norm,l2_norm,l3_norm)
VALUES
    ('Скульптура > Пластичні маси > Cernit',
     'Скульптура','Пластичні маси','Cernit',
     3, 'cernit', 'skulptura','plastichni-masy','cernit');

-- 3. Углубим на 4-й уровень (проверка триггера parent_path_hash)
SET @id_last := (SELECT id FROM wp_lavka_catmap ORDER BY id DESC LIMIT 1);
UPDATE wp_lavka_catmap
SET path_text='Скульптура > Пластичні маси > Cernit > 56гр',
    depth=4, l4='56гр', l4_norm='56gr', slug='56gr'
WHERE id=@id_last;

-- 4. Дубликат пути должен упасть (уникальность path_hash)
-- Ожидаемый ERROR 1062
-- INSERT INTO wp_lavka_catmap (path_text, l1, depth, slug, l1_norm)
-- VALUES ('A > B', 'A', 2, 'b-dup', 'a');