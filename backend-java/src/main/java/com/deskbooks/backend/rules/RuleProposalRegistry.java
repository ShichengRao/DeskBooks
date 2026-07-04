package com.deskbooks.backend.rules;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class RuleProposalRegistry {
    List<RuleProposalSignature> activeSignatures(List<RuleRecord> activeRules) {
        return activeRules.stream()
                .map(rule -> new RuleProposalSignature(
                        rule.matchDescriptionPattern(),
                        rule.matchAccountId(),
                        rule.setCategoryId(),
                        rule.setKind()))
                .toList();
    }

    List<String> rejectedSignatures(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT signature FROM rule_proposal_rejections");
                ResultSet rs = statement.executeQuery()) {
            List<String> signatures = new ArrayList<>();
            while (rs.next()) {
                signatures.add(rs.getString("signature"));
            }
            return signatures;
        }
    }

    boolean candidateIsAvailable(
            List<RuleProposalSignature> activeSignatures,
            List<String> rejectedSignatures,
            String key,
            String pattern,
            Long categoryId,
            String kind) {
        RuleProposalSignature signature = new RuleProposalSignature(pattern, null, categoryId, kind);
        return !activeSignatures.contains(signature)
                && !rejectedSignatures.contains(signature(key, pattern, null, categoryId, kind));
    }

    boolean reject(Connection connection, RuleProposalRequest request) throws SQLException {
        String signature = signature(
                request.key(),
                request.matchDescriptionPattern(),
                request.matchAccountId(),
                request.setCategoryId(),
                request.setKind());
        if (rejectionExists(connection, signature)) {
            return false;
        }
        insertRejection(connection, signature, request);
        return true;
    }

    private boolean rejectionExists(Connection connection, String signature) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM rule_proposal_rejections WHERE signature = ?
                """)) {
            statement.setString(1, signature);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void insertRejection(
            Connection connection,
            String signature,
            RuleProposalRequest request) throws SQLException {
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
    }

    private String signature(String key, String pattern, Long matchAccountId, Long categoryId, String kind) {
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
}

record RuleProposalSignature(String matchDescriptionPattern, Long matchAccountId, Long setCategoryId, String setKind) {
}
