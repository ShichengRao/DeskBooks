package com.deskbooks.backend.rules;

import java.util.Set;
import java.util.regex.Pattern;

final class RuleProposalKeys {
    private static final Set<String> GENERIC_SINGLE_TOKEN_KEYS = Set.of(
            "ach",
            "authorization",
            "authorized",
            "autopay",
            "bill",
            "card",
            "check",
            "credit",
            "debit",
            "deposit",
            "fee",
            "fees",
            "mobile",
            "online",
            "payment",
            "pos",
            "purchase",
            "recurring",
            "transaction",
            "transfer",
            "withdrawal");
    private static final Pattern LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern SPECIFIC_PUNCTUATION = Pattern.compile("[./*@]");
    private static final Pattern PROCESSOR_TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9&'.-]{3,}");

    boolean isSpecificEnough(String key) {
        String[] tokens = key.trim().split("\\s+");
        if (tokens.length >= 2) {
            return true;
        }
        if (tokens.length != 1) {
            return false;
        }
        String token = tokens[0];
        if (token.length() < 4 || !LETTER.matcher(token).find()) {
            return false;
        }
        if (SPECIFIC_PUNCTUATION.matcher(token).find()) {
            return true;
        }
        return !GENERIC_SINGLE_TOKEN_KEYS.contains(token.toLowerCase(java.util.Locale.ROOT));
    }

    String processorStyleKey(String value, boolean hadLongReference, boolean strippedTrailingName) {
        if (!hadLongReference || !strippedTrailingName) {
            return null;
        }
        String[] tokens = value.trim().split("\\s+");
        if (tokens.length < 3) {
            return null;
        }
        String first = tokens[0];
        if (GENERIC_SINGLE_TOKEN_KEYS.contains(first.toLowerCase(java.util.Locale.ROOT))) {
            return null;
        }
        return PROCESSOR_TOKEN.matcher(first).matches() ? first : null;
    }
}
