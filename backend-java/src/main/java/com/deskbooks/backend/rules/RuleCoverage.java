package com.deskbooks.backend.rules;

public record RuleCoverage(
        int activeRuleCount,
        int totalTransactions,
        int matchedTransactions,
        double coveragePercent,
        int labeledTransactions,
        int labeledMatchedTransactions,
        int labeledCorrectMatches,
        int labeledIncorrectMatches,
        Double labeledAccuracy) {
}
