package com.deskbooks.backend.rules;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

final class RuleProposalBacktester {
    private final RuleMatcher matcher;
    private final RuleEngine rules;
    private final RuleTransactionReader transactions;
    private final RuleProposalSummaries summaries;

    RuleProposalBacktester(
            RuleMatcher matcher,
            RuleEngine rules,
            RuleTransactionReader transactions,
            RuleProposalSummaries summaries) {
        this.matcher = matcher;
        this.rules = rules;
        this.transactions = transactions;
        this.summaries = summaries;
    }

    RuleProposal backtest(Connection connection, RuleProposalRequest request) throws SQLException {
        List<RuleTransactionRow> labeledTxs = transactions.load(connection, true);
        List<RuleTransactionRow> allTxs = transactions.load(connection, false);
        List<RuleRecord> activeRules = rules.loadActiveRules(connection);

        List<RuleTransactionRow> matches = matchingTransactions(labeledTxs, request);
        int allMatches = matchingTransactions(allTxs, request).size();
        int addedMatches = addedMatches(allTxs, activeRules, request);
        List<RuleTransactionRow> correct = summaries.correctMatches(matches, request.setCategoryId(), request.setKind());
        List<RuleTransactionRow> incorrect = summaries.incorrectMatches(matches, request.setCategoryId(), request.setKind());

        return new RuleProposal(
                request.key(),
                request.name(),
                request.matchDescriptionPattern(),
                request.matchAccountId(),
                request.setCategoryId(),
                request.setKind(),
                request.setMerchant(),
                correct.size(),
                matches.size(),
                allMatches,
                addedMatches,
                correct.size(),
                incorrect.size(),
                accuracy(matches, correct),
                percentage(matches.size(), labeledTxs.size()),
                percentage(allMatches, allTxs.size()),
                percentage(addedMatches, allTxs.size()),
                summaries.breakdown(matches),
                summaries.examples(correct, incorrect, request.setCategoryId(), request.setKind()));
    }

    private int addedMatches(
            List<RuleTransactionRow> allTxs,
            List<RuleRecord> activeRules,
            RuleProposalRequest request) {
        int addedMatches = 0;
        for (RuleTransactionRow tx : allTxs) {
            if (matchesRequest(tx, request)
                    && !rules.evaluate(activeRules, tx.accountId(), tx.description(), tx.amount()).matched()) {
                addedMatches++;
            }
        }
        return addedMatches;
    }

    private List<RuleTransactionRow> matchingTransactions(
            List<RuleTransactionRow> rows,
            RuleProposalRequest request) {
        return rows.stream()
                .filter(tx -> matchesRequest(tx, request))
                .toList();
    }

    private boolean matchesRequest(RuleTransactionRow tx, RuleProposalRequest request) {
        return matcher.accountOk(request.matchAccountId(), tx.accountId())
                && matcher.proposalMatches(
                        request.matchDescriptionPattern(),
                        tx.descriptionNormalized(),
                        tx.descriptionRaw(),
                        tx.merchant());
    }

    private double accuracy(List<RuleTransactionRow> matches, List<RuleTransactionRow> correct) {
        return matches.isEmpty() ? 0.0 : ((double) correct.size()) / matches.size();
    }

    private double percentage(int count, int total) {
        return total == 0 ? 0.0 : ((double) count) / total * 100.0;
    }
}
