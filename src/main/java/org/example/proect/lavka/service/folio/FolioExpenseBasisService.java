package org.example.proect.lavka.service.folio;

import org.example.proect.lavka.dao.folio.FolioExpenseBasisDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class FolioExpenseBasisService {
    // Full match only. Free-text notes such as РЕЛ must never produce guessed links.
    private static final Pattern BASIS=Pattern.compile("^(\\d{1,10})([^\\d\\s][^\\s]{0,19})?\\s+(?:от|о|o|від)\\s+(\\d{2}\\.\\d{2}\\.\\d{4})$",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    private final FolioExpenseBasisDao dao;
    public FolioExpenseBasisService(FolioExpenseBasisDao dao) { this.dao=dao; }
    @Transactional(transactionManager="mssqlTransactionManager",readOnly=true)
    public Result resolve(String partner,long expenseId) {
        partner=partner==null ? "" : partner.trim();
        if (partner.isEmpty() || partner.length()>8 || expenseId<=0) throw new FolioAccountValidationException("invalid_expense_reference","Exact partner and positive expense ID required");
        var rows=dao.expenseBasis(partner,expenseId);
        if (rows.size()!=1) throw new FolioCustomerDocumentNotFoundException("Expense not found for this partner");
        var ref=rows.get(0); String basis=trim(ref.basis()), number=trim(ref.accountNumber());
        Key textKey=parseBasis(basis), key=null;
        String source="BASIS_TEXT";
        if (!number.isEmpty() || ref.accountDate()!=null) {
            source="HEADER_FIELDS";
            if (number.isEmpty() || ref.accountDate()==null) return result(partner,expenseId,ref,"INCOMPLETE_REFERENCE",source,null);
            key=parseNumber(number,ref.accountDate());
            if (key==null) return result(partner,expenseId,ref,"UNRECOGNIZED_REFERENCE",source,null);
            if (textKey!=null && !key.equals(textKey)) return result(partner,expenseId,ref,"CONFLICTING_REFERENCE",source,null);
        } else key=textKey;
        if (key==null) return result(partner,expenseId,ref,basis.isEmpty()?"NO_BASIS":"UNRECOGNIZED_BASIS",source,null);
        var candidates=new ArrayList<FolioExpenseBasisDao.Header>();
        candidates.addAll(dao.accounts(partner,key.number(),key.suffix(),key.date(),false));
        candidates.addAll(dao.accounts(partner,key.number(),key.suffix(),key.date(),true));
        return result(partner,expenseId,ref,candidates.isEmpty()?"NOT_FOUND":candidates.size()==1?"MATCHED":"AMBIGUOUS",source,
                candidates.size()==1?candidates.get(0):null);
    }
    private static String trim(String value) { return value==null?"":value.trim(); }
    private static Key parseNumber(String number,LocalDate date) {
        var match=Pattern.compile("^(\\d{1,10})([^\\d\\s][^\\s]{0,19})?$").matcher(number);
        return match.matches()?new Key(new BigDecimal(match.group(1)),match.group(2)==null?"":match.group(2),date):null;
    }
    private static Key parseBasis(String basis) {
        var match=BASIS.matcher(basis);
        if (!match.matches()) return null;
        try {
            LocalDate date=LocalDate.parse(match.group(3),DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT));
            return new Key(new BigDecimal(match.group(1)),match.group(2)==null?"":match.group(2),date);
        } catch (RuntimeException ex) { return null; }
    }
    private static Result result(String partner,long id,FolioExpenseBasisDao.ExpenseReference ref,String status,String source,FolioExpenseBasisDao.Header account) {
        return new Result(true,partner,id,trim(ref.basis()),status,account,source,trim(ref.accountNumber()),ref.accountDate());
    }
    private record Key(BigDecimal number,String suffix,LocalDate date) {}

    public record Result(boolean ok,String partnerShortName,long expenseId,String basis,String status,FolioExpenseBasisDao.Header account,String referenceSource,String accountNumber,LocalDate accountDate) {}
}
