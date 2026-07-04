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
    private final RuleProposalSummaries summaries = new RuleProposalSummaries();

    RuleProposalEngine(RuleMatcher matcher, RuleEngine rules) {
        this.matcher = matcher;
        this.rules = rules;
    }

    List<RuleEngine.RuleProposal> generate(Connection connection, int minSupport, int limit) throws SQLException {
        ProposalContext context = proposalContext(connection, minSupport);
        if (context.totalLabeled() == 0) {
            return List.of();
        }
        List<RuleEngine.RuleProposal> proposals = new ArrayList<>();
        for (Map.Entry<String, List<RuleTransactionRow>> entry : groupProposalCandidates(context.labeledTxs()).entrySet()) {
            RuleEngine.RuleProposal proposal = buildRuleProposal(entry.getKey(), entry.getValue(), context);
            if (proposal != null) {
                proposals.add(proposal);
            }
        }
        proposals.sort(Comparator
                .comparingInt(RuleEngine.RuleProposal::allTransactionMatches)
                .thenComparingInt(RuleEngine.RuleProposal::correctMatches)
                .thenComparingDouble(RuleEngine.RuleProposal::accuracy)
                .thenComparingInt(RuleEngine.RuleProposal::totalUserLabeledMatches)
                .reversed());
        return proposals.stream().limit(Math.max(0, limit)).toList();
    }

    RuleEngine.RuleProposal backtest(Connection connection, RuleEngine.RuleProposalRequest request) throws SQLException {
        List<RuleTransactionRow> labeledTxs = transactions.load(connection, true);
        List<RuleTransactionRow> allTxs = transactions.load(connection, false);
        int totalLabeled = labeledTxs.size();
        int totalTransactions = allTxs.size();
        List<RuleEngine.RuleRecord> activeRules = rules.loadActiveRules(connection);

        List<RuleTransactionRow> matches = matchingTransactions(labeledTxs, request);
        int allMatches = matchingTransactions(allTxs, request).size();
        int addedMatches = 0;
        for (RuleTransactionRow tx : allTxs) {
            if (matchesRequest(tx, request)
                    && !rules.evaluate(activeRules, tx.accountId(), tx.description(), tx.amount()).matched()) {
                addedMatches++;
            }
        }
        List<RuleTransactionRow> correct = summaries.correctMatches(matches, request.setCategoryId(), request.setKind());
        List<RuleTransactionRow> incorrect = summaries.incorrectMatches(matches, request.setCategoryId(), request.setKind());
        return new RuleEngine.RuleProposal(
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
                matches.isEmpty() ? 0.0 : ((double) correct.size()) / matches.size(),
                totalLabeled == 0 ? 0.0 : ((double) matches.size()) / totalLabeled * 100.0,
                totalTransactions == 0 ? 0.0 : ((double) allMatches) / totalTransactions * 100.0,
                totalTransactions == 0 ? 0.0 : ((double) addedMatches) / totalTransactions * 100.0,
                summaries.breakdown(matches),
                summaries.examples(correct, incorrect, request.setCategoryId(), request.setKind()));
    }

    boolean reject(Connection connection, RuleEngine.RuleProposalRequest request) throws SQLException {
        return proposals.reject(connection, request);
    }

    private ProposalContext proposalContext(Connection connection, int minSupport) throws SQLException {
        List<RuleTransactionRow> labeled = transactions.load(connection, true);
        List<RuleTransactionRow> all = transactions.load(connection, false);
        List<RuleEngine.RuleRecord> activeRules = rules.loadActiveRules(connection);
        return new ProposalContext(
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

    private RuleEngine.RuleProposal buildRuleProposal(String key, List<RuleTransactionRow> txs, ProposalContext context) {
        RuleProposalOutcome outcome = summaries.majorityOutcome(txs, context.minSupport());
        if (outcome == null) {
            return null;
        }
        String pattern = matcher.proposalPattern(key);
        if (!candidateIsAvailable(context, key, pattern, outcome.categoryId(), outcome.kind())) {
            return null;
        }
        List<RuleTransactionRow> matches = context.labeledTxs().stream()
                .filter(tx -> matcher.proposalMatches(
                        pattern, tx.descriptionNormalized(), tx.descriptionRaw(), tx.merchant()))
                .toList();
        if (matches.isEmpty()) {
            return null;
        }
        MatchCounts allAndAdded = allAndAddedMatches(pattern, context);
        List<RuleTransactionRow> correct = summaries.correctMatches(matches, outcome.categoryId(), outcome.kind());
        List<RuleTransactionRow> incorrect = summaries.incorrectMatches(matches, outcome.categoryId(), outcome.kind());
        double accuracy = matches.isEmpty() ? 0.0 : ((double) correct.size()) / matches.size();
        if (accuracy < MINIMUM_PROPOSAL_ACCURACY) {
            return null;
        }
        return new RuleEngine.RuleProposal(
                key,
                key,
                pattern,
                null,
                outcome.categoryId(),
                outcome.kind(),
                key.length() > 255 ? key.substring(0, 255) : key,
                outcome.support(),
                matches.size(),
                allAndAdded.allMatches(),
                allAndAdded.addedMatches(),
                correct.size(),
                incorrect.size(),
                accuracy,
                ((double) matches.size()) / context.totalLabeled() * 100.0,
                context.totalTransactions() == 0 ? 0.0 : ((double) allAndAdded.allMatches()) / context.totalTransactions() * 100.0,
                context.totalTransactions() == 0 ? 0.0 : ((double) allAndAdded.addedMatches()) / context.totalTransactions() * 100.0,
                summaries.breakdown(matches),
                summaries.examples(correct, incorrect, outcome.categoryId(), outcome.kind()));
    }

    private boolean candidateIsAvailable(ProposalContext context, String key, String pattern, Long categoryId, String kind) {
        return proposals.candidateIsAvailable(context.activeSignatures(), context.rejectedSignatures(), key, pattern, categoryId, kind);
    }

    private MatchCounts allAndAddedMatches(String pattern, ProposalContext context) {
        int allMatches = 0;
        int addedMatches = 0;
        for (RuleTransactionRow tx : context.allTxs()) {
            if (!matcher.proposalMatches(pattern, tx.descriptionNormalized(), tx.descriptionRaw(), tx.merchant())) {
                continue;
            }
            allMatches++;
            RuleEngine.RuleEval eval = rules.evaluate(context.activeRules(), tx.accountId(), tx.description(), tx.amount());
            if (!eval.matched()) {
                addedMatches++;
            }
        }
        return new MatchCounts(allMatches, addedMatches);
    }

    private List<RuleTransactionRow> matchingTransactions(
            List<RuleTransactionRow> transactions,
            RuleEngine.RuleProposalRequest request) {
        return transactions.stream()
                .filter(tx -> matchesRequest(tx, request))
                .toList();
    }

    private boolean matchesRequest(RuleTransactionRow tx, RuleEngine.RuleProposalRequest request) {
        return matcher.accountOk(request.matchAccountId(), tx.accountId())
                && matcher.proposalMatches(
                        request.matchDescriptionPattern(),
                        tx.descriptionNormalized(),
                        tx.descriptionRaw(),
                        tx.merchant());
    }

    private record ProposalContext(
            List<RuleTransactionRow> labeledTxs,
            List<RuleTransactionRow> allTxs,
            int totalLabeled,
            int totalTransactions,
            List<RuleProposalSignature> activeSignatures,
            List<RuleEngine.RuleRecord> activeRules,
            List<String> rejectedSignatures,
            int minSupport) {
    }

    private record MatchCounts(int allMatches, int addedMatches) {
    }
}
