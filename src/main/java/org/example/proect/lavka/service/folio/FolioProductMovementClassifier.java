package org.example.proect.lavka.service.folio;

import java.util.Locale;
import java.util.Set;

/**
 * Keeps physical direction, commercial meaning, demand mode and payment terms
 * independent. VID_DOC is an overloaded Folio business tag and must not be
 * treated as a technical movement direction.
 */
public final class FolioProductMovementClassifier {

    private static final String RECEIPT = "\u041f";
    private static final String EXPENSE = "\u0420";
    private static final String ACCOUNT = "\u0421";
    private static final String ASSEMBLY = "\u0411";
    private static final String OWN_ORGANIZATION = "\u042f";
    private static final Set<String> RETAIL_ORGANIZATION_TYPES = Set.of("\u041a");
    private static final Set<String> NON_RETAIL_ORGANIZATION_TYPES = Set.of("\u041f", "\u0414");
    private static final Set<String> SUPPLIER_ORGANIZATION_TYPES = Set.of("\u0422", "I");
    private static final Set<String> ONE_OFF_MOVEMENT_CLASSES = Set.of(
            "SALE", "PURCHASE_RECEIPT", "OTHER_RECEIPT");
    private static final Set<String> CUSTOMER_MOVEMENT_CLASSES = Set.of(
            "SALE", "CUSTOMER_RETURN");

    private FolioProductMovementClassifier() {
    }

    public static Classification classify(String movementType,
                                          String documentType,
                                          String operationKind,
                                          String organizationType,
                                          boolean accounted,
                                          boolean returnFlag) {
        String movement = normalize(movementType);
        String document = normalize(documentType);
        String operation = normalize(operationKind);
        String organization = normalize(organizationType);

        String direction = direction(movement, document);
        String movementClass = movementClass(
                movement, document, operation, organization, returnFlag);
        String demandMode = demandMode(movementClass, operation);
        String paymentTerms = paymentTerms(operation);
        String customerSegment = customerSegment(
                movementClass, operation, organization);
        boolean affectsStock = accounted && !"NONE".equals(direction)
                && !"RESERVATION".equals(movementClass);
        boolean affectsFinancialSales = accounted && "SALE".equals(movementClass);
        boolean affectsPlanningDemand = affectsFinancialSales
                && "REGULAR".equals(demandMode);

        return new Classification(
                movementClass,
                direction,
                demandMode,
                paymentTerms,
                customerSegment,
                affectsStock,
                affectsFinancialSales,
                affectsPlanningDemand
        );
    }

    private static String direction(String movement, String document) {
        if (ACCOUNT.equals(movement) || ACCOUNT.equals(document)) return "NONE";
        if (RECEIPT.equals(movement)) return "IN";
        if (EXPENSE.equals(movement)) return "OUT";
        return "NONE";
    }

