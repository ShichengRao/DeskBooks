package com.deskbooks.backend.planning;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

final class GoalProgressCalculator {
    private final GoalStore goals;

    GoalProgressCalculator(GoalStore goals) {
        this.goals = goals;
    }

    GoalController.GoalProgressResponse progress(Connection connection, long goalId) throws SQLException {
        GoalController.GoalResponse goal = goals.get(connection, goalId);
        if (goal.linkedAccountIds() == null || goal.linkedAccountIds().isEmpty()) {
            return emptyProgress(goal);
        }
        try (PreparedStatement latest = connection.prepareStatement("""
                SELECT id, snapshot_date FROM net_worth_snapshots ORDER BY snapshot_date DESC LIMIT 1
                """);
                ResultSet latestRs = latest.executeQuery()) {
            if (!latestRs.next()) {
                return emptyProgress(goal);
            }
            BigDecimal current = currentBalance(connection, latestRs.getLong("id"), goal);
            BigDecimal target = targetAmount(goal);
            return new GoalController.GoalProgressResponse(
                    current,
                    target,
                    percent(current, target),
                    LocalDate.parse(latestRs.getString("snapshot_date")));
        }
    }

    private GoalController.GoalProgressResponse emptyProgress(GoalController.GoalResponse goal) {
        return new GoalController.GoalProgressResponse(null, targetAmount(goal), null, null);
    }

    private BigDecimal currentBalance(
            Connection connection,
            long snapshotId,
            GoalController.GoalResponse goal) throws SQLException {
        BigDecimal current = BigDecimal.ZERO;
        try (PreparedStatement balances = connection.prepareStatement("""
                SELECT balance FROM account_balances WHERE snapshot_id = ? AND account_id = ?
                """)) {
            for (Long accountId : goal.linkedAccountIds()) {
                balances.setLong(1, snapshotId);
                balances.setLong(2, accountId);
                try (ResultSet rs = balances.executeQuery()) {
                    BigDecimal balance = rs.next() ? rs.getBigDecimal("balance") : null;
                    if (balance != null) {
                        current = current.add(balance);
                    }
                }
            }
        }
        return current;
    }

    private BigDecimal targetAmount(GoalController.GoalResponse goal) {
        return goal.targetAmount() == null ? null : new BigDecimal(goal.targetAmount());
    }

    private Double percent(BigDecimal current, BigDecimal targetAmount) {
        if (targetAmount == null || targetAmount.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return current.divide(targetAmount, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .doubleValue();
    }
}
