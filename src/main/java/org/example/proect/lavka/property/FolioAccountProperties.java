package org.example.proect.lavka.property;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

@ConfigurationProperties(prefix = "lavka.folio.accounts")
public class FolioAccountProperties {

    private List<String> allowedOperationTypes = List.of("СЧЕТ");
    private String documentType = "СЧЕТ";
    private String typeDoc = "С";
    private String movementVidDoc = "*РАЗОВАЯ";
    private String taxName = "НДС";
    private BigDecimal taxPercent = new BigDecimal("20");
    private boolean valutaRouble = true;
    private Integer currencyCode = 4;
    private BigDecimal secondTaxPercent = BigDecimal.ZERO;
    private int paymentFlag = 0;
    private int partialPaymentFlag = 0;
    private int markFlag = 0;
    private Integer cashProductType = 138;
    private int tradeVatFlag = 0;

    public List<String> getAllowedOperationTypes() {
        return allowedOperationTypes;
    }

    public void setAllowedOperationTypes(List<String> allowedOperationTypes) {
        this.allowedOperationTypes = allowedOperationTypes;
    }

    public String getDocumentType() {
        return documentType;
    }

    public void setDocumentType(String documentType) {
        this.documentType = documentType;
    }

    public String getTypeDoc() {
        return typeDoc;
    }

    public void setTypeDoc(String typeDoc) {
        this.typeDoc = typeDoc;
    }

    public String getMovementVidDoc() {
        return movementVidDoc;
    }

    public void setMovementVidDoc(String movementVidDoc) {
        this.movementVidDoc = movementVidDoc;
    }

    public String getTaxName() {
        return taxName;
    }

    public void setTaxName(String taxName) {
        this.taxName = taxName;
    }

    public BigDecimal getTaxPercent() {
        return taxPercent;
    }

    public void setTaxPercent(BigDecimal taxPercent) {
        this.taxPercent = taxPercent;
    }

    public boolean isValutaRouble() {
        return valutaRouble;
    }

    public void setValutaRouble(boolean valutaRouble) {
        this.valutaRouble = valutaRouble;
    }

    public Integer getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(Integer currencyCode) {
        this.currencyCode = currencyCode;
    }

    public BigDecimal getSecondTaxPercent() {
        return secondTaxPercent;
    }

    public void setSecondTaxPercent(BigDecimal secondTaxPercent) {
        this.secondTaxPercent = secondTaxPercent;
    }

    public int getPaymentFlag() {
        return paymentFlag;
    }

    public void setPaymentFlag(int paymentFlag) {
        this.paymentFlag = paymentFlag;
    }

    public int getPartialPaymentFlag() {
        return partialPaymentFlag;
    }

    public void setPartialPaymentFlag(int partialPaymentFlag) {
        this.partialPaymentFlag = partialPaymentFlag;
    }

    public int getMarkFlag() {
        return markFlag;
    }

    public void setMarkFlag(int markFlag) {
        this.markFlag = markFlag;
    }

    public Integer getCashProductType() {
        return cashProductType;
    }

    public void setCashProductType(Integer cashProductType) {
        this.cashProductType = cashProductType;
    }

    public int getTradeVatFlag() {
        return tradeVatFlag;
    }

    public void setTradeVatFlag(int tradeVatFlag) {
        this.tradeVatFlag = tradeVatFlag;
    }
}
