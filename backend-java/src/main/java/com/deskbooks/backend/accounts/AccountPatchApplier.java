package com.deskbooks.backend.accounts;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.StringJoiner;

final class AccountPatchApplier {
    void apply(Connection connection, long accountId, List<AccountPatchValue> values) throws SQLException {
        String sql = "UPDATE accounts SET " + assignments(values) + " WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (AccountPatchValue value : values) {
                statement.setObject(index++, value.value());
            }
            statement.setLong(index, accountId);
            statement.executeUpdate();
        }
    }

    private String assignments(List<AccountPatchValue> values) {
        StringJoiner assignments = new StringJoiner(", ");
        for (AccountPatchValue value : values) {
            assignments.add(value.column() + " = ?");
        }
        return assignments.toString();
    }
}