    private static String movementClass(String movement,
                                        String document,
                                        String operation,
                                        String organization,
                                        boolean returnFlag) {
        if (ACCOUNT.equals(movement) || ACCOUNT.equals(document)) return "RESERVATION";
        if (ASSEMBLY.equals(document) || containsAny(operation, "\u0421\u0411\u041e\u0420\u041a", "\u041c\u0423\u041b\u042c\u0422\u0418\u0421\u0411\u041e\u0420")) {
            if (RECEIPT.equals(movement)) return "ASSEMBLY_OUTPUT";
            if (EXPENSE.equals(movement)) return "ASSEMBLY_INPUT";
            return "UNCLASSIFIED";
        }
        if (operation.contains("\u041f\u0415\u0420\u0415\u041c\u0415\u0429")) {
            if (RECEIPT.equals(movement)) return "TRANSFER_IN";
            if (EXPENSE.equals(movement)) return "TRANSFER_OUT";
            return "UNCLASSIFIED";
        }
        if (operation.contains("\u041a\u041e\u0420\u0420\u0415\u041a\u0422")) {
            if (RECEIPT.equals(movement)) return "INVENTORY_CORRECTION_IN";
            if (EXPENSE.equals(movement)) return "INVENTORY_CORRECTION_OUT";
            return "UNCLASSIFIED";
        }
        if (operation.contains("\u0411\u0420\u0410\u041a")) {
            if (RECEIPT.equals(movement)) return "DEFECT_IN";
            if (EXPENSE.equals(movement)) return "DEFECT_OUT";
            return "UNCLASSIFIED";
        }
        if (operation.contains("\u0420\u0410\u0421\u0425\u041e\u0414\u041d\u0418\u041a")) {
            if (RECEIPT.equals(movement)) return "INTERNAL_USE_IN";
            if (EXPENSE.equals(movement)) return "INTERNAL_USE_OUT";
            return "UNCLASSIFIED";
        }
        if (operation.contains("\u0420\u0415\u041a\u041b\u0410\u041c")) {
            if (RECEIPT.equals(movement)) return "MARKETING_IN";
            if (EXPENSE.equals(movement)) return "MARKETING_OUT";
            return "UNCLASSIFIED";
        }
        if (returnFlag || operation.contains("\u0412\u041e\u0417\u0412\u0420\u0410\u0422")) {
            if (RECEIPT.equals(movement)) return "CUSTOMER_RETURN";
            if (EXPENSE.equals(movement)) return "SUPPLIER_RETURN";
            return "UNCLASSIFIED";
        }
        if (RECEIPT.equals(movement)) {
            if (SUPPLIER_ORGANIZATION_TYPES.contains(organization)) return "PURCHASE_RECEIPT";
            if (OWN_ORGANIZATION.equals(organization)) return "INTERNAL_RECEIPT";
            return "OTHER_RECEIPT";
        }
        if (EXPENSE.equals(movement)) {
            if (OWN_ORGANIZATION.equals(organization)) return "INTERNAL_EXPENSE";
            if (isExternalCustomer(organization) || isCommercialOperation(operation)) return "SALE";
            return "OTHER_EXPENSE";
        }
        return "UNCLASSIFIED";
    }

    private static String demandMode(String movementClass, String operation) {
        boolean oneOff = operation.contains("\u0420\u0410\u0417\u041e\u0412");
        if (oneOff && ONE_OFF_MOVEMENT_CLASSES.contains(movementClass)) {
            return "ONE_OFF_ORDER";
        }
        if ("SALE".equals(movementClass)) return "REGULAR";
        return "NOT_APPLICABLE";
    }

    private static String paymentTerms(String operation) {
        if (operation.contains("\u041f\u0420\u0415\u0414\u041e\u041f\u041b\u0410\u0422")) return "PREPAYMENT";
        if (operation.contains("180\u0414")) return "DEFERRED_180";
        if (operation.contains("90\u0414")) return "DEFERRED_90";
        if (operation.contains("60\u0414")) return "DEFERRED_60";
        if (operation.contains("30\u0414")) return "DEFERRED_30";
        if (operation.contains("\u041f\u041e \u0424\u0410\u041a\u0422\u0423")) return "ON_FACT";
        return "NOT_SPECIFIED";
    }

    private static String customerSegment(String movementClass,
                                          String operation,
                                          String organization) {
        if (!CUSTOMER_MOVEMENT_CLASSES.contains(movementClass)) {
            return "NOT_APPLICABLE";
        }
        if (operation.contains("\u0420\u041e\u0417\u041d\u0418\u0426")
                || RETAIL_ORGANIZATION_TYPES.contains(organization)) {
            return "RETAIL";
        }
        if (NON_RETAIL_ORGANIZATION_TYPES.contains(organization)) return "NON_RETAIL";
        return "UNKNOWN";
    }

    private static boolean isExternalCustomer(String organization) {
        return RETAIL_ORGANIZATION_TYPES.contains(organization)
                || NON_RETAIL_ORGANIZATION_TYPES.contains(organization);
    }

    private static boolean isCommercialOperation(String operation) {
        return containsAny(operation,
                "\u0420\u041e\u0417\u041d\u0418\u0426",
                "\u0420\u0410\u0417\u041e\u0412",
                "\u041f\u0420\u0415\u0414\u041e\u041f\u041b\u0410\u0422",
                "\u041e\u0422\u0421\u0420\u041e\u0427\u041a",
                "\u0420\u0415\u0410\u041b\u0418\u0417\u0410\u0426");
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public record Classification(
            String movementClass,
            String stockDirection,
            String demandMode,
            String paymentTerms,
            String customerSegment,
            boolean affectsStock,
            boolean affectsFinancialSales,
            boolean affectsPlanningDemand) {
    }
}
