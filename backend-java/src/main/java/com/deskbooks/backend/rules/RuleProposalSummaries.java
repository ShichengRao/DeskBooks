package com.deskbooks.backend.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class RuleProposalSummaries {
    RuleProposalOutcome majorityOutcome(List<RuleTransactionRow> txs, int minSupport) {
        if (txs.size() < minSupport) {
            return null;
        }
        Map<OutcomeKey, Integer> counts = new HashMap<>();
        for (RuleTransactionRow tx : txs) {
            counts.merge(new OutcomeKey(tx.categoryId(), tx.kind()), 1, Integer::sum);
        }
        Map.Entry<OutcomeKey, Integer> winner = counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .orElse(null);
        if (winner == null || winner.getValue() < minSupport) {
            return null;
        }
        return new RuleProposalOutcome(winner.getKey().categoryId(), winner.getKey().kind(), winner.getValue());
    }

    List<RuleTransactionRow> correctMatches(List<RuleTransactionRow> matches, Long categoryId, String kind) {
        return matches.stream()
                .filter(tx -> Objects.equals(tx.categoryId(), categoryId) && kind.equals(tx.kind()))
                .toList();
    }

    List<RuleTransactionRow> incorrectMatches(List<RuleTransactionRow> matches, Long categoryId, String kind) {
        return matches.stream()
                .filter(tx -> !Objects.equals(tx.categoryId(), categoryId) || !kind.equals(tx.kind()))
                .toList();
    }

    List<RuleEngine.RuleProposalBreakdown> breakdown(List<RuleTransactionRow> matches) {
        Map<OutcomeKey, Integer> counts = new HashMap<>();
        for (RuleTransactionRow tx : matches) {
            counts.merge(new OutcomeKey(tx.categoryId(), tx.kind()), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<OutcomeKey, Integer>comparingByValue().reversed())
                .map(entry -> new RuleEngine.RuleProposalBreakdown(entry.getKey().categoryId(), entry.getKey().kind(), entry.getValue()))
                .toList();
    }

    List<RuleEngine.RuleProposalExample> examples(
            List<RuleTransactionRow> correct,
            List<RuleTransactionRow> incorrect,
            Long categoryId,
            String kind) {
        List<RuleTransactionRow> candidates = new ArrayList<>();
        candidates.addAll(incorrect.stream().limit(3).toList());
        candidates.addAll(correct.stream().limit(3).toList());
        return candidates.stream()
                .limit(6)
                .map(tx -> new RuleEngine.RuleProposalExample(
                        tx.id(),
                        tx.date(),
                        tx.description(),
                        moneyString(tx.amount()),
                        tx.categoryId(),
                        tx.kind(),
                        Objects.equals(tx.categoryId(), categoryId) && kind.equals(tx.kind())))
                .toList();
    }

    private String moneyString(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private record OutcomeKey(Long categoryId, String kind) {
    }
}

record RuleProposalOutcome(Long categoryId, String kind, int support) {
}
