package org.example.proect.lavka.property;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

@ConfigurationProperties(prefix = "lavka.folio.profit-report")
public class FolioProfitReportProperties {

    private boolean enabled = true;
    private List<Integer> kyivWarehouseIds = List.of(1, 7, 12);
    private int odesaWarehouseId = 5;
    private BigDecimal defaultOdesaTaxShare = new BigDecimal("0.4285714286");
    private BigDecimal defaultRubToUahRate = new BigDecimal("0.41");
    private int maxAuditDocuments = 500;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<Integer> getKyivWarehouseIds() {
        return kyivWarehouseIds;
    }

    public void setKyivWarehouseIds(List<Integer> kyivWarehouseIds) {
        this.kyivWarehouseIds = kyivWarehouseIds;
    }

    public int getOdesaWarehouseId() {
        return odesaWarehouseId;
    }

    public void setOdesaWarehouseId(int odesaWarehouseId) {
        this.odesaWarehouseId = odesaWarehouseId;
    }

    public BigDecimal getDefaultOdesaTaxShare() {
        return defaultOdesaTaxShare;
    }

    public void setDefaultOdesaTaxShare(BigDecimal defaultOdesaTaxShare) {
        this.defaultOdesaTaxShare = defaultOdesaTaxShare;
    }

    public BigDecimal getDefaultRubToUahRate() {
        return defaultRubToUahRate;
    }

    public void setDefaultRubToUahRate(BigDecimal defaultRubToUahRate) {
        this.defaultRubToUahRate = defaultRubToUahRate;
    }

    public int getMaxAuditDocuments() {
        return maxAuditDocuments;
    }

    public void setMaxAuditDocuments(int maxAuditDocuments) {
        this.maxAuditDocuments = maxAuditDocuments;
    }
}
