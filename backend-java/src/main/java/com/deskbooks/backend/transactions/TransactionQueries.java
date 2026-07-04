package com.deskbooks.backend.transactions;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class TransactionQueries {
    private final TransactionReader reader;

    TransactionQueries(TransactionReader reader) {
        this.reader = reader;
    }

    List<TransactionResponse> list(Connection connection, TransactionQueryRequest query) throws SQLException {
        TransactionFilters.FilterSql filters = query.filters();
        String sql = "SELECT " + reader.selectColumns()
                + " FROM transactions t"
                + accountJoin(filters)
                + filters.whereSql()
                + " ORDER BY t.date DESC, t.id DESC LIMIT ? OFFSET ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = TransactionSql.bindParams(statement, filters.params(), 1);
            statement.setInt(index++, query.pageLimit());
            statement.setInt(index, query.pageOffset());
            try (ResultSet rs = statement.executeQuery()) {
                List<TransactionResponse> transactions = new ArrayList<>();
                while (rs.next()) {
                    transactions.add(reader.from(connection, rs));
                }
                return transactions;
            }
        }
    }

    Map<String, Long> count(Connection connection, TransactionQueryRequest query) throws SQLException {
        TransactionFilters.FilterSql filters = query.filters();
        String sql = "SELECT COUNT(t.id) AS count FROM transactions t"
                + accountJoin(filters)
                + filters.whereSql();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            TransactionSql.bindParams(statement, filters.params(), 1);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return Map.of("count", rs.getLong("count"));
            }
        }
    }

    private String accountJoin(TransactionFilters.FilterSql filters) {
        return filters.joinAccounts() ? " JOIN accounts a ON a.id = t.account_id" : "";
    }
}
