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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class RuleEngine {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter SQLITE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final RuleMatcher matcher = new RuleMatcher();
    private final RuleProposalEngine proposals = new RuleProposalEngine(matcher, this);
    private final RuleReapplyEngine reapply = new RuleReapplyEngine(this);
    private final RuleCoverageReporter coverage = new RuleCoverageReporter(this);

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
            if (matcher.matches(rule, accountId, description, amount)) {
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
        return reapply.reapplyToUnreviewed(connection);
    }

    RuleCoverage coverageSummary(Connection connection) throws SQLException {
        return coverage.summarize(connection);
    }

    List<RuleProposal> generateRuleProposals(Connection connection, int minSupport, int limit) throws SQLException {
        return proposals.generate(connection, minSupport, limit);
    }

    RuleProposal backtestRuleProposal(Connection connection, RuleProposalRequest request) throws SQLException {
        return proposals.backtest(connection, request);
    }

    boolean rejectRuleProposal(Connection connection, RuleProposalRequest request) throws SQLException {
        return proposals.reject(connection, request);
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

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
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

}
