package org.example.proect.lavka.service.folio;
import org.example.proect.lavka.dao.folio.FolioDocumentValuesDao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
@Service
public class FolioDocumentValuesService {
    private final FolioDocumentValuesDao dao;
    public FolioDocumentValuesService(FolioDocumentValuesDao dao) { this.dao=dao; }
    @Transactional(transactionManager="mssqlTransactionManager",readOnly=true)
    public Result read(String partner,String type,long id) {
        partner=partner==null?"":partner.trim();
        String folioType=type==null?null:Map.of("ACCOUNT","С","EXPENSE","Р","RECEIPT","П").get(type);
        if (partner.isEmpty() || partner.length()>8 || id<=0 || folioType==null)
            throw new FolioAccountValidationException("invalid_document_values_reference","Exact document type, ID and partner required");
        var rows=dao.read(partner,type,folioType,id);
        if(rows.size()!=1) throw new FolioCustomerDocumentNotFoundException("Document values not found for this partner");
        return new Result(true,rows.get(0));
    }
    public record Result(boolean ok,FolioDocumentValuesDao.Values values) {}
}
