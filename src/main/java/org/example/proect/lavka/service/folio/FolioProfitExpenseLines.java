package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.ExpenseFilters;
import org.example.proect.lavka.dto.folio.FolioProfitReportResponse.ExpenseLine;
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
            d("KYIV_RENT_WHOLESALE", City.KYIV, Category.RENT, "Аренда ОПТ", "АРЕНДА", false, "", "АРЕНДАКО"),
            d("KYIV_UTILITIES", City.KYIV, Category.UTILITIES, "Коммунальные", "КОММУН ПЛАТЕЖИ", false, "", "КОММУНКИ"),
            d("KYIV_SALARY_UAH", City.KYIV, Category.SALARY, "Зарплата UAH", "РАСХОДЫ КИЕВА", false, "Источник информации зп не обязателен", "З/ПЛАТА"),
            d("KYIV_SALARY_RUB", City.KYIV, Category.SALARY, "Зарплата RUB → UAH", "РАСХОДЫ СЕТИ", false, "Полное имя содержит ДОНЕЦК; перевод по принятому rubToUahRate", "З/П", "Z/P RUB", "З/П RUB"),
            d("KYIV_ADDITIONAL_SALARY", City.KYIV, Category.SALARY, "Дополнительные работы", "", false, "Ручной параметр kyivAdditionalSalary; документов нет"),
            d("KYIV_BANK_SERVICES", City.KYIV, Category.BANK_SERVICES, "Услуги банка", "РАСХОДЫ СЕТИ", false, "Кроме склада 5; неизвестный склад исторически относится к Киеву", "БАНКОВСК"),
            d("KYIV_TAX_MALAFOP", City.KYIV, Category.TAXES, "Налоги МАЛАФОП: доля Киева", "НАЛОГИ", false, "Пул MALAFOP/МАЛАФОП ищется в назначении, коде, имени или операции; остаток после округления доли Одессы", "НАЛОГИ"),
            d("KYIV_TAX_KONDFOP", City.KYIV, Category.TAXES, "Налоги КОНДФОП", "НАЛОГИ", false, "Пул KONDFOP/КОНДФОП; полностью Киев", "НАЛОГИ"),
            d("KYIV_IRREGULAR", City.KYIV, Category.IRREGULAR, "Нерегулярные ЧП", "", false, "", "НЕРЕГ КИ"),
            d("KYIV_ACCOUNTING", City.KYIV, Category.ACCOUNTING_SERVICES, "Бухгалтер", "ОПЛАТА ПОСТАВЩИКУ", false, "Примечание содержит БУХГАЛТЕР; специальное правило до исключения поставщиков", "ВЫХОДЦЕВ"),
            d("KYIV_HOUSEHOLD", City.KYIV, Category.HOUSEHOLD, "Хозяйственные", "РАСХОДЫ КИЕВА", true, "Фактически операция содержит КИЕВ, не точное равенство", "НЕРЕГУЛ"),
            d("KYIV_ADVERTISING", City.KYIV, Category.ADVERTISING, "Реклама", "", false, "", "РЕКЛАМКИ"),
            d("KYIV_TRANSPORT_UKRAINE", City.KYIV, Category.TRANSPORT_UKRAINE, "Транспорт Украина", "РАСХОДЫ СЕТИ", false, "ТРАНСПКИ — поддерживаемый legacy alias", "ТРАНСПОР", "ТРАНСПКИ"),
            d("KYIV_IMPORT_TRANSPORT", City.KYIV, Category.IMPORT_TRANSPORT, "Транспорт импорт", "РАСХОДЫ СЕТИ", false, "Влияние 0 по принятой политике; капитализация конкретного платежа здесь не доказывается", "ТРАНС.ИМ"),
            d("KYIV_INTERNET", City.KYIV, Category.INTERNET, "Интернет", "УСЛУГИ СВЯЗИ", false, "", "ИНТЕРНКИ"),
            d("KYIV_PHONE", City.KYIV, Category.PHONE, "Телефон ТЕЛЕФОНК", "УСЛУГИ СВЯЗИ", false, "", "ТЕЛЕФОНК"),
            d("KYIV_PHONE_KAL", City.KYIV, Category.PHONE, "Телефон ТЕЛЕФКАЛ", "УСЛУГИ СВЯЗИ", false, "", "ТЕЛЕФКАЛ"),
            d("ODESA_RENT", City.ODESA, Category.RENT, "Аренда", "АРЕНДА", false, "", "АРЕНДОД"),
            d("ODESA_UTILITIES", City.ODESA, Category.UTILITIES, "Коммунальные", "КОММУН ПЛАТЕЖИ", false, "", "КОММУНОД"),
            d("ODESA_SALARY_DOCUMENTS", City.ODESA, Category.SALARY, "Зарплата по документам", "РАСХОДЫ ОДЕССЫ", false, "Источник информации зп — контрольный признак, не обязательный фильтр", "З/П ОДЕС"),
            d("ODESA_ADDITIONAL_SALARY", City.ODESA, Category.SALARY, "Доплата зарплаты", "", false, "Ручной параметр odesaAdditionalSalary; документов нет"),
            d("ODESA_BANK_SERVICES", City.ODESA, Category.BANK_SERVICES, "Услуги банка", "РАСХОДЫ СЕТИ", false, "Только склад 5", "БАНКОВСК"),
            d("ODESA_TAX_MALAFOP", City.ODESA, Category.TAXES, "Налоги МАЛАФОП: доля Одессы", "НАЛОГИ", false, "Пул MALAFOP/МАЛАФОП; каждый документ × odesaTaxShare, HALF_UP", "НАЛОГИ"),
            d("ODESA_TAX_KONDFOP", City.ODESA, Category.TAXES, "Налоги КОНДФОП: не относятся на Одессу", "НАЛОГИ", false, "Пул KONDFOP/КОНДФОП полностью относится на Киев; здесь 0", "НАЛОГИ"),
            d("ODESA_IRREGULAR_MIH", City.ODESA, Category.IRREGULAR, "Нерегулярные НЕРЕГМИХ", "", false, "", "НЕРЕГМИХ"),
            d("ODESA_IRREGULAR_DON", City.ODESA, Category.IRREGULAR, "Нерегулярные НЕРЕГДОН", "", false, "", "НЕРЕГДОН"),
            d("ODESA_HOUSEHOLD", City.ODESA, Category.HOUSEHOLD, "Хозяйственные", "РАСХОДЫ ОДЕССЫ", true, "Фактически операция содержит ОДЕСС; при двух городах Одесса имеет приоритет", "НЕРЕГУЛ"),
            d("ODESA_ADVERTISING", City.ODESA, Category.ADVERTISING, "Реклама", "РАСХОДЫ ОДЕССЫ", false, "", "РЕКЛАМОД"),
            d("ODESA_TRANSPORT_UKRAINE", City.ODESA, Category.TRANSPORT_UKRAINE, "Транспорт", "", false, "", "ТРАНСПОД"),
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
    FolioProfitExpenseLines() { DEFINITIONS.forEach(d -> { definitions.put(d.id(), d); totals.put(d.id(), new Total()); }); }

    static String taxPool(ClassifiedPayment p) {
        var r = p.source();
        String ids = upper(r.purposeCode()) + " " + upper(r.expenseCode()) + " " + upper(r.name()) + " " + upper(r.documentClass());
        if (ids.contains("MALAFOP") || ids.contains("МАЛАФОП")) return "MALAFOP";
        if (ids.contains("KONDFOP") || ids.contains("КОНДФОП")) return "KONDFOP";
        return "UNKNOWN";
    }
    record Allocation(BigDecimal kyiv, BigDecimal odesa, boolean operating) {}
    static Allocation allocation(ClassifiedPayment p, BigDecimal share) {
        BigDecimal zero = money(BigDecimal.ZERO), amount = p.reportAmount();
        if (p.treatment() == Treatment.TAX_POOL) {
            if (taxPool(p).equals("MALAFOP")) {
                BigDecimal odesa = money(amount.multiply(share));
                return new Allocation(money(amount.subtract(odesa)), odesa, true);
            }
            if (taxPool(p).equals("KONDFOP")) return new Allocation(amount, zero, true);
        }
        if (p.treatment() == Treatment.OPERATING_EXPENSE)
            return new Allocation(p.city() == City.KYIV ? amount : zero, p.city() == City.ODESA ? amount : zero, true);
        return new Allocation(zero, zero, false);
    }

    static String documentLineId(ClassifiedPayment p) {
        if (p.treatment() == Treatment.TAX_POOL) return "SHARED_TAX_" + taxPool(p);
        // Category/city have already been chosen by the authoritative classifier.
        // Definition order only resolves two aliases within that same category.
        for (Definition d : DEFINITIONS) {
            if (d.city() == p.city() && d.category() == p.category() && d.codes().stream().anyMatch(c ->
                    c.equals(upper(p.source().purposeCode())) || c.equals(upper(p.source().expenseCode())))) return d.id();
        }
        return p.city().name() + "_" + p.category().name();
    }
    static List<String> lineIds(ClassifiedPayment p) {
        if (p.treatment() == Treatment.TAX_POOL) {
            if (taxPool(p).equals("MALAFOP")) return List.of("KYIV_TAX_MALAFOP", "ODESA_TAX_MALAFOP");
            if (taxPool(p).equals("KONDFOP")) return List.of("KYIV_TAX_KONDFOP");
        }
        return List.of(documentLineId(p));
    }

    void add(ClassifiedPayment p, BigDecimal share) {
        Allocation a = allocation(p, share);
        for (String id : lineIds(p)) {
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
            boolean bank = d.category() == Category.BANK_SERVICES;
            String mode = bank ? d.city() == City.ODESA ? "INCLUDE" : "EXCLUDE" : "ALL";
            List<Integer> warehouses = bank ? List.of(5) : List.of();
            ExpenseFilters filters = new ExpenseFilters(d.codes(), d.operation().isEmpty() ? List.of() : List.of(d.operation()),
                    List.of(), warehouses, warehouses, mode, mode, d.required(),
                    "Точный код после trim/upper: expenseCode ИЛИ purposeCode; не AND. "
                    + "Классификатор выбирает первое совпавшее правило. Тип операции — контрольный, если operationRequired=false. " + d.note());
            int index = DEFINITIONS.indexOf(d);
            int order = index >= 0 ? index * 10 + 10 : 1000 + d.city().ordinal() * 100 + d.category().ordinal();
            result.add(new ExpenseLine(d.id(), order, d.city().name(), d.category().name(), d.label(),
                    t.count, money(t.amount), money(t.impact), d.treatment().name(), t.source, filters));
        }
        return result.stream().sorted(java.util.Comparator.comparingInt(ExpenseLine::sortOrder)
                .thenComparing(ExpenseLine::lineId)).toList();
    }
    private static String upper(String text) { return text == null ? "" : text.trim().toUpperCase(Locale.ROOT); }
}
