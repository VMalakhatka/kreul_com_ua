package org.example.proect.lavka.service.folio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FolioProductMovementClassifierTest {

    @Test
    void oneOffSaleAffectsFinancialsButNotPlanningDemand() {
        var result = FolioProductMovementClassifier.classify(
                "\u0420", "\u0420", "*\u0420\u0410\u0417\u041e\u0412\u0410\u042f", "\u0414", true, false);

        assertThat(result.movementClass()).isEqualTo("SALE");
        assertThat(result.stockDirection()).isEqualTo("OUT");
        assertThat(result.demandMode()).isEqualTo("ONE_OFF_ORDER");
        assertThat(result.customerSegment()).isEqualTo("NON_RETAIL");
        assertThat(result.affectsFinancialSales()).isTrue();
        assertThat(result.affectsPlanningDemand()).isFalse();
    }

    @Test
    void paymentTermsNeverReplaceSaleOrRegularDemandClassification() {
        var prepayment = FolioProductMovementClassifier.classify(
                "\u0420", "\u0420", "*\u041f\u0420\u0415\u0414\u041e\u041f\u041b\u0410\u0422\u0410", "\u041a", true, false);
        var deferred = FolioProductMovementClassifier.classify(
                "\u0420", "\u0420", "*60\u0414. \u041e\u0422\u0421\u0420\u041e\u0427\u041a\u0410", "\u0414", true, false);

        assertThat(prepayment.movementClass()).isEqualTo("SALE");
        assertThat(prepayment.demandMode()).isEqualTo("REGULAR");
        assertThat(prepayment.paymentTerms()).isEqualTo("PREPAYMENT");
        assertThat(prepayment.customerSegment()).isEqualTo("RETAIL");
        assertThat(deferred.movementClass()).isEqualTo("SALE");
        assertThat(deferred.demandMode()).isEqualTo("REGULAR");
        assertThat(deferred.paymentTerms()).isEqualTo("DEFERRED_60");
        assertThat(deferred.customerSegment()).isEqualTo("NON_RETAIL");
    }

    @Test
    void transferAndReservationDoNotBecomeSales() {
        var transfer = FolioProductMovementClassifier.classify(
                "\u0420", "\u0420", "*\u041f\u0415\u0420\u0415\u041c\u0415\u0429\u0415\u041d\u0418\u0415", "\u042f", true, false);
        var reservation = FolioProductMovementClassifier.classify(
                "\u0421", "\u0421", "*\u041f\u0420\u0415\u0414\u041e\u041f\u041b\u0410\u0422\u0410", "\u041a", true, false);

        assertThat(transfer.movementClass()).isEqualTo("TRANSFER_OUT");
        assertThat(transfer.affectsFinancialSales()).isFalse();
        assertThat(reservation.movementClass()).isEqualTo("RESERVATION");
        assertThat(reservation.stockDirection()).isEqualTo("NONE");
        assertThat(reservation.affectsStock()).isFalse();
        assertThat(reservation.paymentTerms()).isEqualTo("PREPAYMENT");
    }

    @Test
    void supplierReceiptUsesPartnerTypeWithoutInventingDemand() {
        var result = FolioProductMovementClassifier.classify(
                "\u041f", "\u041f", "*\u0420\u0410\u0417\u041e\u0412\u0410\u042f", "\u0422", true, false);

        assertThat(result.movementClass()).isEqualTo("PURCHASE_RECEIPT");
        assertThat(result.demandMode()).isEqualTo("ONE_OFF_ORDER");
        assertThat(result.customerSegment()).isEqualTo("NOT_APPLICABLE");
        assertThat(result.affectsPlanningDemand()).isFalse();
    }
}
