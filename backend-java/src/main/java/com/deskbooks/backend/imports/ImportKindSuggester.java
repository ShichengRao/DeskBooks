package com.deskbooks.backend.imports;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

final class ImportKindSuggester {
    private static final List<String> REFUND_MARKERS = List.of("REFUND", "RETURN", "REVERSAL");
    private static final List<String> DIRECT_INCOME_MARKERS = List.of("DIRECT DEP", "DIRECTDEP", "PAYROLL");
    private static final List<String> TAX_REFUND_MARKERS = List.of("TAX REFUND", "TREAS 310 TAX REF");
    private static final List<String> TAX_MARKERS = List.of("IRS", "USATAXPYMT");
    private static final List<String> TRANSFER_MARKERS = List.of("TRANSFER", "XFER", "EXT TRNSFR");

    private ImportKindSuggester() {
    }

    static String suggest(String description, BigDecimal amount, boolean creditCard, String extra) {
        String haystack = ((description == null ? "" : description) + " " + (extra == null ? "" : extra))
                .toUpperCase(Locale.ROOT);
        if (isPositive(amount) && containsAny(haystack, REFUND_MARKERS)) {
            return "refund";
        }
        return nonRefundKind(haystack, amount, creditCard);
    }

    private static String nonRefundKind(String haystack, BigDecimal amount, boolean creditCard) {
        if (isCreditCardPayment(haystack, creditCard)) {
            return "cc_payment";
        }
        if (isIncome(haystack, amount)) {
            return "income";
        }
        return categorizedKind(haystack);
    }

    private static boolean isCreditCardPayment(String haystack, boolean creditCard) {
        return haystack.contains("PAYMENT") && (creditCard || haystack.contains("CREDIT CARD") || haystack.contains("CRD"))
                || haystack.contains("AUTOPAY") && creditCard;
    }

    private static boolean isIncome(String haystack, BigDecimal amount) {
        return containsAny(haystack, DIRECT_INCOME_MARKERS)
                || isPositive(amount) && haystack.contains("INTEREST")
                || containsAny(haystack, TAX_REFUND_MARKERS);
    }

    private static String categorizedKind(String haystack) {
        if (containsAny(haystack, TAX_MARKERS)) {
            return "tax";
        }
        if (containsAny(haystack, TRANSFER_MARKERS)) {
            return "transfer";
        }
        return "uncategorized";
    }

    private static boolean isPositive(BigDecimal amount) {
        return amount.compareTo(BigDecimal.ZERO) > 0;
    }

    private static boolean containsAny(String haystack, List<String> markers) {
        for (String marker : markers) {
            if (haystack.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
