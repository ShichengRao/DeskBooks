package com.deskbooks.backend.imports;

import java.util.Locale;

final class ImportTextParsing {
    private ImportTextParsing() {
    }

    static String normalize(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
    }

    static String guessMerchant(String raw) {
        String value = normalize(raw)
                .replaceFirst("(?i)^(DD \\*|TST\\*|SQ \\*|SP \\*|PY \\*|PAYPAL \\*|VENMO \\*)", "")
                .replaceFirst("\\s+[A-Z]{2}\\s*$", "")
                .replaceFirst("\\s+\\d{6,}\\s*$", "")
                .replaceAll("\\s+#\\d+", "")
                .trim();
        if (value.isEmpty()) {
            return value;
        }
        return titleCase(value);
    }

    private static String titleCase(String value) {
        StringBuilder title = new StringBuilder();
        for (String part : value.toLowerCase(Locale.ROOT).split(" ")) {
            if (!part.isEmpty()) {
                title.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(" ");
            }
        }
        return title.toString().trim();
    }
}
