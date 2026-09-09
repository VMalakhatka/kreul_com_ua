package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioDocumentAuditDao.Row;
import org.example.proect.lavka.dto.folio.FolioDocumentAuditResponse.*;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Workbook requirements are deliberately separate from unchanged profit inclusion rules. */
@Component
public class FolioDocumentAuditRules {
    public static final String VERSION = "2026-09-09.1";
    private static final String EVIDENCE = "WORKBOOK_REQUIREMENT; organization code aliases from existing Java where workbook gives labels";
    private static final Pattern FINANCIAL_NUMBER = Pattern.compile("(?<!\\d)(?:\\d[ \\u00a0-]*){8,}(?!\\d)");
    private static final Pattern FULL_DATE = Pattern.compile("(?<![0-9])[0-9]{1,2}[./-][0-9]{1,2}[./-][0-9]{4}(?![0-9])");
    private static final List<Rule> RULES = List.of(
            rule("SALARY_KYIV", "SALARY", "Зарплата Киев", 10, "зп", "REQUIRED", List.of("РАСХОДЫ КИЕВОП", "РАСХОДЫ КИЕВОПТ", "РАСХОДЫ КИЕВА"), "З/ПЛАТА"),
            rule("SALARY_ODESA", "SALARY", "Зарплата Одесса", 10, "зп", "REQUIRED", List.of("РАСХОДЫ ОДЕССЫ"), "З/П ОДЕС"),
            rule("SALARY_NETWORK", "SALARY", "Зарплата офис/склад", 11, "зп", "REQUIRED", List.of("РАСХОДЫ СЕТИ"), "З/ПЛ", "З/П", "Z/P RUB", "З/П RUB"),
            rule("RENT", "RENT", "Аренда", 16, "ар", "REQUIRED", List.of("АРЕНДА"), "АРЕНДАКИ", "АРЕНДАКО", "АРЕНДОД"),
            rule("UTILITIES", "UTILITIES", "Коммунальные услуги", 18, "кмн", "REQUIRED", List.of("КОММУН ПЛАТЕЖИ"), "КОММУНКИ", "КОММУНОД"),
            rule("INTERNET", "INTERNET", "Интернет", 19, "инт", "REQUIRED", List.of("УСЛУГИ СВЯЗИ"), "ИНТЕРНКИ", "ИНТЕРНОД", "ИНТЕРНКА"),
            rule("PHONE", "PHONE", "Телефон", 20, "тел", "REQUIRED", List.of("УСЛУГИ СВЯЗИ"), "ТЕЛЕФОНК", "ТЕЛЕФКАЛ", "ТЕЛЕФОДЕ"),
            rule("ADVERTISING", "ADVERTISING", "Реклама", 7, "рекл", "REQUIRED", List.of("РАСХОДЫ КИЕВОПТ", "РАСХОДЫ ОДЕССЫ", "РАСХОДЫ СЕТИ"), "РЕКЛАМКИ", "РЕКЛАМОД", "РЕКЛАМА"),
            rule("TRANSPORT_UKRAINE", "TRANSPORT_UKRAINE", "Транспорт Украина", 9, "тр", "NONE", List.of("РАСХОДЫ СЕТИ"), "ТРАНСПОР", "ТРАНСПКИ", "ТРАНСПОД"),
            rule("BANK", "BANK_SERVICES", "Услуги банка", 4, null, "CONDITIONAL", List.of("РАСХОДЫ СЕТИ"), "БАНКОВСК"),
            rule("TAXES", "TAXES", "Налоги", 21, null, "REQUIRED", List.of("НАЛОГИ"), "НАЛОГИ"),
            rule("HOUSEHOLD", "HOUSEHOLD", "Хозяйственные расходы", 6, null, "CONDITIONAL", List.of("РАСХОДЫ КИЕВОПТ", "РАСХОДЫ ОДЕССЫ"), "НЕРЕГУЛ"),
            rule("IRREGULAR", "IRREGULAR", "Нерегулярные расходы", 5, null, "CONDITIONAL", List.of("РАСХОДЫ СЕТИ"), "НЕРЕГДОН", "НЕРЕГМИХ", "НЕРЕГ КИ")
    );

    public List<Rule> catalog() {
        List<Rule> catalog = new ArrayList<>(RULES);
        catalog.add(new Rule("COMMON", "COMMON", "Основные поля", "расход / приход", List.of(1),
                "Required document fields; signs require review, not automatic correction", null, "NONE", List.of(), List.of(), List.of()));
        catalog.add(new Rule("PERIOD", "PERIOD", "Период начисления", "расход", List.of(10,11,16,18,19,20,21),
                "Explicit YYYY MM for recurring categories; contract dates are not accrual periods", null, "CONDITIONAL", List.of(), List.of(), List.of()));
        catalog.add(new Rule("COVERAGE", "COVERAGE", "Неполное правило категории", "расход / приход / отборы", List.of(2,3),
                "Recognize supplier payment/internal transfer/customer receipts without inventing missing validations", null, "NONE", List.of(), List.of(),
                List.of("Between-FOP rule explicitly unfinished in source workbook")));
        return List.copyOf(catalog);
    }

