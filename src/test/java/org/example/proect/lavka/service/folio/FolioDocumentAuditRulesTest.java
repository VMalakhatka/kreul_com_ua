package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioDocumentAuditDao.Row;
import org.example.proect.lavka.dao.folio.FolioProfitReportDao.PaymentRow;
import org.example.proect.lavka.dto.folio.FolioDocumentAuditResponse.Finding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class FolioDocumentAuditRulesTest {
    private final FolioDocumentAuditRules rules = new FolioDocumentAuditRules();
    static Row salary(String source, String note) {
        return new Row(1,"1",LocalDate.of(2026,8,4),5,false,false,new BigDecimal("123.45"),null,
                "З/П ОДЕС","Synthetic salary","0004","РАСХОДЫ ОДЕССЫ",source,note);
    }

    @Test void missingSalarySourceIsErrorWithoutChangingProfitSelection() {
        Row row = salary(null,"2026 07");
        var item = rules.evaluate(row);
        assertThat(item.status()).isEqualTo("ERROR");
        assertThat(item.category().code()).isEqualTo("SALARY");
        assertThat(item.category().recognition()).isEqualTo("RECOGNIZED");
        assertThat(item.findings()).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo("SOURCE_INFO_REQUIRED");
            assertThat(f.field()).isEqualTo("sourceInfo");
            assertThat(f.expected()).isEqualTo("зп");
        });
        assertThat(item.document().documentDate()).isEqualTo(LocalDate.of(2026,8,4));
        assertThat(item.document().resolvedPeriod()).isEqualTo("2026-07");
        var profit = new FolioProfitClassifier().classify(new PaymentRow(row.paymentId(), row.documentNumber(),
                row.documentDate(),row.amount(),false,5,row.purposeCode(),row.organizationCode(),row.organizationName(),
                row.operationType(),row.note(),row.sourceInfo()),new BigDecimal("0.41"));
        assertThat(profit.category()).isEqualTo(FolioProfitClassifier.Category.SALARY);
        assertThat(profit.treatment()).isEqualTo(FolioProfitClassifier.Treatment.OPERATING_EXPENSE);
    }
    @ParameterizedTest @ValueSource(strings={"зп","ЗП"," зп ","\u00a0ЗП\u00a0"})
    void salarySourceCaseAndWhitespaceDoNotCreateFalseError(String source) {
        assertThat(rules.evaluate(salary(source,"2026 07")).status()).isEqualTo("VALID");
    }
    @Test void salaryNeedsAccrualPeriodNotAutomaticDocumentMonth() {
        var item=rules.evaluate(salary("зп","salary"));
        assertThat(item.findings()).extracting(Finding::code).contains("PERIOD_REQUIRED");
        assertThat(item.document().resolvedPeriod()).isNull();
    }
    @Test void transportNeedsDescriptionButNotAnAccrualMonth() {
        var empty=rules.evaluate(new Row(7,"7",LocalDate.of(2026,8,1),1,false,false,BigDecimal.TEN,null,
                "ТРАНСПОР","Synthetic transport","0004","РАСХОДЫ СЕТИ","тр",""));
        assertThat(empty.findings()).extracting(Finding::code).contains("NOTE_REQUIRED").doesNotContain("PERIOD_REQUIRED");
        var described=rules.evaluate(new Row(7,"7",LocalDate.of(2026,8,1),1,false,false,BigDecimal.TEN,null,
                "ТРАНСПОР","Synthetic transport","0004","РАСХОДЫ СЕТИ","тр","Доставка материалов"));
        assertThat(described.status()).isEqualTo("VALID");
    }
    @ParameterizedTest @ValueSource(strings={"договор от 01.07.2026","2026 07-08","2026 07 и 2026 08","2026 13"})
    void ambiguousAndContractDatesAreReviewNotSilentFallback(String note) {
        var item=rules.evaluate(salary("зп",note));
        assertThat(item.status()).isEqualTo("RULE_REVIEW");
        assertThat(item.document().resolvedPeriod()).isNull();
    }
    @Test void operationMismatchIsRuleReviewAndEmptyOperationIsDataError() {
        Row s=salary("зп","2026 07");
        for(String operation:List.of("NEW_OPERATION","")) {
            var item=rules.evaluate(new Row(s.paymentId(),s.documentNumber(),s.documentDate(),5,false,false,s.amount(),
                    null,s.organizationCode(),s.organizationName(),s.purposeCode(),operation,s.sourceInfo(),s.note()));
            assertThat(item.status()).isEqualTo(operation.isEmpty()?"ERROR":"RULE_REVIEW");
        }
    }
    @ParameterizedTest @ValueSource(strings={"РЕАЛИЗАЦИЯ","ОПЛАТА ПОСТАВЩИКУ","ПЕРЕМЕЩ НАЛ ПО СЕТИ"})
    void nonProfitCategoriesAreRecognizedButIncompleteRulesNotCertified(String operation) {
        var item=rules.evaluate(new Row(2,"2",LocalDate.of(2026,8,1),9,true,true,BigDecimal.TEN,null,
                "SYNTHETIC_PARTNER","Synthetic partner","SYNTHETIC_ACCOUNT",operation,null,null));
        assertThat(item.category().recognition()).isEqualTo("RECOGNIZED");
        assertThat(item.category().profitTreatment()).isEqualTo("KNOWN_NON_OPERATING_CATEGORY");
        assertThat(item.status()).isEqualTo("RULE_REVIEW");
    }
    @Test void unknownDocumentIsRetainedWithCategoryAndDiagnostics() {
        var item=rules.evaluate(new Row(3,"1",LocalDate.of(2026,8,1),1,false,true,BigDecimal.ONE,null,
                "UNKNOWN","Synthetic unknown",null,"UNKNOWN",null,null));
        assertThat(item.category().code()).isEqualTo("UNCLASSIFIED");
        assertThat(item.status()).isEqualTo("RULE_REVIEW");
        assertThat(item.findings()).extracting(Finding::code).contains("DOCUMENT_CATEGORY_UNKNOWN");
    }
    @Test void nullAmountStaysNullAndEveryReferencedRuleExists() {
        Row row=salary(null,"bad");
        var item=rules.evaluate(new Row(4,"4",row.documentDate(),null,null,null,null,null,
                row.organizationCode(),row.organizationName(),null,null,null,row.note()));
        assertThat(item.document().amount()).isNull();
        assertThat(item.status()).isEqualTo("ERROR");
        assertThat(rules.catalog().stream().map(r -> r.id()).toList()).containsAll(item.ruleIds());
    }
}
