package com.deskbooks.backend.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class RuleProposalEngine {
    private final RuleMatcher matcher;
    private final RuleEngine rules;
    private final RuleTransactionReader transactions = new RuleTransactionReader();

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
        List<RuleTransactionRow> correct = correctMatches(matches, request.setCategoryId(), request.setKind());
        List<RuleTransactionRow> incorrect = incorrectMatches(matches, request.setCategoryId(), request.setKind());
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
                breakdown(matches),
                proposalExamples(correct, incorrect, request.setCategoryId(), request.setKind()));
    }

    boolean reject(Connection connection, RuleEngine.RuleProposalRequest request) throws SQLException {
        String signature = proposalSignature(
                request.key(),
                request.matchDescriptionPattern(),
                request.matchAccountId(),
                request.setCategoryId(),
                request.setKind());
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM rule_proposal_rejections WHERE signature = ?
                """)) {
            statement.setString(1, signature);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return false;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO rule_proposal_rejections (
                  signature, key, name, match_account_id, match_description_pattern,
                  set_category_id, set_kind, set_merchant
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, signature);
            statement.setString(2, request.key());
            statement.setString(3, request.name());
            setNullableLong(statement, 4, request.matchAccountId());
            statement.setString(5, request.matchDescriptionPattern());
            setNullableLong(statement, 6, request.setCategoryId());
            statement.setString(7, request.setKind());
            statement.setString(8, request.setMerchant());
            statement.executeUpdate();
        }
        return true;
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
                activeSignatures(activeRules),
                activeRules,
                rejectedSignatures(connection),
                minSupport);
    }

    private List<RuleSignature> activeSignatures(List<RuleEngine.RuleRecord> activeRules) {
        return activeRules.stream()
                .map(rule -> new RuleSignature(
                        rule.matchDescriptionPattern(),
                        rule.matchAccountId(),
                        rule.setCategoryId(),
                        rule.setKind()))
                .toList();
    }

    private List<String> rejectedSignatures(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT signature FROM rule_proposal_rejections");
                ResultSet rs = statement.executeQuery()) {
            List<String> signatures = new ArrayList<>();
            while (rs.next()) {
                signatures.add(rs.getString("signature"));
            }
            return signatures;
        }
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
        Outcome outcome = majorityOutcome(txs, context.minSupport());
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
        List<RuleTransactionRow> correct = correctMatches(matches, outcome.categoryId(), outcome.kind());
        List<RuleTransactionRow> incorrect = incorrectMatches(matches, outcome.categoryId(), outcome.kind());
        double accuracy = matches.isEmpty() ? 0.0 : ((double) correct.size()) / matches.size();
        if (accuracy < 0.75) {
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
                breakdown(matches),
                proposalExamples(correct, incorrect, outcome.categoryId(), outcome.kind()));
    }

    private Outcome majorityOutcome(List<RuleTransactionRow> txs, int minSupport) {
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
        return new Outcome(winner.getKey().categoryId(), winner.getKey().kind(), winner.getValue());
    }

    private boolean candidateIsAvailable(ProposalContext context, String key, String pattern, Long categoryId, String kind) {
        RuleSignature signature = new RuleSignature(pattern, null, categoryId, kind);
        if (context.activeSignatures().contains(signature)) {
            return false;
        }
        String rejected = proposalSignature(key, pattern, null, categoryId, kind);
        return !context.rejectedSignatures().contains(rejected);
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

    private List<RuleTransactionRow> correctMatches(List<RuleTransactionRow> matches, Long categoryId, String kind) {
        return matches.stream()
                .filter(tx -> Objects.equals(tx.categoryId(), categoryId) && kind.equals(tx.kind()))
                .toList();
    }

    private List<RuleTransactionRow> incorrectMatches(List<RuleTransactionRow> matches, Long categoryId, String kind) {
        return matches.stream()
                .filter(tx -> !Objects.equals(tx.categoryId(), categoryId) || !kind.equals(tx.kind()))
                .toList();
    }

    private List<RuleEngine.RuleProposalBreakdown> breakdown(List<RuleTransactionRow> matches) {
        Map<OutcomeKey, Integer> counts = new HashMap<>();
        for (RuleTransactionRow tx : matches) {
            counts.merge(new OutcomeKey(tx.categoryId(), tx.kind()), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<OutcomeKey, Integer>comparingByValue().reversed())
                .map(entry -> new RuleEngine.RuleProposalBreakdown(entry.getKey().categoryId(), entry.getKey().kind(), entry.getValue()))
                .toList();
    }

    private List<RuleEngine.RuleProposalExample> proposalExamples(
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

    private String proposalSignature(String key, String pattern, Long matchAccountId, Long categoryId, String kind) {
        return String.join("|",
                List.of(
                        key == null ? "" : key.strip().toLowerCase(Locale.ROOT),
                        pattern == null ? "" : pattern.strip(),
                        matchAccountId == null ? "" : String.valueOf(matchAccountId),
                        categoryId == null ? "" : String.valueOf(categoryId),
                        kind == null ? "" : kind));
    }

    private void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setLong(index, value);
        }
    }

    private String moneyString(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private record ProposalContext(
            List<RuleTransactionRow> labeledTxs,
            List<RuleTransactionRow> allTxs,
            int totalLabeled,
            int totalTransactions,
            List<RuleSignature> activeSignatures,
            List<RuleEngine.RuleRecord> activeRules,
            List<String> rejectedSignatures,
            int minSupport) {
    }

    private record RuleSignature(String matchDescriptionPattern, Long matchAccountId, Long setCategoryId, String setKind) {
    }

    private record OutcomeKey(Long categoryId, String kind) {
    }

    private record Outcome(Long categoryId, String kind, int support) {
    }

    private record MatchCounts(int allMatches, int addedMatches) {
    }
}
