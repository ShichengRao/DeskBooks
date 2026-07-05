package com.deskbooks.backend.rules;

import java.util.List;

final class RuleProposalBuilder {
    private static final double MINIMUM_PROPOSAL_ACCURACY = 0.75;

    private final RuleMatcher matcher;
    private final RuleEngine rules;
    private final RuleProposalRegistry proposals;
    private final RuleProposalSummaries summaries;

    RuleProposalBuilder(
            RuleMatcher matcher,
            RuleEngine rules,
            RuleProposalRegistry proposals,
            RuleProposalSummaries summaries) {
        this.matcher = matcher;
        this.rules = rules;
        this.proposals = proposals;
        this.summaries = summaries;
    }

    RuleProposal build(String key, List<RuleTransactionRow> txs, RuleProposalContext context) {
        RuleProposalOutcome outcome = summaries.majorityOutcome(txs, context.minSupport());
        if (outcome == null) {
            return null;
        }
        String pattern = matcher.proposalPattern(key);
        if (!candidateIsAvailable(context, key, pattern, outcome.categoryId(), outcome.kind())) {
            return null;
        }
        List<RuleTransactionRow> matches = matchingLabeledTransactions(pattern, context);
        if (matches.isEmpty()) {
            return null;
        }
        MatchCounts allAndAdded = allAndAddedMatches(pattern, context);
        List<RuleTransactionRow> correct = summaries.correctMatches(matches, outcome.categoryId(), outcome.kind());
        List<RuleTransactionRow> incorrect = summaries.incorrectMatches(matches, outcome.categoryId(), outcome.kind());
        double accuracy = ((double) correct.size()) / matches.size();
        if (accuracy < MINIMUM_PROPOSAL_ACCURACY) {
            return null;
        }
        return new RuleProposal(
                key,
                key,
                pattern,
                null,
                outcome.categoryId(),
                outcome.kind(),
                setMerchant(key),
                outcome.support(),
                matches.size(),
                allAndAdded.allMatches(),
                allAndAdded.addedMatches(),
                correct.size(),
                incorrect.size(),
                accuracy,
                percentage(matches.size(), context.totalLabeled()),
                percentage(allAndAdded.allMatches(), context.totalTransactions()),
                percentage(allAndAdded.addedMatches(), context.totalTransactions()),
                summaries.breakdown(matches),
                summaries.examples(correct, incorrect, outcome.categoryId(), outcome.kind()));
    }

    private List<RuleTransactionRow> matchingLabeledTransactions(String pattern, RuleProposalContext context) {
        return context.labeledTxs().stream()
                .filter(tx -> matcher.proposalMatches(
                        pattern, tx.descriptionNormalized(), tx.descriptionRaw(), tx.merchant()))
                .toList();
    }

    private boolean candidateIsAvailable(
            RuleProposalContext context,
            String key,
            String pattern,
            Long categoryId,
            String kind) {
        return proposals.candidateIsAvailable(
                context.activeSignatures(), context.rejectedSignatures(), key, pattern, categoryId, kind);
    }

    private MatchCounts allAndAddedMatches(String pattern, RuleProposalContext context) {
        int allMatches = 0;
        int addedMatches = 0;
        for (RuleTransactionRow tx : context.allTxs()) {
            if (!matcher.proposalMatches(pattern, tx.descriptionNormalized(), tx.descriptionRaw(), tx.merchant())) {
                continue;
            }
            allMatches++;
            RuleEval eval = rules.evaluate(context.activeRules(), tx.accountId(), tx.description(), tx.amount());
            if (!eval.matched()) {
                addedMatches++;
            }
        }
        return new MatchCounts(allMatches, addedMatches);
    }

    private String setMerchant(String key) {
        return key.length() > 255 ? key.substring(0, 255) : key;
    }

    private double percentage(int count, int total) {
        return total == 0 ? 0.0 : ((double) count) / total * 100.0;
    }

    private record MatchCounts(int allMatches, int addedMatches) {
    }
}
