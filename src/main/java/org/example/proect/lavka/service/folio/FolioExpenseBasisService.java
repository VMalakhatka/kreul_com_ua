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
    private static final Pattern BASIS=Pattern.compile("^(\\d{1,10})([^\\d\\s][^\\s]{0,19})?\\s+(?:от|о|від)\\s+(\\d{2}\\.\\d{2}\\.\\d{4})$",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    private final FolioExpenseBasisDao dao;
    public FolioExpenseBasisService(FolioExpenseBasisDao dao) { this.dao=dao; }
    @Transactional(transactionManager="mssqlTransactionManager",readOnly=true)
    public Result resolve(String partner,long expenseId) {
        partner=partner==null ? "" : partner.trim();
        if (partner.isEmpty() || partner.length()>8 || expenseId<=0) throw new FolioAccountValidationException("invalid_expense_reference","Exact partner and positive expense ID required");
        List<String> rows=dao.expenseBasis(partner,expenseId);
        if (rows.size()!=1) throw new FolioCustomerDocumentNotFoundException("Expense not found for this partner");
        String basis=rows.get(0)==null ? "" : rows.get(0).trim();
        var match=BASIS.matcher(basis);
        if (!match.matches()) return new Result(true,partner,expenseId,basis,basis.isEmpty()?"NO_BASIS":"UNRECOGNIZED_BASIS",null);
        LocalDate date;
        try { date=LocalDate.parse(match.group(3),DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)); }
        catch (RuntimeException ex) { return new Result(true,partner,expenseId,basis,"UNRECOGNIZED_BASIS",null); }
        var candidates=new ArrayList<FolioExpenseBasisDao.Header>();
        String suffix=match.group(2)==null ? "" : match.group(2);
        BigDecimal number=new BigDecimal(match.group(1));
        candidates.addAll(dao.accounts(partner,number,suffix,date,false));
        candidates.addAll(dao.accounts(partner,number,suffix,date,true));
        return new Result(true,partner,expenseId,basis,candidates.isEmpty()?"NOT_FOUND":candidates.size()==1?"MATCHED":"AMBIGUOUS",
                candidates.size()==1?candidates.get(0):null);
    }
    public record Result(boolean ok,String partnerShortName,long expenseId,String basis,String status,FolioExpenseBasisDao.Header account) {}
}
