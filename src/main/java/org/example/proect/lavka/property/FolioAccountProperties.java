package org.example.proect.lavka.property;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "lavka.folio.accounts")
public class FolioAccountProperties {

    private List<String> allowedOperationTypes = List.of("СЧЕТ");
    private String documentType = "СЧЕТ";

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
}
