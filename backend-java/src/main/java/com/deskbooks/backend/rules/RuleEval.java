package com.deskbooks.backend.rules;

import java.util.List;

public record RuleEval(Long categoryId, String kind, String merchant, List<String> tags, Long matchedRuleId) {
    static RuleEval empty() {
        return new RuleEval(null, null, null, null, null);
    }

    public boolean matched() {
        return matchedRuleId != null;
    }
}
