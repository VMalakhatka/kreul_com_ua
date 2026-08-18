package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioProfitReportDao.PaymentRow;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Map;

@Component
public class FolioProfitClassifier {

    private static final Map<Category, String> LABELS = Map.ofEntries(
            Map.entry(Category.RENT, "Аренда"),
            Map.entry(Category.UTILITIES, "Коммунальные услуги"),
            Map.entry(Category.SALARY, "Зарплата"),
            Map.entry(Category.BANK_SERVICES, "Услуги банка"),
            Map.entry(Category.ACCOUNTING_SERVICES, "Бухгалтерские услуги"),
            Map.entry(Category.HOUSEHOLD, "Хозяйственные расходы"),
            Map.entry(Category.ADVERTISING, "Реклама"),
            Map.entry(Category.TRANSPORT_UKRAINE, "Транспорт Украина"),
            Map.entry(Category.IMPORT_TRANSPORT, "Транспорт импорт"),
            Map.entry(Category.INTERNET, "Интернет"),
            Map.entry(Category.PHONE, "Телефон"),
            Map.entry(Category.IRREGULAR, "Нерегулярные расходы"),
            Map.entry(Category.TAXES, "Налоги"),
            Map.entry(Category.SUPPLIER_PAYMENT, "Оплата поставщику"),
            Map.entry(Category.INTERNAL_TRANSFER, "Внутреннее перемещение"),
            Map.entry(Category.NON_EXPENSE, "Не расход отчёта"),
            Map.entry(Category.UNCLASSIFIED, "Не классифицировано")
    );

    public ClassifiedPayment classify(PaymentRow row, BigDecimal rubToUahRate) {
        String documentClass = upper(row.documentClass());

        if (hasCode(row, "НАЛОГИ")) {
            return result(row, City.SHARED, Category.TAXES, Treatment.TAX_POOL,
                    money(row.amount()), "UAH", "Налоговый пул распределяется сервисом");
        }
        if (hasCode(row, "ВЫХОДЦЕВ") && contains(row.note(), "БУХГАЛТЕР")) {
            return result(row, City.KYIV, Category.ACCOUNTING_SERVICES, Treatment.OPERATING_EXPENSE,
                    money(row.amount()), "UAH", "Бухгалтерские услуги относятся на Киев");
        }
        if (hasCode(row, "АРЕНДАКИ", "АРЕНДАКО")) {
            return operating(row, City.KYIV, Category.RENT);
        }
        if (hasCode(row, "АРЕНДОД")) {
            return operating(row, City.ODESA, Category.RENT);
        }
        if (hasCode(row, "КОММУНКИ")) {
            return operating(row, City.KYIV, Category.UTILITIES);
        }
        if (hasCode(row, "КОММУНОД")) {
            return operating(row, City.ODESA, Category.UTILITIES);
        }
        if (hasCode(row, "З/ПЛАТА")) {
            return operating(row, City.KYIV, Category.SALARY);
        }
        if (hasCode(row, "З/П ОДЕС")) {
            return operating(row, City.ODESA, Category.SALARY);
        }
        if (hasCode(row, "З/П", "Z/P RUB", "З/П RUB") && contains(row.name(), "ДОНЕЦК")) {
            BigDecimal converted = money(row.amount().multiply(rubToUahRate));
            return result(row, City.KYIV, Category.SALARY, Treatment.OPERATING_EXPENSE,
                    converted, "RUB", "Зарплата Крым/OPT: RUB пересчитаны в UAH");
        }
        if (hasCode(row, "БАНКОВСК")) {
            City city = Integer.valueOf(5).equals(row.warehouseId()) ? City.ODESA : City.KYIV;
            return operating(row, city, Category.BANK_SERVICES);
        }
        if (hasCode(row, "НЕРЕГУЛ")) {
            if (contains(documentClass, "ОДЕСС")) {
                return operating(row, City.ODESA, Category.HOUSEHOLD);
            }
            if (contains(documentClass, "КИЕВ")) {
                return operating(row, City.KYIV, Category.HOUSEHOLD);
            }
        }
        if (hasCode(row, "НЕРЕГ КИ")) {
            return operating(row, City.KYIV, Category.IRREGULAR);
        }
        if (hasCode(row, "НЕРЕГМИХ", "НЕРЕГДОН")) {
            return operating(row, City.ODESA, Category.IRREGULAR);
        }
        if (hasCode(row, "РЕКЛАМКИ")) {
            return operating(row, City.KYIV, Category.ADVERTISING);
        }
        if (hasCode(row, "РЕКЛАМОД")) {
            return operating(row, City.ODESA, Category.ADVERTISING);
        }
        if (hasCode(row, "ТРАНСПОР", "ТРАНСПКИ")) {
            return operating(row, City.KYIV, Category.TRANSPORT_UKRAINE);
        }
        if (hasCode(row, "ТРАНСПОД")) {
            return operating(row, City.ODESA, Category.TRANSPORT_UKRAINE);
        }
        if (hasCode(row, "ТРАНС.ИМ")) {
            return result(row, City.KYIV, Category.IMPORT_TRANSPORT,
                    Treatment.CAPITALIZED_IN_INVENTORY, money(row.amount()), "UAH",
                    "Оплата показана отдельно; повторное влияние на прибыль равно нулю");
        }
        if (hasCode(row, "ИНТЕРНКИ")) {
            return operating(row, City.KYIV, Category.INTERNET);
        }
        if (hasCode(row, "ИНТЕРНОД")) {
            return operating(row, City.ODESA, Category.INTERNET);
        }
        if (hasCode(row, "ТЕЛЕФОНК", "ТЕЛЕФКАЛ")) {
            return operating(row, City.KYIV, Category.PHONE);
        }
        if (hasCode(row, "ТЕЛЕФОДЕ")) {
            return operating(row, City.ODESA, Category.PHONE);
        }

        if (contains(documentClass, "ОПЛАТА ПОСТАВЩИКУ")) {
            return result(row, City.NONE, Category.SUPPLIER_PAYMENT, Treatment.EXCLUDED,
                    money(row.amount()), "UAH", "Стоимость товара не повторяется поверх себестоимости");
        }
        if (contains(documentClass, "ПЕРЕМЕЩ НАЛ")) {
            return result(row, City.NONE, Category.INTERNAL_TRANSFER, Treatment.EXCLUDED,
                    money(row.amount()), "UAH", "Перемещение денег внутри сети");
        }
        if (contains(documentClass, "РЕАЛИЗАЦИЯ")) {
            return result(row, City.NONE, Category.NON_EXPENSE, Treatment.EXCLUDED,
                    money(row.amount()), "UAH", "Реализация и излишки не являются расходом");
        }
        return result(row, City.NONE, Category.UNCLASSIFIED, Treatment.UNCLASSIFIED,
                money(row.amount()), "UAH", "Для документа нет подтверждённого правила");
    }

