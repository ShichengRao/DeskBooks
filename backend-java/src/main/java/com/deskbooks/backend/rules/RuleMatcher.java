package com.deskbooks.backend.rules;

import java.math.BigDecimal;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class RuleMatcher {
    private final RuleDescriptionGeneralizer descriptions = new RuleDescriptionGeneralizer();

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
                || compiled.matcher(descriptions.generalize(description)).find()
                || compiled.matcher(descriptions.generalize(merchant)).find();
    }

    String proposalKey(String merchant, String descriptionNormalized, String descriptionRaw) {
        String description = descriptionNormalized == null ? descriptionRaw : descriptionNormalized;
        String value = merchant == null ? description : merchant;
        return descriptions.generalize(value == null ? "" : value);
    }

    String proposalPattern(String key) {
        return descriptions.patternFor(key);
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
}
