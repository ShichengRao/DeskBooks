package com.deskbooks.backend.rules;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RuleProposalEngine {
    private static final double MINIMUM_PROPOSAL_ACCURACY = 0.75;

    private final RuleMatcher matcher;
    private final RuleEngine rules;
    private final RuleTransactionReader transactions = new RuleTransactionReader();
    private final RuleProposalRegistry proposals = new RuleProposalRegistry();
    private final RuleProposalBacktester backtester;
    private final RuleProposalBuilder builder;

    RuleProposalEngine(RuleMatcher matcher, RuleEngine rules) {
        this.matcher = matcher;
        this.rules = rules;
        RuleProposalSummaries summaries = new RuleProposalSummaries();
        this.backtester = new RuleProposalBacktester(matcher, rules, transactions, summaries);
        this.builder = new RuleProposalBuilder(matcher, rules, proposals, summaries);
    }

    List<RuleProposal> generate(Connection connection, int minSupport, int limit) throws SQLException {
        RuleProposalContext context = proposalContext(connection, minSupport);
        if (context.totalLabeled() == 0) {
            return List.of();
        }
        List<RuleProposal> proposals = new ArrayList<>();
        for (Map.Entry<String, List<RuleTransactionRow>> entry : groupProposalCandidates(context.labeledTxs()).entrySet()) {
            RuleProposal proposal = builder.build(entry.getKey(), entry.getValue(), context);
            if (proposal != null) {
                proposals.add(proposal);
            }
        }
        proposals.sort(Comparator
                .comparingInt(RuleProposal::allTransactionMatches)
                .thenComparingInt(RuleProposal::correctMatches)
                .thenComparingDouble(RuleProposal::accuracy)
                .thenComparingInt(RuleProposal::totalUserLabeledMatches)
                .reversed());
        return proposals.stream().limit(Math.max(0, limit)).toList();
    }

    RuleProposal backtest(Connection connection, RuleProposalRequest request) throws SQLException {
        return backtester.backtest(connection, request);
    }

    boolean reject(Connection connection, RuleProposalRequest request) throws SQLException {
        return proposals.reject(connection, request);
    }

    private RuleProposalContext proposalContext(Connection connection, int minSupport) throws SQLException {
        List<RuleTransactionRow> labeled = transactions.load(connection, true);
        List<RuleTransactionRow> all = transactions.load(connection, false);
        List<RuleRecord> activeRules = rules.loadActiveRules(connection);
        return new RuleProposalContext(
                labeled,
                all,
                labeled.size(),
                all.size(),
                proposals.activeSignatures(activeRules),
                activeRules,
                proposals.rejectedSignatures(connection),
                minSupport);
    }

    private Map<String, List<RuleTransactionRow>> groupProposalCandidates(List<RuleTransactionRow> labeledTxs) {
        Map<String, List<RuleTransactionRow>> byKey = new LinkedHashMap<>();
        for (RuleTransactionRow tx : labeledTxs) {
            String key = matcher.proposalKey(tx.merchant(), tx.descriptionNormalized(), tx.descriptionRaw());
            if (!key.isBlank() && key.split("\\s+").length >= 2) {
                byKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tx);
            }
        }
        return byKey;
    }
}
