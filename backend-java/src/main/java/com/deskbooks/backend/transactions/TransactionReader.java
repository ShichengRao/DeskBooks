package com.deskbooks.backend.transactions;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;

final class TransactionReader {
    private static final List<String> SELECT_COLUMNS = List.of(
            "t.id", "t.account_id", "t.date", "t.post_date", "t.description_raw",
            "t.description_normalized", "t.merchant", "t.amount", "t.category_id", "t.kind",
            "t.is_user_categorized", "t.is_excluded_from_totals", "t.notes", "t.transfer_pair_id",
            "t.import_batch_id", "t.matched_rule_id");

    String selectColumns() {
        return String.join(", ", SELECT_COLUMNS);
    }

    TransactionResponse get(Connection connection, long transactionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT %s FROM transactions t WHERE t.id = ?
                """.formatted(selectColumns()))) {
            statement.setLong(1, transactionId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, TransactionLookup.NOT_FOUND_DETAIL);
                }
                return from(connection, rs);
            }
        }
    }

    TransactionResponse from(Connection connection, ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        BigDecimal amount = rs.getBigDecimal("amount");
        return new TransactionResponse(
                id,
                rs.getLong("account_id"),
                localDate(rs, "date"),
                localDate(rs, "post_date"),
                rs.getString("description_raw"),
                rs.getString("description_normalized"),
                rs.getString("merchant"),
                moneyString(amount == null ? BigDecimal.ZERO : amount),
                nullableLong(rs, "category_id"),
                rs.getString("kind"),
                rs.getBoolean("is_user_categorized"),
                rs.getBoolean("is_excluded_from_totals"),
                rs.getString("notes"),
                nullableLong(rs, "transfer_pair_id"),
                nullableLong(rs, "import_batch_id"),
                nullableLong(rs, "matched_rule_id"),
                tags(connection, id),
                split(connection, id));
    }

    private List<TagResponse> tags(Connection connection, long transactionId) throws SQLException {
        List<TagResponse> tags = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT tags.id, tags.name, tags.color
                FROM transaction_tags
                JOIN tags ON tags.id = transaction_tags.tag_id
                WHERE transaction_tags.transaction_id = ?
                ORDER BY tags.name
                """)) {
            statement.setLong(1, transactionId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    tags.add(new TagResponse(
                            rs.getLong("id"),
                            rs.getString("name"),
                            rs.getString("color")));
                }
            }
        }
        return tags;
    }

    private TransactionSplitResponse split(Connection connection, long transactionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT transaction_id, group_name, personal_share, notes
                FROM transaction_splits
                WHERE transaction_id = ?
                """)) {
            statement.setLong(1, transactionId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new TransactionSplitResponse(
                        rs.getLong("transaction_id"),
                        rs.getString("group_name"),
                        rateString(rs.getBigDecimal("personal_share")),
                        rs.getString("notes"));
            }
        }
    }

    private LocalDate localDate(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        if (value == null) {
            return null;
        }
        if (value.matches("\\d{10,}")) {
            return Instant.ofEpochMilli(Long.parseLong(value))
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();
        }
        return LocalDate.parse(value);
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private String moneyString(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String rateString(BigDecimal value) {
        return (value == null ? new BigDecimal("0.5") : value).setScale(4, RoundingMode.HALF_UP).toPlainString();
    }
}
