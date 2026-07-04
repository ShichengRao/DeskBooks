package com.deskbooks.backend.rules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class RuleMatcher {
    boolean matches(RuleRecord rule, long accountId, String description, BigDecimal amount) {
        return accountOk(rule.matchAccountId(), accountId)
                && descriptionMatches(rule.matchDescriptionPattern(), description)
                && amountMatches(rule, amount);
    }

    boolean accountOk(Long matchAccountId, long accountId) {
        return matchAccountId == null || matchAccountId == accountId;
    }

    boolean proposalMatches(String pattern, String descriptionNormalized, String descriptionRaw, String merchantRaw) {
        Pattern compiled = compile(pattern);
        if (compiled == null) {
            return false;
        }
        String description = descriptionNormalized == null ? descriptionRaw : descriptionNormalized;
        if (description == null) {
            description = "";
        }
        String merchant = merchantRaw == null ? "" : merchantRaw;
        return compiled.matcher(description).find()
                || compiled.matcher(merchant).find()
                || compiled.matcher(generalizeDescription(description)).find()
                || compiled.matcher(generalizeDescription(merchant)).find();
    }

    String proposalKey(String merchant, String descriptionNormalized, String descriptionRaw) {
        String description = descriptionNormalized == null ? descriptionRaw : descriptionNormalized;
        String value = merchant == null ? description : merchant;
        return generalizeDescription(value == null ? "" : value);
    }

    String proposalPattern(String key) {
        List<String> tokens = new ArrayList<>();
        for (String token : key.trim().split("\\s+")) {
            if (!token.isBlank()) {
                tokens.add(regexEscape(token));
            }
        }
        return String.join(".*", tokens);
    }

    private boolean descriptionMatches(String pattern, String description) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        Pattern compiled = compile(pattern);
        return compiled != null && compiled.matcher(description == null ? "" : description).find();
    }

    private boolean amountMatches(RuleRecord rule, BigDecimal amount) {
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        if (rule.matchAmountMin() != null && value.compareTo(new BigDecimal(rule.matchAmountMin())) < 0) {
            return false;
        }
        return rule.matchAmountMax() == null || value.compareTo(new BigDecimal(rule.matchAmountMax())) <= 0;
    }

    private Pattern compile(String pattern) {
        try {
            return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException exception) {
            return null;
        }
    }

    private String generalizeDescription(String value) {
        String s = value == null ? "" : value.trim();
        if (s.isBlank()) {
            return "";
        }
        s = s.replaceAll("(?i)\\bX+X*\\d{3,}\\b", "");
        s = s.replaceAll("\\b[Xx]{2,}\\d{3,}\\b", "");
        s = s.replaceAll("\\b\\d{10,}\\b", "");
        s = s.replaceAll("\\b\\d{6,8}\\b", "");
        s = s.replaceAll("\\b[A-Z][a-z]+\\s+[A-Z][a-z]+\\b", "");
        s = s.replaceAll("\\b[A-Z][a-z]+,?\\s*[A-Z][a-z]+\\b", "");
        s = s.replaceAll("(?i)^\\s*DD\\s+(?=DoorDash\\b)", "");
        s = s.replaceAll("(?i)^\\s*(Aplpay|Apple\\s+Pay)\\s+", "");
        s = s.replaceAll("(?i)\\s+New\\s+York\\s*$", "");
        s = s.replaceAll("[*#:;-]+", " ");
        s = s.replaceAll("\\s+", " ").trim();
        if (Pattern.compile("\\bNYCT\\b", Pattern.CASE_INSENSITIVE).matcher(s).find()
                && Pattern.compile("\\bPAYGO\\b", Pattern.CASE_INSENSITIVE).matcher(s).find()) {
            return "Nyct Paygo";
        }
        return s;
    }

    private String regexEscape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if ("\\.[]{}()*+-?^$|".indexOf(ch) >= 0) {
                out.append('\\');
            }
            out.append(ch);
        }
        return out.toString();
    }

}
