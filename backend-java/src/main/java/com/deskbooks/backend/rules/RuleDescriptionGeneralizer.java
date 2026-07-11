package com.deskbooks.backend.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class RuleDescriptionGeneralizer {
    private static final Pattern NYCT = Pattern.compile("\\bNYCT\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAYGO = Pattern.compile("\\bPAYGO\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LONG_REFERENCE = Pattern.compile(
            "\\b(?:X+\\d{3,}|[Xx]{2,}\\d{3,}|\\d{10,})\\b");
    private static final Pattern TRAILING_PERSON_NAME = Pattern.compile(
            "\\s+\\b[A-Z][a-z]+,?\\s+[A-Z][a-z]+\\b\\s*$");
    private static final String REGEX_META_CHARS = "\\.[]{}()*+-?^$|";

    private final RuleProposalKeys proposalKeys = new RuleProposalKeys();

    String generalize(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            return "";
        }
        boolean hadLongReference = LONG_REFERENCE.matcher(normalized).find();
        normalized = withoutVaryingIdentifiers(normalized);
        TrailingNameResult trailingName = stripTrailingPersonName(normalized);
        String processorKey = proposalKeys.processorStyleKey(
                trailingName.value(), hadLongReference, trailingName.stripped());
        if (processorKey != null) {
            return processorKey;
        }
        normalized = withoutPersonNames(trailingName.value());
        normalized = normalized.replaceAll("(?i)^\\s*DD\\s+(?=DoorDash\\b)", "");
        normalized = normalized.replaceAll("(?i)^\\s*(Aplpay|Apple\\s+Pay)\\s+", "");
        normalized = normalized.replaceAll("(?i)\\s+New\\s+York\\s*$", "");
        normalized = normalized.replaceAll("[*#:;-]+", " ");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        if (NYCT.matcher(normalized).find() && PAYGO.matcher(normalized).find()) {
            return "Nyct Paygo";
        }
        return normalized;
    }

    String patternFor(String key) {
        List<String> tokens = new ArrayList<>();
        for (String token : key.trim().split("\\s+")) {
            if (!token.isBlank()) {
                tokens.add(regexEscape(token));
            }
        }
        return String.join(".*", tokens);
    }

    private String withoutVaryingIdentifiers(String value) {
        String normalized = value;
        normalized = normalized.replaceAll("(?i)\\bX+X*\\d{3,}\\b", "");
        normalized = normalized.replaceAll("\\b[Xx]{2,}\\d{3,}\\b", "");
        normalized = normalized.replaceAll("\\b\\d{10,}\\b", "");
        return normalized.replaceAll("\\b\\d{6,8}\\b", "");
    }

    private TrailingNameResult stripTrailingPersonName(String value) {
        String cleaned = TRAILING_PERSON_NAME.matcher(value).replaceFirst("").trim();
        return new TrailingNameResult(cleaned, !cleaned.equals(value.trim()));
    }

    private String withoutPersonNames(String value) {
        String normalized = value;
        normalized = normalized.replaceAll("\\b[A-Z][a-z]+\\s+[A-Z][a-z]+\\b", "");
        return normalized.replaceAll("\\b[A-Z][a-z]+,?\\s*[A-Z][a-z]+\\b", "");
    }

    private String regexEscape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (REGEX_META_CHARS.indexOf(ch) >= 0) {
                out.append('\\');
            }
            out.append(ch);
        }
        return out.toString();
    }

    private record TrailingNameResult(String value, boolean stripped) {
    }
}
