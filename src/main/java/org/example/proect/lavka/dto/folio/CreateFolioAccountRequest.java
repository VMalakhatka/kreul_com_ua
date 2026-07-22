package org.example.proect.lavka.dto.folio;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record CreateFolioAccountRequest(
        @NotBlank String externalRequestId,
        @NotBlank String documentNumber,
        @NotNull LocalDateTime documentDate,
        @NotNull Integer warehouseId,
        @NotBlank String operationType,
        Integer partnerId,
        @Size(max = 5) String comment,
        @NotNull LocalDate controlDate,
        @NotBlank @Size(max = 20) String folioOperationKind,
        @NotBlank @Size(max = 50) String payerName,
        @NotBlank @Size(max = 50) String receiverName,
        @NotBlank @Size(max = 8) String payerShortName,
        @NotBlank @Size(max = 20) String folioUser,
        @NotBlank @Size(max = 30) String sourceInfo,
        @NotBlank @Size(max = 30) String additionalInfo,
        @Size(max = 10) String priceContractType,
        @NotNull Boolean notCash,
        @NotNull Boolean accountingEnabled,
        @NotNull Boolean returnFlag,
        BigDecimal currencyAmount,
        BigDecimal retailAmount,
        @Size(max = 28) String payerCity,
        @Size(max = 75) String directorName,
        @Size(max = 75) String accountantName,
        @Size(max = 20) String payerPhone,
        @Size(max = 150) String deliveryInfo,
        @NotEmpty List<@Valid CreateFolioAccountItemRequest> items
) {
}
