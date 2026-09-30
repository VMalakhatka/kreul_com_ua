package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.ExpenseFilters;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.ExpenseLine;
import org.example.proect.lavka.dto.folio.FolioProfitTaxSettings;
import org.example.proect.lavka.service.folio.FolioProfitClassifier.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.example.proect.lavka.service.folio.FolioProfitClassifier.money;

/** Display breakdown of the existing classifier, never a second payment classifier. */
final class FolioProfitExpenseLines {
    private record Definition(String id, City city, Category category, String label, Treatment treatment,
            List<String> codes, String operation, boolean required, String note) {}
    private static Definition d(String id, City city, Category category, String label, String operation,
            boolean required, String note, String... codes) {
        return new Definition(id, city, category, label,
                category == Category.IMPORT_TRANSPORT ? Treatment.CAPITALIZED_IN_INVENTORY : Treatment.OPERATING_EXPENSE,
                List.of(codes), operation, required, note);
    }
    private static final List<Definition> DEFINITIONS = List.of(
            d("KYIV_RENT_SHOP", City.KYIV, Category.RENT, "Аренда магазина", "АРЕНДА", false, "", "АРЕНДАКИ"),
            d("KYIV_UTILITIES", City.KYIV, Category.UTILITIES, "Коммунальные", "КОММУН ПЛАТЕЖИ", false, "", "КОММУНКИ"),
            d("KYIV_SALARY_UAH", City.KYIV, Category.SALARY, "Зарплата UAH", "РАСХОДЫ КИЕВА", false, "Источник информации зп не обязателен", "З/ПЛАТА"),
            d("KYIV_SALARY_RUB", City.KYIV, Category.SALARY, "Зарплата RUB → UAH", "РАСХОДЫ СЕТИ", false, "Полное имя содержит ДОНЕЦК; перевод по принятому rubToUahRate", "З/П", "Z/P RUB", "З/П RUB"),
            d("KYIV_ADDITIONAL_SALARY", City.KYIV, Category.SALARY, "Дополнительные работы", "", false, "Не применять на Киеве; 0"),
            d("KYIV_BANK_SERVICES", City.KYIV, Category.BANK_SERVICES, "Услуги банка", "РАСХОДЫ СЕТИ", true, "Все склады кассы и банка, включая 5; полностью Киев", "БАНКОВСК"),
            d("KYIV_TAXES", City.KYIV, Category.TAXES, "Налоги — розница и опт", "НАЛОГИ", false, "Фирмы обоих списков; полностью Киев, без долей работников", "НАЛОГИ"),
            d("KYIV_IRREGULAR", City.KYIV, Category.IRREGULAR, "Нерегулярные ЧП", "", false, "", "НЕРЕГ КИ"),
            d("KYIV_ACCOUNTING", City.KYIV, Category.ACCOUNTING_SERVICES, "Бухгалтер", "ОПЛАТА ПОСТАВЩИКУ", false, "Примечание содержит БУХГАЛТЕР; специальное правило до исключения поставщиков", "ВЫХОДЦЕВ"),
            d("KYIV_HOUSEHOLD", City.KYIV, Category.HOUSEHOLD, "Хозяйственные", "РАСХОДЫ КИЕВА", true, "Фактически операция содержит КИЕВ, не точное равенство", "НЕРЕГУЛ"),
            d("KYIV_ADVERTISING", City.KYIV, Category.ADVERTISING, "Реклама", "", false, "", "РЕКЛАМКИ"),
            d("KYIV_TRANSPORT_UKRAINE", City.KYIV, Category.TRANSPORT_UKRAINE, "Транспорт Украина", "РАСХОДЫ СЕТИ", false, "ТРАНСПКИ — поддерживаемый legacy alias", "ТРАНСПОР", "ТРАНСПКИ"),
            d("KYIV_INTERNET", City.KYIV, Category.INTERNET, "Интернет", "УСЛУГИ СВЯЗИ", false, "", "ИНТЕРНКИ"),
            d("KYIV_PHONE", City.KYIV, Category.PHONE, "Телефон ТЕЛЕФОНК", "УСЛУГИ СВЯЗИ", false, "", "ТЕЛЕФОНК"),
            d("KYIV_RENT_WHOLESALE", City.KYIV, Category.RENT, "Аренда ОПТ", "АРЕНДА", false, "", "АРЕНДАКО"),
            d("KYIV_PHONE_KAL", City.KYIV, Category.PHONE, "Телефон ТЕЛЕФКАЛ", "УСЛУГИ СВЯЗИ", false, "", "ТЕЛЕФКАЛ"),
            d("KYIV_IMPORT_TRANSPORT", City.KYIV, Category.IMPORT_TRANSPORT, "Транспорт импорт", "РАСХОДЫ СЕТИ", false, "После общего итога расходов; влияние 0 по принятой политике; капитализация платежа здесь не доказывается", "ТРАНС.ИМ"),
            d("ODESA_RENT", City.ODESA, Category.RENT, "Аренда", "АРЕНДА", false, "", "АРЕНДОД"),
            d("ODESA_UTILITIES", City.ODESA, Category.UTILITIES, "Коммунальные", "КОММУН ПЛАТЕЖИ", false, "", "КОММУНОД"),
            d("ODESA_SALARY_DOCUMENTS", City.ODESA, Category.SALARY, "Зарплата по документам", "РАСХОДЫ ОДЕССЫ", false, "Источник информации зп — контрольный признак, не обязательный фильтр", "З/П ОДЕС"),
            d("ODESA_SALARY_RUB", City.ODESA, Category.SALARY, "Зарплата RUB → UAH", "", false, "Не заполнять"),
            d("ODESA_ADDITIONAL_SALARY", City.ODESA, Category.SALARY, "Дополнительные работы", "", false, "Ручной параметр odesaAdditionalSalary; документов нет"),
            d("ODESA_BANK_SERVICES", City.ODESA, Category.BANK_SERVICES, "Услуги банка", "", false, "Не заполнять — учтено на Киеве"),
            d("ODESA_TAXES", City.ODESA, Category.TAXES, "Налоги — розница и опт", "", false, "Не заполнять — учтено на Киеве"),
            d("ODESA_IRREGULAR", City.ODESA, Category.IRREGULAR, "Неналоговые нерегулярные расходы", "РАСХОДЫ СЕТИ", true, "Точный код, старые НЕРЕГМИХ/НЕРЕГДОН не являются alias", "НЕРЕГ ОД"),
            d("ODESA_ACCOUNTING", City.ODESA, Category.ACCOUNTING_SERVICES, "Бухгалтерские услуги", "", false, "Не заполнять"),
            d("ODESA_HOUSEHOLD", City.ODESA, Category.HOUSEHOLD, "Хозяйственные", "РАСХОДЫ ОДЕССЫ", true, "Фактически операция содержит ОДЕСС; при двух городах Одесса имеет приоритет", "НЕРЕГУЛ"),
            d("ODESA_ADVERTISING", City.ODESA, Category.ADVERTISING, "Реклама", "РАСХОДЫ ОДЕССЫ", false, "", "РЕКЛАМОД"),
            d("ODESA_TRANSPORT_UKRAINE", City.ODESA, Category.TRANSPORT_UKRAINE, "Транспорт", "РАСХОДЫ СЕТИ", true, "", "ТРАНСПОД"),
            d("ODESA_INTERNET", City.ODESA, Category.INTERNET, "Интернет", "УСЛУГИ СВЯЗИ", false, "Включая оплаты с киевских складов", "ИНТЕРНОД"),
            d("ODESA_PHONE", City.ODESA, Category.PHONE, "Телефон", "УСЛУГИ СВЯЗИ", false, "", "ТЕЛЕФОДЕ")
    );

