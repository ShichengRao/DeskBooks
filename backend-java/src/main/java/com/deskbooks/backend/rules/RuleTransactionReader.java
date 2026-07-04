package com.deskbooks.backend.rules;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

final class RuleTransactionReader {
    List<RuleTransactionRow> load(Connection connection, boolean labeledTrainingOnly) throws SQLException {
        String sql = """
                SELECT id, account_id, date, description_raw, description_normalized, merchant,
                       amount, category_id, kind, matched_rule_id
                FROM transactions
                """ + (labeledTrainingOnly
                ? " WHERE category_id IS NOT NULL AND kind != 'uncategorized' AND matched_rule_id IS NULL"
                : "");
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            List<RuleTransactionRow> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(new RuleTransactionRow(
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

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}

record RuleTransactionRow(
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