    public Item evaluate(Row row) {
        List<Finding> findings = new ArrayList<>();
        String organization = normalize(row.organizationCode());
        String operation = normalize(row.operationType());
        Rule rule = RULES.stream().filter(r -> r.organizationCodes().contains(organization)).findFirst().orElse(null);
        Category category;
        List<String> ruleIds = new ArrayList<>();
        if (rule != null) {
            ruleIds.add(rule.id());
            category = new Category(rule.categoryCode(), rule.label(), "RECOGNIZED", "NOT_RECALCULATED_BY_AUDIT");
            if (normalize(row.note()).isEmpty()) finding(findings, "NOTE_REQUIRED", "ERROR", "note", "", "Описание расхода / период по правилу",
                    "Заполните описание назначения расхода и, где требуется, период начисления.", rule.id());
            if (row.incoming() == null || row.incoming()) finding(findings, "EXPENSE_DIRECTION_REVIEW", "RULE_REVIEW", "direction",
                    direction(row), "OUTGOING", "Проверьте направление и природу операции; возврат не исправлять как обычный расход.", rule.id());
            if (rule.requiredSourceInfo() != null && !normalize(rule.requiredSourceInfo()).equals(normalize(row.sourceInfo()))) {
                // Rent-tax exception is explicit in workbook row17; cannot choose it from category alone.
                boolean rentException = rule.id().equals("RENT") && Boolean.TRUE.equals(row.bank());
                finding(findings, rentException ? "RENT_TAX_EXCEPTION_REVIEW" : "SOURCE_INFO_REQUIRED",
                        rentException ? "RULE_REVIEW" : "ERROR", "sourceInfo", mask(row.sourceInfo()), rule.requiredSourceInfo(),
                        rentException ? "Проверьте обычную аренду либо исключение налога аренды (расход, строка17)."
                                : "Укажите подтверждённый источник информации по правилу; документ исправляет оператор в ФОЛИО.", rule.id());
            }
            if (operation.isEmpty()) finding(findings, "OPERATION_TYPE_REQUIRED", "ERROR", "operationType", "", String.join(" / ",rule.expectedOperationTypes()),
                    "Заполните тип операции согласно назначению документа.", rule.id());
            else if (!rule.expectedOperationTypes().contains(operation)) finding(findings, "OPERATION_RULE_REVIEW", "RULE_REVIEW", "operationType",
                    mask(row.operationType()), String.join(" / ",rule.expectedOperationTypes()),
                    "Согласуйте соответствие текущего справочника формулировке правила; не меняйте тип автоматически.", rule.id());
            if (rule.requiredSourceInfo() == null || rule.periodRequirement().equals("CONDITIONAL"))
                finding(findings, "CONDITIONAL_REQUIREMENTS_REVIEW", "RULE_REVIEW", "rule", null, null,
                        "Уточните условные требования примечания и обозначения источника №1/№2 по исходной инструкции.", rule.id());
        } else {
            String code = switch (operation) {
                case "ОПЛАТА ПОСТАВЩИКУ" -> "SUPPLIER_PAYMENT";
                case "ПЕРЕМЕЩ НАЛ ПО СЕТИ" -> "INTERNAL_TRANSFER";
                case "РЕАЛИЗАЦИЯ" -> "CUSTOMER_PAYMENT";
                default -> "UNCLASSIFIED";
            };
            if (organization.equals("ТРАНС.ИМ")) code = "IMPORT_TRANSPORT";
            category = new Category(code, label(code), code.equals("UNCLASSIFIED") ? "UNRECOGNIZED" : "RECOGNIZED",
                    List.of("SUPPLIER_PAYMENT","INTERNAL_TRANSFER","CUSTOMER_PAYMENT").contains(code)
                            ? "KNOWN_NON_OPERATING_CATEGORY" : "NOT_RECALCULATED_BY_AUDIT");
            finding(findings, code.equals("UNCLASSIFIED") ? "DOCUMENT_CATEGORY_UNKNOWN" : "CATEGORY_RULE_INCOMPLETE",
                    "RULE_REVIEW", "category", code, null,
                    "Согласуйте правило для категории, направления, кассы/счёта и контрагента. Документ не исключён из аудита.", "COVERAGE");
            ruleIds.add("COVERAGE");
        }
        if (row.warehouseId() == null || row.warehouseId() <= 0) finding(findings, "WAREHOUSE_REQUIRED", "ERROR", "warehouseId",
                String.valueOf(row.warehouseId()), "positive warehouse ID", "Проверьте склад документа.", "COMMON");
        if (row.bank() == null || row.incoming() == null) finding(findings, "REGISTER_OR_DIRECTION_UNKNOWN", "ERROR", "register/direction",
                null, null, "Проверьте признаки кассового/банковского документа и прихода/расхода.", "COMMON");
        if (row.amount() == null) finding(findings, "AMOUNT_MISSING", "ERROR", "amount", null, null,
                "Проверьте сумму исходного документа; неизвестная сумма не равна нулю.", "COMMON");
        else if (row.amount().signum() <= 0) finding(findings, "NON_POSITIVE_AMOUNT_REVIEW", "RULE_REVIEW", "amount", row.amount().toPlainString(), null,
                "Проверьте коррекцию/сторно; знак сам по себе не доказывает ошибку.", "COMMON");
        if (organization.isEmpty()) finding(findings, "ORGANIZATION_REQUIRED", "ERROR", "organizationCode", "", null,
                "Заполните организацию согласно назначению документа.", "COMMON");
        var period = FolioExpensePeriod.resolve(row.note(), row.documentDate());
        if (period.problem()) finding(findings, period.status(), "RULE_REVIEW", "period", period.evidence(), "YYYY MM",
                "Уточните период: дата договора/несколько периодов не являются однозначным месяцем начисления.", "PERIOD");
        else if (rule != null && rule.periodRequirement().equals("REQUIRED") && period.status().equals("NO_PERIOD")) {
            boolean contractDate = row.note() != null && FULL_DATE.matcher(row.note()).find();
            finding(findings, contractDate ? "CONTRACT_DATE_REQUIRES_PERIOD_REVIEW" : "PERIOD_REQUIRED",
                    contractDate ? "RULE_REVIEW" : "ERROR", "period", period.evidence(), "YYYY MM",
                    "Укажите месяц начисления отдельно от даты документа/договора. Не переносите дату документа автоматически.", "PERIOD");
        }
        String status = findings.stream().anyMatch(f -> f.severity().equals("ERROR")) ? "ERROR"
                : findings.isEmpty() ? "VALID" : "RULE_REVIEW";
        boolean masked = !java.util.Objects.equals(mask(row.purposeCode()), row.purposeCode())
                || !java.util.Objects.equals(mask(row.sourceInfo()), row.sourceInfo())
                || !java.util.Objects.equals(mask(row.organizationCode()), row.organizationCode());
        Document document = new Document(row.paymentId(), row.documentNumber(), row.documentDate(), row.warehouseId(),
                row.bank(), direction(row), row.amount(), row.currencyCode(), mask(row.organizationCode()), row.organizationName(), mask(row.purposeCode()),
                mask(row.operationType()), mask(row.sourceInfo()), row.note(), period.evidence(),
                period.status().equals("VALID") ? period.month().toString() : null,
                "SCL_PLAT.SUM_POR", "NOT_CONFIRMED_FROM_COD_VALUT", masked);
        findings.stream().map(Finding::ruleId).filter(id -> !ruleIds.contains(id)).forEach(ruleIds::add);
        return new Item(document, category, status, List.copyOf(ruleIds), List.copyOf(findings));
    }