    private static final class Total {
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal impact = BigDecimal.ZERO;
        int count;
        String source = "FOLIO";
    }
    private final Map<String, Total> totals = new LinkedHashMap<>();
    private final Map<String, Definition> definitions = new LinkedHashMap<>();
    private final FolioProfitTaxSettings settings;
    FolioProfitExpenseLines(FolioProfitTaxSettings settings) {
        this.settings = settings;
        DEFINITIONS.forEach(d -> { definitions.put(d.id(), d); totals.put(d.id(), new Total()); });
    }

    static String taxPool(ClassifiedPayment p, FolioProfitTaxAllocation share) {
        return share.pool(p.source());
    }
    record Allocation(BigDecimal kyiv, BigDecimal odesa, boolean operating) {}
    static Allocation allocation(ClassifiedPayment p, FolioProfitTaxAllocation share) {
        BigDecimal zero = money(BigDecimal.ZERO), amount = p.reportAmount();
        if (p.treatment() == Treatment.TAX_POOL) {
            if (taxPool(p, share).equals("MALAFOP") || taxPool(p, share).equals("KONDFOP"))
                return new Allocation(amount, zero, true);
        }
        if (p.treatment() == Treatment.OPERATING_EXPENSE)
            return new Allocation(p.city() == City.KYIV ? amount : zero, p.city() == City.ODESA ? amount : zero, true);
        return new Allocation(zero, zero, false);
    }

