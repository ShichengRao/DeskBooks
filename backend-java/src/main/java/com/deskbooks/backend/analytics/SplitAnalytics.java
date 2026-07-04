package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class SplitAnalytics {
    private SplitAnalytics() {
    }

    static List<AnalyticsController.SplitGroupSummaryResponse> load(
            Connection connection,
            LocalDate start,
            LocalDate end) throws SQLException {
        Map<String, SplitAccumulator> groups = new TreeMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT s.group_name, s.personal_share, t.amount
                FROM transaction_splits s
                JOIN transactions t ON t.id = s.transaction_id
                WHERE t.date >= ?
                  AND t.date <= ?
                  AND t.is_excluded_from_totals = 0
                """)) {
            statement.setString(1, start.toString());
            statement.setString(2, end.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    groups.computeIfAbsent(rs.getString("group_name"), ignored -> new SplitAccumulator())
                            .add(rs.getBigDecimal("amount"), rs.getBigDecimal("personal_share"));
                }
            }
        }
        return responses(groups);
    }

    private static List<AnalyticsController.SplitGroupSummaryResponse> responses(
            Map<String, SplitAccumulator> groups) {
        List<AnalyticsController.SplitGroupSummaryResponse> out = new ArrayList<>();
        for (Map.Entry<String, SplitAccumulator> entry : groups.entrySet()) {
            out.add(entry.getValue().response(entry.getKey()));
        }
        return out;
    }

    private static String moneyString(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static final class SplitAccumulator {
        private BigDecimal sharedOutflows = BigDecimal.ZERO;
        private BigDecimal personalOutflows = BigDecimal.ZERO;
        private BigDecimal expectedReimbursement = BigDecimal.ZERO;
        private BigDecimal receivedReimbursement = BigDecimal.ZERO;
        private int transactionCount = 0;

        void add(BigDecimal amount, BigDecimal share) {
            transactionCount++;
            if (amount.compareTo(BigDecimal.ZERO) < 0) {
                addOutflow(amount.negate(), share);
            } else if (amount.compareTo(BigDecimal.ZERO) > 0) {
                receivedReimbursement = receivedReimbursement.add(amount);
            }
        }

        private void addOutflow(BigDecimal fullOutflow, BigDecimal share) {
            BigDecimal personal = fullOutflow.multiply(share);
            sharedOutflows = sharedOutflows.add(fullOutflow);
            personalOutflows = personalOutflows.add(personal);
            expectedReimbursement = expectedReimbursement.add(fullOutflow.subtract(personal));
        }

        private AnalyticsController.SplitGroupSummaryResponse response(String groupName) {
            return new AnalyticsController.SplitGroupSummaryResponse(
                    groupName,
                    moneyString(sharedOutflows),
                    moneyString(personalOutflows),
                    moneyString(expectedReimbursement),
                    moneyString(receivedReimbursement),
                    moneyString(expectedReimbursement.subtract(receivedReimbursement)),
                    transactionCount);
        }
    }
}
