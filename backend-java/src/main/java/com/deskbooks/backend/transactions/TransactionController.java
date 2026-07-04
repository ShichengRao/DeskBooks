package com.deskbooks.backend.transactions;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/transactions")
class TransactionController {
    private final SqliteConnectionProvider connections;
    private final TransactionReader reader = new TransactionReader();

    TransactionController(SqliteConnectionProvider connections) {
        this.connections = connections;
    }

    @GetMapping("")
    List<TransactionResponse> listTransactions(
            @RequestParam(name = "start", required = false) LocalDate start,
            @RequestParam(name = "end", required = false) LocalDate end,
            @RequestParam(name = "account_id", required = false) Long accountId,
            @RequestParam(name = "account_category", required = false) List<String> accountCategory,
            @RequestParam(name = "category_id", required = false) Long categoryId,
            @RequestParam(name = "kind", required = false) List<String> kind,
            @RequestParam(name = "amount_min", required = false) BigDecimal amountMin,
            @RequestParam(name = "amount_max", required = false) BigDecimal amountMax,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "exclude_excluded", defaultValue = "false") boolean excludeExcluded,
            @RequestParam(name = "limit", defaultValue = "100") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset) {
        if (limit < 1 || offset < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "limit must be >= 1 and offset must be >= 0");
        }

        try (Connection connection = connections.open()) {
            TransactionFilters.FilterSql filters = TransactionFilters.build(
                    start, end, accountId, accountCategory, categoryId, kind, amountMin, amountMax, q, excludeExcluded);
            String sql = "SELECT " + reader.selectColumns()
                    + " FROM transactions t"
                    + (filters.joinAccounts() ? " JOIN accounts a ON a.id = t.account_id" : "")
                    + filters.whereSql()
                    + " ORDER BY t.date DESC, t.id DESC LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = bindParams(statement, filters.params(), 1);
                statement.setInt(index++, limit);
                statement.setInt(index, offset);
                try (ResultSet rs = statement.executeQuery()) {
                    List<TransactionResponse> transactions = new ArrayList<>();
                    while (rs.next()) {
                        transactions.add(reader.from(connection, rs));
                    }
                    return transactions;
                }
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/count")
    Map<String, Long> countTransactions(
            @RequestParam(name = "start", required = false) LocalDate start,
            @RequestParam(name = "end", required = false) LocalDate end,
            @RequestParam(name = "account_id", required = false) Long accountId,
            @RequestParam(name = "account_category", required = false) List<String> accountCategory,
            @RequestParam(name = "category_id", required = false) Long categoryId,
            @RequestParam(name = "kind", required = false) List<String> kind,
            @RequestParam(name = "amount_min", required = false) BigDecimal amountMin,
            @RequestParam(name = "amount_max", required = false) BigDecimal amountMax,
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "exclude_excluded", defaultValue = "false") boolean excludeExcluded) {
        try (Connection connection = connections.open()) {
            TransactionFilters.FilterSql filters = TransactionFilters.build(
                    start, end, accountId, accountCategory, categoryId, kind, amountMin, amountMax, q, excludeExcluded);
            String sql = "SELECT COUNT(t.id) AS count FROM transactions t"
                    + (filters.joinAccounts() ? " JOIN accounts a ON a.id = t.account_id" : "")
                    + filters.whereSql();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bindParams(statement, filters.params(), 1);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    return Map.of("count", rs.getLong("count"));
                }
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("")
    TransactionResponse createTransaction(@RequestBody JsonNode body) {
        try (Connection connection = connections.open()) {
            long accountId = requiredLong(body, "account_id");
            requireAccount(connection, accountId);

            Long categoryId = optionalLong(body, "category_id");
            CategoryInfo category = categoryId == null ? null : categoryOr404(connection, categoryId);
            String kind = textOrDefault(body, "kind", "uncategorized");
            if (category != null && !body.has("kind")) {
                kind = category.kind();
            }

            String descriptionRaw = requiredText(body, "description_raw");
            String normalized = textOrNull(body, "description_normalized");
            if (normalized == null || normalized.isBlank()) {
                normalized = normalizeDescription(descriptionRaw);
            }

            String sql = """
                    INSERT INTO transactions (
                      account_id, date, post_date, description_raw, description_normalized,
                      merchant, amount, category_id, kind, is_user_categorized,
                      is_excluded_from_totals, notes, matched_rule_id, raw, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, NULL, ?, CURRENT_TIMESTAMP)
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, accountId);
                statement.setString(2, requiredDate(body, "date").toString());
                statement.setString(3, optionalDateString(body, "post_date"));
                statement.setString(4, descriptionRaw);
                statement.setString(5, normalized);
                statement.setString(6, blankToNull(textOrNull(body, "merchant")));
                statement.setBigDecimal(7, requiredDecimal(body, "amount"));
                setNullableLong(statement, 8, categoryId);
                statement.setString(9, kind);
                statement.setBoolean(10, booleanOrDefault(body, "is_excluded_from_totals", false));
                statement.setString(11, blankToNull(textOrNull(body, "notes")));
                statement.setString(12, "{\"source\":\"manual\"}");
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    keys.next();
                    return reader.get(connection, keys.getLong(1));
                }
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/{transactionId}")
    TransactionResponse getTransaction(@PathVariable long transactionId) {
        try (Connection connection = connections.open()) {
            return reader.get(connection, transactionId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PutMapping("/{transactionId}/split")
    TransactionResponse setTransactionSplit(@PathVariable long transactionId, @RequestBody JsonNode body) {
        try (Connection connection = connections.open()) {
            requireTransaction(connection, transactionId);
            String groupName = blankToNull(textOrNull(body, "group_name"));
            if (groupName == null) {
                clearSplit(connection, transactionId);
            } else {
                upsertSplit(connection, transactionId, groupName, clampedShare(body), blankToNull(textOrNull(body, "notes")));
            }
            touchTransaction(connection, transactionId);
            return reader.get(connection, transactionId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PatchMapping("/bulk/update")
    Map<String, Integer> bulkUpdate(@RequestBody JsonNode body) {
        List<Long> ids = longList(body.get("ids"));
        if (ids.isEmpty()) {
            return Map.of("updated", 0);
        }

        try (Connection connection = connections.open()) {
            CategoryInfo category = null;
            if (body.has("category_id") && !body.get("category_id").isNull()) {
                category = categoryOr404(connection, body.get("category_id").asLong());
            }
            Set<Long> found = existingTransactions(connection, ids);
            if (found.isEmpty()) {
                return Map.of("updated", 0);
            }
            for (Long id : found) {
                applyBulkUpdate(connection, id, body, category);
            }
            return Map.of("updated", found.size());
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PatchMapping("/{transactionId}")
    TransactionResponse updateTransaction(@PathVariable long transactionId, @RequestBody JsonNode body) {
        try (Connection connection = connections.open()) {
            requireTransaction(connection, transactionId);
            List<ColumnValue> values = patchValues(connection, body);
            if (!values.isEmpty()) {
                applyTransactionUpdate(connection, transactionId, values);
            }
            return reader.get(connection, transactionId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("/pair")
    Map<String, String> pairTransactions(@RequestBody JsonNode body) {
        try (Connection connection = connections.open()) {
            long transactionAId = requiredLong(body, "transaction_a_id");
            long transactionBId = requiredLong(body, "transaction_b_id");
            requireTransaction(connection, transactionAId);
            requireTransaction(connection, transactionBId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE transactions
                    SET transfer_pair_id = ?, kind = 'transfer', is_user_categorized = 1, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """)) {
                statement.setLong(1, transactionBId);
                statement.setLong(2, transactionAId);
                statement.addBatch();
                statement.setLong(1, transactionAId);
                statement.setLong(2, transactionBId);
                statement.addBatch();
                statement.executeBatch();
            }
            return Map.of("status", "paired");
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("/{transactionId}/unpair")
    Map<String, String> unpairTransaction(@PathVariable long transactionId) {
        try (Connection connection = connections.open()) {
            Long pairId = transferPairId(connection, transactionId);
            if (pairId == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "transaction not paired");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE transactions SET transfer_pair_id = NULL, updated_at = CURRENT_TIMESTAMP WHERE id IN (?, ?)
                    """)) {
                statement.setLong(1, transactionId);
                statement.setLong(2, pairId);
                statement.executeUpdate();
            }
            return Map.of("status", "unpaired");
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @DeleteMapping("/{transactionId}")
    Map<String, String> deleteTransaction(@PathVariable long transactionId) {
        try (Connection connection = connections.open()) {
            requireTransaction(connection, transactionId);
            Long pairId = transferPairId(connection, transactionId);
            if (pairId != null) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE transactions SET transfer_pair_id = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                        """)) {
                    statement.setLong(1, pairId);
                    statement.executeUpdate();
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM transactions WHERE id = ?")) {
                statement.setLong(1, transactionId);
                statement.executeUpdate();
            }
            return Map.of("status", "deleted");
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private List<ColumnValue> patchValues(Connection connection, JsonNode body) throws SQLException {
        List<ColumnValue> values = new ArrayList<>();
        addDate(values, body, "date");
        addDate(values, body, "post_date");
        if (body.has("description_raw")) {
            String raw = textOrNull(body, "description_raw");
            values.add(new ColumnValue("description_raw", raw));
            if (!body.has("description_normalized")) {
                values.add(new ColumnValue("description_normalized", raw == null ? null : normalizeDescription(raw)));
            }
        }
        addText(values, body, "description_normalized");
        addText(values, body, "merchant");
        addBigDecimal(values, body, "amount");
        if (body.has("category_id")) {
            Long categoryId = optionalLong(body, "category_id");
            CategoryInfo category = categoryId == null ? null : categoryOr404(connection, categoryId);
            values.add(new ColumnValue("category_id", categoryId));
            values.add(new ColumnValue("is_user_categorized", true));
            values.add(new ColumnValue("matched_rule_id", null));
            if (category != null && !body.has("kind")) {
                values.add(new ColumnValue("kind", category.kind()));
            }
        }
        if (body.has("kind")) {
            values.add(new ColumnValue("kind", textOrNull(body, "kind")));
            values.add(new ColumnValue("is_user_categorized", true));
            values.add(new ColumnValue("matched_rule_id", null));
        }
        addBoolean(values, body, "is_excluded_from_totals");
        addText(values, body, "notes");
        addLong(values, body, "transfer_pair_id");
        return values;
    }

    private void applyBulkUpdate(Connection connection, long transactionId, JsonNode body, CategoryInfo category) throws SQLException {
        List<ColumnValue> values = new ArrayList<>();
        if (category != null) {
            values.add(new ColumnValue("category_id", category.id()));
            values.add(new ColumnValue("is_user_categorized", true));
            values.add(new ColumnValue("matched_rule_id", null));
            if (!body.has("kind")) {
                values.add(new ColumnValue("kind", category.kind()));
            }
        }
        if (body.has("kind") && !body.get("kind").isNull()) {
            values.add(new ColumnValue("kind", body.get("kind").asText()));
            values.add(new ColumnValue("is_user_categorized", true));
            values.add(new ColumnValue("matched_rule_id", null));
        }
        addBoolean(values, body, "is_excluded_from_totals");

        if (!values.isEmpty()) {
            applyTransactionUpdate(connection, transactionId, values);
        }

        if (body.has("clear_split") && body.get("clear_split").asBoolean()) {
            clearSplit(connection, transactionId);
            touchTransaction(connection, transactionId);
        } else if (body.has("split_group_name") && !body.get("split_group_name").isNull()) {
            String groupName = blankToNull(body.get("split_group_name").asText());
            if (groupName != null) {
                upsertSplit(connection, transactionId, groupName, clampedShare(body, "split_personal_share"), blankToNull(textOrNull(body, "split_notes")));
                touchTransaction(connection, transactionId);
            }
        }

        addTags(connection, transactionId, longList(body.get("add_tag_ids")));
        removeTags(connection, transactionId, longList(body.get("remove_tag_ids")));
    }

    private void applyTransactionUpdate(Connection connection, long transactionId, List<ColumnValue> values)
            throws SQLException {
        StringJoiner assignments = new StringJoiner(", ");
        for (ColumnValue value : values) {
            assignments.add(value.column() + " = ?");
        }
        assignments.add("updated_at = CURRENT_TIMESTAMP");
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE transactions SET " + assignments + " WHERE id = ?")) {
            int index = 1;
            for (ColumnValue value : values) {
                bindParam(statement, index++, value.value());
            }
            statement.setLong(index, transactionId);
            statement.executeUpdate();
        }
    }

    private void upsertSplit(Connection connection, long transactionId, String groupName, BigDecimal personalShare, String notes) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transaction_splits (transaction_id, group_name, personal_share, notes)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(transaction_id) DO UPDATE SET
                  group_name = excluded.group_name,
                  personal_share = excluded.personal_share,
                  notes = excluded.notes
                """)) {
            statement.setLong(1, transactionId);
            statement.setString(2, groupName);
            statement.setBigDecimal(3, personalShare);
            statement.setString(4, notes);
            statement.executeUpdate();
        }
    }

    private void clearSplit(Connection connection, long transactionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM transaction_splits WHERE transaction_id = ?")) {
            statement.setLong(1, transactionId);
            statement.executeUpdate();
        }
    }

    private void addTags(Connection connection, long transactionId, List<Long> tagIds) throws SQLException {
        if (tagIds.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO transaction_tags (transaction_id, tag_id)
                SELECT ?, id FROM tags WHERE id = ?
                """)) {
            for (Long tagId : tagIds) {
                statement.setLong(1, transactionId);
                statement.setLong(2, tagId);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        touchTransaction(connection, transactionId);
    }

    private void removeTags(Connection connection, long transactionId, List<Long> tagIds) throws SQLException {
        if (tagIds.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM transaction_tags WHERE transaction_id = ? AND tag_id = ?
                """)) {
            for (Long tagId : tagIds) {
                statement.setLong(1, transactionId);
                statement.setLong(2, tagId);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        touchTransaction(connection, transactionId);
    }

    private Set<Long> existingTransactions(Connection connection, List<Long> ids) throws SQLException {
        Set<Long> found = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM transactions WHERE id IN (%s)
                """.formatted(placeholders(ids.size())))) {
            bindParams(statement, new ArrayList<>(ids), 1);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    found.add(rs.getLong("id"));
                }
            }
        }
        return found;
    }

    private CategoryInfo categoryOr404(Connection connection, long categoryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, kind FROM categories WHERE id = ?")) {
            statement.setLong(1, categoryId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "category not found");
                }
                return new CategoryInfo(rs.getLong("id"), rs.getString("kind"));
            }
        }
    }

    private void requireAccount(Connection connection, long accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM accounts WHERE id = ?")) {
            statement.setLong(1, accountId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "account not found");
                }
            }
        }
    }

    private void requireTransaction(Connection connection, long transactionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM transactions WHERE id = ?")) {
            statement.setLong(1, transactionId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "transaction not found");
                }
            }
        }
    }

    private Long transferPairId(Connection connection, long transactionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT transfer_pair_id FROM transactions WHERE id = ?")) {
            statement.setLong(1, transactionId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "transaction not found");
                }
                long pairId = rs.getLong("transfer_pair_id");
                return rs.wasNull() ? null : pairId;
            }
        }
    }

    private void touchTransaction(Connection connection, long transactionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE transactions SET updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """)) {
            statement.setLong(1, transactionId);
            statement.executeUpdate();
        }
    }

    private String normalizeDescription(String description) {
        return String.join(" ", description.trim().split("\\s+"));
    }

    private void addText(List<ColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            values.add(new ColumnValue(field, textOrNull(body, field)));
        }
    }

    private void addDate(List<ColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            values.add(new ColumnValue(field, optionalDateString(body, field)));
        }
    }

    private void addBigDecimal(List<ColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new ColumnValue(field, node == null || node.isNull() ? null : new BigDecimal(node.asText())));
        }
    }

    private void addBoolean(List<ColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            JsonNode node = body.get(field);
            values.add(new ColumnValue(field, node == null || node.isNull() ? null : node.asBoolean()));
        }
    }

    private void addLong(List<ColumnValue> values, JsonNode body, String field) {
        if (body.has(field)) {
            values.add(new ColumnValue(field, optionalLong(body, field)));
        }
    }

    private LocalDate requiredDate(JsonNode body, String field) {
        JsonNode node = requiredNode(body, field);
        return LocalDate.parse(node.asText());
    }

    private String optionalDateString(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() || node.asText().isBlank() ? null : LocalDate.parse(node.asText()).toString();
    }

    private long requiredLong(JsonNode body, String field) {
        return requiredNode(body, field).asLong();
    }

    private Long optionalLong(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? null : node.asLong();
    }

    private BigDecimal requiredDecimal(JsonNode body, String field) {
        return new BigDecimal(requiredNode(body, field).asText());
    }

    private String requiredText(JsonNode body, String field) {
        String value = requiredNode(body, field).asText();
        if (value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value;
    }

    private JsonNode requiredNode(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return node;
    }

    private String textOrDefault(JsonNode body, String field, String defaultValue) {
        String value = textOrNull(body, field);
        return value == null ? defaultValue : value;
    }

    private String textOrNull(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private boolean booleanOrDefault(JsonNode body, String field, boolean defaultValue) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? defaultValue : node.asBoolean();
    }

    private BigDecimal clampedShare(JsonNode body) {
        return clampedShare(body, "personal_share");
    }

    private BigDecimal clampedShare(JsonNode body, String field) {
        JsonNode node = body.get(field);
        BigDecimal value = node == null || node.isNull() ? new BigDecimal("0.5") : new BigDecimal(node.asText());
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO;
        }
        if (value.compareTo(BigDecimal.ONE) > 0) {
            return BigDecimal.ONE;
        }
        return value;
    }

    private List<Long> longList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        List<Long> values = new ArrayList<>();
        for (JsonNode item : node) {
            values.add(item.asLong());
        }
        return values;
    }

    private int bindParams(PreparedStatement statement, List<Object> params, int startIndex) throws SQLException {
        int index = startIndex;
        for (Object param : params) {
            bindParam(statement, index++, param);
        }
        return index;
    }

    private void bindParam(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value instanceof LocalDate localDate) {
            statement.setString(index, localDate.toString());
        } else if (value instanceof BigDecimal decimal) {
            statement.setBigDecimal(index, decimal);
        } else if (value instanceof Long longValue) {
            statement.setLong(index, longValue);
        } else if (value instanceof Integer intValue) {
            statement.setInt(index, intValue);
        } else if (value instanceof Boolean boolValue) {
            statement.setBoolean(index, boolValue);
        } else {
            statement.setObject(index, value);
        }
    }

    private void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setLong(index, value);
        }
    }

    private String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }

    record TransactionResponse(
            long id,
            long accountId,
            LocalDate date,
            LocalDate postDate,
            String descriptionRaw,
            String descriptionNormalized,
            String merchant,
            String amount,
            Long categoryId,
            String kind,
            boolean isUserCategorized,
            boolean isExcludedFromTotals,
            String notes,
            Long transferPairId,
            Long importBatchId,
            Long matchedRuleId,
            List<TagResponse> tags,
            TransactionSplitResponse split) {
    }

    record TagResponse(long id, String name, String color) {
    }

    record TransactionSplitResponse(long transactionId, String groupName, String personalShare, String notes) {
    }

    record CategoryInfo(long id, String kind) {
    }

    record ColumnValue(String column, Object value) {
    }

}