    private static Rule rule(String id, String category, String label, int row, String source, String period,
            List<String> operations, String... codes) {
        return new Rule(id,category,label,"расход",id.equals("ADVERTISING") ? List.of(7,8) : List.of(row),
                EVIDENCE + (category.equals("SALARY") ? "; USER_CONFIRMED_SOURCE_INFO_2026_09_09" : ""),source,period,operations,List.of(codes),
                List.of("Cash/account/warehouse mapping and counterparty identity are not validated", "Historical effective dates not established"));
    }
    private static void finding(List<Finding> list, String code, String severity, String field, String actual,
            String expected, String recommendation, String rule) { list.add(new Finding(code,severity,field,actual,expected,recommendation,rule)); }
    private static String direction(Row row) { return row.incoming() == null ? "UNKNOWN" : row.incoming() ? "INCOMING" : "OUTGOING"; }
    private static String normalize(String value) { return value == null ? "" : value.replace('\u00a0',' ').trim().replaceAll("\\s+"," ").toUpperCase(Locale.ROOT); }
    private static String mask(String value) { return value == null ? null : FINANCIAL_NUMBER.matcher(value).replaceAll("[MASKED]"); }
    private static String label(String code) { return switch(code) {
        case "SUPPLIER_PAYMENT" -> "Расчёты с поставщиком"; case "INTERNAL_TRANSFER" -> "Внутреннее перемещение денег";
        case "CUSTOMER_PAYMENT" -> "Реализация / поступление от покупателя"; case "IMPORT_TRANSPORT" -> "Импортный транспорт";
        default -> "Не классифицировано";
    }; }
}