    public String label(Category category) {
        return LABELS.get(category);
    }

    private static ClassifiedPayment operating(PaymentRow row, City city, Category category) {
        return result(row, city, category, Treatment.OPERATING_EXPENSE,
                money(row.amount()), "UAH", "Подтверждённое правило статьи и города");
    }

    private static ClassifiedPayment result(
            PaymentRow row,
            City city,
            Category category,
            Treatment treatment,
            BigDecimal reportAmount,
            String sourceCurrency,
            String reason) {
        return new ClassifiedPayment(row, city, category, treatment, reportAmount, sourceCurrency, reason);
    }

    private static boolean contains(String value, String expected) {
        return upper(value).contains(expected);
    }

    private static boolean hasCode(PaymentRow row, String... expectedCodes) {
        String purposeCode = upper(row.purposeCode());
        String expenseCode = upper(row.expenseCode());
        for (String expectedCode : expectedCodes) {
            if (expectedCode.equals(purposeCode) || expectedCode.equals(expenseCode)) {
                return true;
            }
        }
        return false;
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public enum City {
        KYIV,
        ODESA,
        SHARED,
        NONE
    }

    public enum Category {
        RENT,
        UTILITIES,
        SALARY,
        BANK_SERVICES,
        ACCOUNTING_SERVICES,
        HOUSEHOLD,
        ADVERTISING,
        TRANSPORT_UKRAINE,
        IMPORT_TRANSPORT,
        INTERNET,
        PHONE,
        IRREGULAR,
        TAXES,
        SUPPLIER_PAYMENT,
        INTERNAL_TRANSFER,
        NON_EXPENSE,
        UNCLASSIFIED
    }

    public enum Treatment {
        OPERATING_EXPENSE,
        TAX_POOL,
        CAPITALIZED_IN_INVENTORY,
        EXCLUDED,
        UNCLASSIFIED
    }

    public record ClassifiedPayment(
            PaymentRow source,
            City city,
            Category category,
            Treatment treatment,
            BigDecimal reportAmount,
            String sourceCurrency,
            String reason
    ) {
    }
}