    static String documentLineId(ClassifiedPayment p, FolioProfitTaxAllocation share) {
        if (p.treatment() == Treatment.TAX_POOL) return taxPool(p, share).equals("UNALLOCATED")
                ? "SHARED_TAX_UNALLOCATED" : "KYIV_TAXES";
        // Category/city have already been chosen by the authoritative classifier.
        // Definition order only resolves two aliases within that same category.
        for (Definition d : DEFINITIONS) {
            if (d.city() == p.city() && d.category() == p.category() && d.codes().stream().anyMatch(c ->
                    c.equals(upper(p.source().purposeCode())) || c.equals(upper(p.source().expenseCode())))) return d.id();
        }
        return p.city().name() + "_" + p.category().name();
    }
    static List<String> lineIds(ClassifiedPayment p, FolioProfitTaxAllocation share) {
        return List.of(documentLineId(p, share));
    }

    void add(ClassifiedPayment p, FolioProfitTaxAllocation share) {
        Allocation a = allocation(p, share);
        for (String id : lineIds(p, share)) {
            definitions.computeIfAbsent(id, k -> new Definition(k, p.city(), p.category(), p.reason(),
                    p.treatment(), List.of(), "", false, "См. классификацию документа"));
            Total total = totals.computeIfAbsent(id, k -> new Total());
            BigDecimal amount = p.treatment() == Treatment.TAX_POOL && a.operating()
                    ? id.startsWith("KYIV_") ? a.kyiv() : a.odesa() : p.reportAmount();
            total.amount = total.amount.add(amount);
            total.impact = total.impact.add(a.operating() ? amount : BigDecimal.ZERO);
            total.count++;
        }
    }

    void manual(String id, BigDecimal amount, String source) {
        Total total = totals.get(id); total.amount = amount; total.impact = amount; total.source = source;
    }
    List<ExpenseLine> rows() {
        List<ExpenseLine> result = new ArrayList<>();
        for (Definition d : definitions.values()) {
            Total t = totals.get(d.id());
            boolean notApplicable = List.of("KYIV_ADDITIONAL_SALARY", "ODESA_BANK_SERVICES", "ODESA_TAXES",
                    "ODESA_SALARY_RUB", "ODESA_ACCOUNTING").contains(d.id());
            String mode = notApplicable ? "NOT_APPLICABLE" : "ALL";
            List<Integer> warehouses = List.of();
            List<String> purposes = d.id().equals("KYIV_TAXES") ? java.util.stream.Stream
                    .concat(settings.retailFirmCodes().stream(), settings.wholesaleFirmCodes().stream()).toList() : List.of();
            ExpenseFilters filters = new ExpenseFilters(d.codes(), d.operation().isEmpty() ? List.of() : List.of(d.operation()),
                    purposes, warehouses, warehouses, mode, mode, d.required(),
                    "Точный код после trim/upper: expenseCode ИЛИ purposeCode; не AND. "
                    + "Классификатор выбирает первое совпавшее правило. Тип операции — контрольный, если operationRequired=false. "
                    + (d.category() == Category.TAXES ? "purposeCodes — фактические коды фирм из снимка настроек; ищутся целыми токенами в purposeCode/expenseCode/name/documentClass. Пустой список отключает распределение этого пула. " : "") + d.note());
            int index = DEFINITIONS.indexOf(d);
            int order = index >= 0 ? index * 10 + 10 : 1000 + d.city().ordinal() * 100 + d.category().ordinal();
            result.add(new ExpenseLine(d.id(), order, d.city().name(), d.category().name(), d.label(),
                    t.count, money(t.amount), money(t.impact), "SHARED_TAX_UNALLOCATED".equals(d.id())
                            ? "UNALLOCATED" : notApplicable ? "NOT_APPLICABLE" : d.treatment().name(),
                    notApplicable ? "NOT_APPLICABLE" : t.source, filters));
        }
        return result.stream().sorted(java.util.Comparator.comparingInt(ExpenseLine::sortOrder)
                .thenComparing(ExpenseLine::lineId)).toList();
    }
    private static String upper(String text) { return text == null ? "" : text.trim().toUpperCase(Locale.ROOT); }
}
