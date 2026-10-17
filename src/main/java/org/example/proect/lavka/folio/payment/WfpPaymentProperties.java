package org.example.proect.lavka.folio.payment;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** No production defaults for identity, credentials or write permission. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "folio.wfp-payment")
public class WfpPaymentProperties {
    private boolean apiEnabled;
    private boolean applyEnabled;
    private String token = "";
    private String expectedDatabase = "";
    private String sourceScope = "";
    private String merchantHash = "";
    private String startAt = "";
    private long bankTemplateId;
}
