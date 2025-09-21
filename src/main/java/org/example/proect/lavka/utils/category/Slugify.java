package org.example.proect.lavka.utils.category;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;

@Component
public class Slugify {

    /** Универсальный метод: кириллицу транслитерирует, латиницу просто чистит. */
    public String slug(String input) {
        if (input == null) return "";

        // 1) Нормализация и снятие диакритики (для латиницы с акцентами и т.п.)
        String s = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");

        // 2) Если есть кириллица — транслитерируем посимвольно (uk + ru)
        if (containsCyrillic(s)) {
            s = cyrToLat(s);
        }

        // 3) Приводим к slug
        s = s.toLowerCase(Locale.ROOT)
                // всё, что не буква/цифра, заменяем на дефис
                .replaceAll("[^a-z0-9]+", "-")
                // схлопываем дефисы
                .replaceAll("-{2,}", "-")
                // убираем дефисы по краям
                .replaceAll("(^-|-$)", "");

        return s;
    }

    private boolean containsCyrillic(String s) {
        // Быстрая проверка: есть ли символы из блока кириллицы
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            // Юникод блоки: Russian/Ukrainian/Belarusian etc.
            if (Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.CYRILLIC) {
                return true;
            }
        }
        return false;
    }

    /** Транслитерация uk + ru. Максимально читаемый латинский эквивалент. */
    private String cyrToLat(String s) {
        StringBuilder out = new StringBuilder(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            // Быстро пропускаем ASCII
            if (c < 0x80) {
                out.append(c);
                continue;
            }

            switch (c) {
                // ====== УКРАИНСКИЙ ======
                case 'Є': out.append("Ye"); break;
                case 'є': out.append("ie"); break;   // внутри слова чаще "ie"
                case 'Ї': out.append("Yi"); break;
                case 'ї': out.append("i");  break;   // внутри слова как "i"
                case 'І': out.append("I");  break;
                case 'і': out.append("i");  break;
                case 'Ґ': out.append("G");  break;
                case 'ґ': out.append("g");  break;

                // ====== РУССКИЙ/ОБЩИЕ ======
                case 'Ё': out.append("Yo"); break;
                case 'ё': out.append("yo"); break;
                case 'Ж': out.append("Zh"); break;
                case 'ж': out.append("zh"); break;
                case 'Ч': out.append("Ch"); break;
                case 'ч': out.append("ch"); break;
                case 'Ш': out.append("Sh"); break;
                case 'ш': out.append("sh"); break;
                case 'Щ': out.append("Shch"); break;
                case 'щ': out.append("shch"); break;
                case 'Ю': out.append("Yu"); break;
                case 'ю': out.append("yu"); break;
                case 'Я': out.append("Ya"); break;
                case 'я': out.append("ya"); break;
                case 'Х': out.append("Kh"); break;
                case 'х': out.append("kh"); break;
                case 'Ц': out.append("Ts"); break;
                case 'ц': out.append("ts"); break;
                case 'Й': out.append("Y");  break;
                case 'й': out.append("y");  break;
                case 'Ы': out.append("Y");  break;
                case 'ы': out.append("y");  break;
                case 'Э': out.append("E");  break;
                case 'э': out.append("e");  break;
                case 'Ъ': /* твердый знак */ break;
                case 'ъ': /* твердый знак */ break;
                case 'Ь': /* мягкий знак  */ break;
                case 'ь': /* мягкий знак  */ break;

                // Базовые буквы (общие)
                case 'А': out.append("A"); break;   case 'а': out.append("a"); break;
                case 'Б': out.append("B"); break;   case 'б': out.append("b"); break;
                case 'В': out.append("V"); break;   case 'в': out.append("v"); break;
                case 'Г': out.append("G"); break;   case 'г': out.append("g"); break;
                case 'Д': out.append("D"); break;   case 'д': out.append("d"); break;
                case 'Е': out.append("E"); break;   case 'е': out.append("e"); break;
                case 'З': out.append("Z"); break;   case 'з': out.append("z"); break;
                case 'И': out.append("I"); break;   case 'и': out.append("i"); break;
                case 'К': out.append("K"); break;   case 'к': out.append("k"); break;
                case 'Л': out.append("L"); break;   case 'л': out.append("l"); break;
                case 'М': out.append("M"); break;   case 'м': out.append("m"); break;
                case 'Н': out.append("N"); break;   case 'н': out.append("n"); break;
                case 'О': out.append("O"); break;   case 'о': out.append("o"); break;
                case 'П': out.append("P"); break;   case 'п': out.append("p"); break;
                case 'Р': out.append("R"); break;   case 'р': out.append("r"); break;
                case 'С': out.append("S"); break;   case 'с': out.append("s"); break;
                case 'Т': out.append("T"); break;   case 'т': out.append("t"); break;
                case 'У': out.append("U"); break;   case 'у': out.append("u"); break;
                case 'Ф': out.append("F"); break;   case 'ф': out.append("f"); break;

                default:
                    // прочие символы — пробел, пунктуация и т.д. оставляем как есть, дальше очистим
                    out.append(c);
            }
        }
        return out.toString();
    }
}