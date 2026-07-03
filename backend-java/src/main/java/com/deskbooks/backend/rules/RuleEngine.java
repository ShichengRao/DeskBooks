package com.deskbooks.backend.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class RuleEngine {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter SQLITE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public List<RuleRecord> loadActiveRules(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, name, priority, is_active, match_account_id, match_description_pattern,
                       match_amount_min, match_amount_max, set_category_id, set_kind, set_merchant,
                       set_tags, notes, apply_count, last_applied_at
                FROM rules
                WHERE is_active = 1
                ORDER BY priority ASC
                """);
                ResultSet rs = statement.executeQuery()) {
            List<RuleRecord> rules = new ArrayList<>();
            while (rs.next()) {
                rules.add(ruleFrom(rs));
            }
            return rules;
        }
    }

    public RuleEval evaluate(List<RuleRecord> rules, long accountId, String description, BigDecimal amount) {
        for (RuleRecord rule : rules) {
            if (matches(rule, accountId, description, amount)) {
                return new RuleEval(
                        rule.setCategoryId(),
                        rule.setKind(),
                        rule.setMerchant(),
                        rule.setTags(),
                        rule.id());
            }
        }
        return RuleEval.empty();
    }

    public void stampRuleFires(Connection connection, List<Long> ruleIds) throws SQLException {
        if (ruleIds.isEmpty()) {
            return;
        }
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (Long ruleId : ruleIds) {
            if (ruleId != null) {
                counts.merge(ruleId, 1, Integer::sum);
            }
        }
        String now = LocalDateTime.now().format(SQLITE_TIMESTAMP);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE rules
                SET apply_count = apply_count + ?, last_applied_at = ?
                WHERE id = ?
                """)) {
            for (Map.Entry<Long, Integer> entry : counts.entrySet()) {
                statement.setInt(1, entry.getValue());
                statement.setString(2, now);
                statement.setLong(3, entry.getKey());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    ReapplyResult reapplyToUnreviewed(Connection connection) throws SQLException {
        List<RuleRecord> rules = loadActiveRules(connection);
        if (rules.isEmpty()) {
            return new ReapplyResult(0, 0);
        }
        Map<Long, Integer> fires = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, account_id, description_raw, description_normalized, merchant,
                       amount, category_id, kind
                FROM transactions
                WHERE is_user_categorized = 0
                """);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                RuleEval eval = evaluate(
                        rules,
                        rs.getLong("account_id"),
                        firstNonNull(rs.getString("description_normalized"), rs.getString("description_raw"), ""),
                        rs.getBigDecimal("amount"));
                if (!eval.matched()) {
                    continue;
                }
                List<ColumnValue> values = new ArrayList<>();
                Long currentCategoryId = nullableLong(rs, "category_id");
                if (eval.categoryId() != null && !eval.categoryId().equals(currentCategoryId)) {
                    values.add(new ColumnValue("category_id", eval.categoryId()));
                }
                String currentKind = rs.getString("kind");
                if (eval.kind() != null && !eval.kind().equals(currentKind)) {
                    values.add(new ColumnValue("kind", eval.kind()));
                }
                String currentMerchant = rs.getString("merchant");
                if (eval.merchant() != null && !eval.merchant().isBlank() && !eval.merchant().equals(currentMerchant)) {
                    values.add(new ColumnValue("merchant", eval.merchant()));
                }
                if (values.isEmpty()) {
                    continue;
                }
                values.add(new ColumnValue("matched_rule_id", eval.matchedRuleId()));
                updateTransaction(connection, rs.getLong("id"), values);
                fires.merge(eval.matchedRuleId(), 1, Integer::sum);
            }
        }
        stampRuleFires(connection, expandFires(fires));
        int rowsChanged = fires.values().stream().mapToInt(Integer::intValue).sum();
        return new ReapplyResult(rowsChanged, fires.size());
    }

    RuleCoverage coverageSummary(Connection connection) throws SQLException {
        List<RuleRecord> rules = loadActiveRules(connection);
        List<TransactionRow> txs = loadTransactions(connection, false);
        int total = txs.size();
        int labeledTransactions = 0;
        int matched = 0;
        int labeledMatched = 0;
        int labeledCorrect = 0;
        int labeledIncorrect = 0;
        for (TransactionRow tx : txs) {
            boolean labeled = tx.categoryId() != null && !"uncategorized".equals(tx.kind());
            if (labeled) {
                labeledTransactions++;
            }
            RuleEval eval = evaluate(rules, tx.accountId(), tx.description(), tx.amount());
            if (!eval.matched()) {
                continue;
            }
            matched++;
            if (labeled) {
                labeledMatched++;
                boolean categoryOk = eval.categoryId() == null || eval.categoryId().equals(tx.categoryId());
                boolean kindOk = eval.kind() == null || eval.kind().equals(tx.kind());
                if (categoryOk && kindOk) {
                    labeledCorrect++;
                } else {
                    labeledIncorrect++;
                }
            }
        }
        Double accuracy = labeledMatched == 0 ? null : ((double) labeledCorrect) / labeledMatched;
        double coverage = total == 0 ? 0.0 : ((double) matched) / total * 100.0;
        return new RuleCoverage(
                rules.size(),
                total,
                matched,
                coverage,
                labeledTransactions,
                labeledMatched,
                labeledCorrect,
                labeledIncorrect,
                accuracy);
    }

    List<RuleProposal> generateRuleProposals(Connection connection, int minSupport, int limit) throws SQLException {
        ProposalContext context = proposalContext(connection, minSupport);
        if (context.totalLabeled() == 0) {
            return List.of();
        }
        List<RuleProposal> proposals = new ArrayList<>();
        for (Map.Entry<String, List<TransactionRow>> entry : groupProposalCandidates(context.labeledTxs()).entrySet()) {
            RuleProposal proposal = buildRuleProposal(entry.getKey(), entry.getValue(), context);
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

    RuleProposal backtestRuleProposal(Connection connection, RuleProposalRequest request) throws SQLException {
        List<TransactionRow> labeledTxs = loadLabeledTrainingTransactions(connection);
        List<TransactionRow> allTxs = loadTransactions(connection, false);
        int totalLabeled = labeledTxs.size();
        int totalTransactions = allTxs.size();
        List<RuleRecord> activeRules = loadActiveRules(connection);

        List<TransactionRow> matches = labeledTxs.stream()
                .filter(tx -> accountOk(request.matchAccountId(), tx))
                .filter(tx -> proposalMatches(request.matchDescriptionPattern(), tx))
                .toList();
        int allMatches = (int) allTxs.stream()
                .filter(tx -> accountOk(request.matchAccountId(), tx))
                .filter(tx -> proposalMatches(request.matchDescriptionPattern(), tx))
                .count();
        int addedMatches = 0;
        for (TransactionRow tx : allTxs) {
            if (accountOk(request.matchAccountId(), tx)
                    && proposalMatches(request.matchDescriptionPattern(), tx)
                    && !evaluate(activeRules, tx.accountId(), tx.description(), tx.amount()).matched()) {
                addedMatches++;
            }
        }
        List<TransactionRow> correct = matches.stream()
                .filter(tx -> Objects.equals(tx.categoryId(), request.setCategoryId()) && request.setKind().equals(tx.kind()))
                .toList();
        List<TransactionRow> incorrect = matches.stream()
                .filter(tx -> !Objects.equals(tx.categoryId(), request.setCategoryId()) || !request.setKind().equals(tx.kind()))
                .toList();
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
                matches.isEmpty() ? 0.0 : ((double) correct.size()) / matches.size(),
                totalLabeled == 0 ? 0.0 : ((double) matches.size()) / totalLabeled * 100.0,
                totalTransactions == 0 ? 0.0 : ((double) allMatches) / totalTransactions * 100.0,
                totalTransactions == 0 ? 0.0 : ((double) addedMatches) / totalTransactions * 100.0,
                breakdown(matches),
                proposalExamples(correct, incorrect, request.setCategoryId(), request.setKind()));
    }

    boolean rejectRuleProposal(Connection connection, RuleProposalRequest request) throws SQLException {
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

    RuleRecord ruleFrom(ResultSet rs) throws SQLException {
        return new RuleRecord(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getInt("priority"),
                rs.getBoolean("is_active"),
                nullableLong(rs, "match_account_id"),
                rs.getString("match_description_pattern"),
                moneyString(rs.getBigDecimal("match_amount_min")),
                moneyString(rs.getBigDecimal("match_amount_max")),
                nullableLong(rs, "set_category_id"),
                rs.getString("set_kind"),
                rs.getString("set_merchant"),
                parseTags(rs.getString("set_tags")),
                rs.getString("notes"),
                rs.getInt("apply_count"),
                localDateTime(rs.getString("last_applied_at")));
    }

    String tagsJson(List<String> tags) {
        if (tags == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(tags);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    List<String> parseTags(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(raw, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private boolean matches(RuleRecord rule, long accountId, String description, BigDecimal amount) {
        if (rule.matchAccountId() != null && rule.matchAccountId() != accountId) {
            return false;
        }
        if (rule.matchDescriptionPattern() != null && !rule.matchDescriptionPattern().isBlank()) {
            try {
                if (!Pattern.compile(rule.matchDescriptionPattern(), Pattern.CASE_INSENSITIVE)
                        .matcher(description == null ? "" : description)
                        .find()) {
                    return false;
                }
            } catch (PatternSyntaxException exception) {
                return false;
            }
        }
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
        if (rule.matchAmountMin() != null && value.compareTo(new BigDecimal(rule.matchAmountMin())) < 0) {
            return false;
        }
        return rule.matchAmountMax() == null || value.compareTo(new BigDecimal(rule.matchAmountMax())) <= 0;
    }

    private ProposalContext proposalContext(Connection connection, int minSupport) throws SQLException {
        List<TransactionRow> labeled = loadLabeledTrainingTransactions(connection);
        List<TransactionRow> all = loadTransactions(connection, false);
        List<RuleRecord> activeRules = loadActiveRules(connection);
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

    private List<TransactionRow> loadLabeledTrainingTransactions(Connection connection) throws SQLException {
        return loadTransactions(connection, true);
    }

    private List<TransactionRow> loadTransactions(Connection connection, boolean labeledTrainingOnly) throws SQLException {
        String sql = """
                SELECT id, account_id, date, description_raw, description_normalized, merchant,
                       amount, category_id, kind, matched_rule_id
                FROM transactions
                """ + (labeledTrainingOnly
                ? " WHERE category_id IS NOT NULL AND kind != 'uncategorized' AND matched_rule_id IS NULL"
                : "");
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<TransactionRow> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new TransactionRow(
                        rs.getLong("id"),
                        rs.getLong("account_id"),
                        LocalDate.parse(rs.getString("date")),
                        rs.getString("description_raw"),
                        rs.getString("description_normalized"),
                        rs.getString("merchant"),
                        rs.getBigDecimal("amount"),
                        nullableLong(rs, "category_id"),
                        rs.getString("kind"),
                        nullableLong(rs, "matched_rule_id")));
            }
            return rows;
        }
    }

    private List<RuleSignature> activeSignatures(List<RuleRecord> activeRules) {
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

    private Map<String, List<TransactionRow>> groupProposalCandidates(List<TransactionRow> labeledTxs) {
        Map<String, List<TransactionRow>> byKey = new LinkedHashMap<>();
        for (TransactionRow tx : labeledTxs) {
            String key = proposalKey(tx);
            if (!key.isBlank() && key.split("\\s+").length >= 2) {
                byKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(tx);
            }
        }
        return byKey;
    }

    private RuleProposal buildRuleProposal(String key, List<TransactionRow> txs, ProposalContext context) {
        Outcome outcome = majorityOutcome(txs, context.minSupport());
        if (outcome == null) {
            return null;
        }
        String pattern = proposalPattern(key);
        if (!validProposalPattern(pattern)) {
            return null;
        }
        if (!candidateIsAvailable(context, key, pattern, outcome.categoryId(), outcome.kind())) {
            return null;
        }
        List<TransactionRow> matches = context.labeledTxs().stream()
                .filter(tx -> proposalMatches(pattern, tx))
                .toList();
        if (matches.isEmpty()) {
            return null;
        }
        MatchCounts allAndAdded = allAndAddedMatches(pattern, context);
        List<TransactionRow> correct = matches.stream()
                .filter(tx -> Objects.equals(tx.categoryId(), outcome.categoryId()) && outcome.kind().equals(tx.kind()))
                .toList();
        List<TransactionRow> incorrect = matches.stream()
                .filter(tx -> !Objects.equals(tx.categoryId(), outcome.categoryId()) || !outcome.kind().equals(tx.kind()))
                .toList();
        double accuracy = matches.isEmpty() ? 0.0 : ((double) correct.size()) / matches.size();
        if (accuracy < 0.75) {
            return null;
        }
        return new RuleProposal(
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

    private Outcome majorityOutcome(List<TransactionRow> txs, int minSupport) {
        if (txs.size() < minSupport) {
            return null;
        }
        Map<OutcomeKey, Integer> counts = new HashMap<>();
        for (TransactionRow tx : txs) {
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

    private boolean validProposalPattern(String pattern) {
        if (pattern.isBlank()) {
            return false;
        }
        try {
            Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
            return true;
        } catch (PatternSyntaxException exception) {
            return false;
        }
    }

    private boolean proposalMatches(String pattern, TransactionRow tx) {
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException exception) {
            return false;
        }
        String desc = firstNonNull(tx.descriptionNormalized(), tx.descriptionRaw(), "");
        String merchant = tx.merchant() == null ? "" : tx.merchant();
        return compiled.matcher(desc).find()
                || compiled.matcher(merchant).find()
                || compiled.matcher(generalizeDescription(desc)).find()
                || compiled.matcher(generalizeDescription(merchant)).find();
    }

    private MatchCounts allAndAddedMatches(String pattern, ProposalContext context) {
        int allMatches = 0;
        int addedMatches = 0;
        for (TransactionRow tx : context.allTxs()) {
            if (!proposalMatches(pattern, tx)) {
                continue;
            }
            allMatches++;
            RuleEval eval = evaluate(context.activeRules(), tx.accountId(), tx.description(), tx.amount());
            if (!eval.matched()) {
                addedMatches++;
            }
        }
        return new MatchCounts(allMatches, addedMatches);
    }

    private List<RuleProposalBreakdown> breakdown(List<TransactionRow> matches) {
        Map<OutcomeKey, Integer> counts = new HashMap<>();
        for (TransactionRow tx : matches) {
            counts.merge(new OutcomeKey(tx.categoryId(), tx.kind()), 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<OutcomeKey, Integer>comparingByValue().reversed())
                .map(entry -> new RuleProposalBreakdown(entry.getKey().categoryId(), entry.getKey().kind(), entry.getValue()))
                .toList();
    }

    private List<RuleProposalExample> proposalExamples(
            List<TransactionRow> correct,
            List<TransactionRow> incorrect,
            Long categoryId,
            String kind) {
        List<TransactionRow> candidates = new ArrayList<>();
        candidates.addAll(incorrect.stream().limit(3).toList());
        candidates.addAll(correct.stream().limit(3).toList());
        return candidates.stream()
                .limit(6)
                .map(tx -> new RuleProposalExample(
                        tx.id(),
                        tx.date(),
                        tx.description(),
                        moneyString(tx.amount()),
                        tx.categoryId(),
                        tx.kind(),
                        Objects.equals(tx.categoryId(), categoryId) && kind.equals(tx.kind())))
                .toList();
    }

    private boolean accountOk(Long matchAccountId, TransactionRow tx) {
        return matchAccountId == null || matchAccountId == tx.accountId();
    }

    private String proposalKey(TransactionRow tx) {
        return generalizeDescription(firstNonNull(tx.merchant(), tx.descriptionNormalized(), tx.descriptionRaw(), ""));
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

    private String proposalPattern(String key) {
        List<String> tokens = new ArrayList<>();
        for (String token : key.trim().split("\\s+")) {
            if (!token.isBlank()) {
                tokens.add(regexEscape(token));
            }
        }
        return String.join(".*", tokens);
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

    private String proposalSignature(String key, String pattern, Long matchAccountId, Long categoryId, String kind) {
        return String.join("|",
                List.of(
                        key == null ? "" : key.strip().toLowerCase(Locale.ROOT),
                        pattern == null ? "" : pattern.strip(),
                        matchAccountId == null ? "" : String.valueOf(matchAccountId),
                        categoryId == null ? "" : String.valueOf(categoryId),
                        kind == null ? "" : kind));
    }

    private List<Long> expandFires(Map<Long, Integer> fires) {
        List<Long> ids = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : fires.entrySet()) {
            for (int i = 0; i < entry.getValue(); i++) {
                ids.add(entry.getKey());
            }
        }
        return ids;
    }

    private void updateTransaction(Connection connection, long transactionId, List<ColumnValue> values) throws SQLException {
        StringJoiner assignments = new StringJoiner(", ");
        for (ColumnValue value : values) {
            assignments.add(value.column() + " = ?");
        }
        assignments.add("updated_at = CURRENT_TIMESTAMP");
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE transactions SET " + assignments + " WHERE id = ?")) {
            int index = 1;
            for (ColumnValue value : values) {
                if (value.value() == null) {
                    statement.setObject(index++, null);
                } else {
                    statement.setObject(index++, value.value());
                }
            }
            statement.setLong(index, transactionId);
            statement.executeUpdate();
        }
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
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

    private LocalDateTime localDateTime(String value) {
        if (value == null) {
            return null;
        }
        return value.contains("T") ? LocalDateTime.parse(value) : LocalDateTime.parse(value, SQLITE_TIMESTAMP);
    }

    private String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public record RuleRecord(
            long id,
            String name,
            int priority,
            boolean isActive,
            Long matchAccountId,
            String matchDescriptionPattern,
            String matchAmountMin,
            String matchAmountMax,
            Long setCategoryId,
            String setKind,
            String setMerchant,
            List<String> setTags,
            String notes,
            int applyCount,
            LocalDateTime lastAppliedAt) {
    }

    public record RuleEval(Long categoryId, String kind, String merchant, List<String> tags, Long matchedRuleId) {
        static RuleEval empty() {
            return new RuleEval(null, null, null, null, null);
        }

        public boolean matched() {
            return matchedRuleId != null;
        }
    }

    public record ReapplyResult(int rowsChanged, int rulesFired) {
    }

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

    public record RuleProposalRequest(
            String key,
            String name,
            String matchDescriptionPattern,
            Long matchAccountId,
            Long setCategoryId,
            String setKind,
            String setMerchant) {
    }

    public record RuleProposal(
            String key,
            String name,
            String matchDescriptionPattern,
            Long matchAccountId,
            Long setCategoryId,
            String setKind,
            String setMerchant,
            int support,
            int totalUserLabeledMatches,
            int allTransactionMatches,
            int addedTransactionMatches,
            int correctMatches,
            int incorrectMatches,
            double accuracy,
            double labeledCoveragePercent,
            double allCoveragePercent,
            double addedCoveragePercent,
            List<RuleProposalBreakdown> breakdown,
            List<RuleProposalExample> examples) {
    }

    public record RuleProposalBreakdown(Long categoryId, String kind, int count) {
    }

    public record RuleProposalExample(
            long transactionId,
            LocalDate date,
            String description,
            String amount,
            Long categoryId,
            String kind,
            boolean correct) {
    }

    private record TransactionRow(
            long id,
            long accountId,
            LocalDate date,
            String descriptionRaw,
            String descriptionNormalized,
            String merchant,
            BigDecimal amount,
            Long categoryId,
            String kind,
            Long matchedRuleId) {
        String description() {
            return descriptionNormalized == null ? descriptionRaw : descriptionNormalized;
        }
    }

    private record ProposalContext(
            List<TransactionRow> labeledTxs,
            List<TransactionRow> allTxs,
            int totalLabeled,
            int totalTransactions,
            List<RuleSignature> activeSignatures,
            List<RuleRecord> activeRules,
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

    private record ColumnValue(String column, Object value) {
    }
}
