package com.deskbooks.backend.networth;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class NetWorthSeries {
    private static final String TAX_ADVANTAGED = "tax_advantaged";

    List<NetWorthController.NetWorthSeriesPointResponse> list(
            Connection connection,
            LocalDate start,
            LocalDate end) throws SQLException {
        NetWorthSeriesQuery query = query(start, end);
        List<NetWorthController.NetWorthSeriesPointResponse> points = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(query.sql())) {
            for (int i = 0; i < query.params().size(); i++) {
                statement.setString(i + 1, query.params().get(i).toString());
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    points.add(point(connection, rs.getLong("id"), LocalDate.parse(rs.getString("snapshot_date"))));
                }
            }
        }
        return points;
    }

    private NetWorthSeriesQuery query(LocalDate start, LocalDate end) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, snapshot_date
                FROM net_worth_snapshots
                """);
        List<LocalDate> params = new ArrayList<>();
        if (start != null || end != null) {
            sql.append(" WHERE ");
            List<String> filters = new ArrayList<>();
            if (start != null) {
                filters.add("snapshot_date >= ?");
                params.add(start);
            }
            if (end != null) {
                filters.add("snapshot_date <= ?");
                params.add(end);
            }
            sql.append(String.join(" AND ", filters));
        }
        sql.append(" ORDER BY snapshot_date ASC");
        return new NetWorthSeriesQuery(sql.toString(), params);
    }

    private NetWorthController.NetWorthSeriesPointResponse point(
            Connection connection,
            long snapshotId,
            LocalDate snapshotDate) throws SQLException {
        NetWorthSeriesTotals totals = totals(connection, snapshotId);
        return new NetWorthController.NetWorthSeriesPointResponse(
                snapshotDate,
                NetWorthMoney.format(totals.total()),
                NetWorthMoney.stringify(totals.byCategory()),
                NetWorthMoney.stringify(totals.byAccount()),
                NetWorthMoney.format(totals.taxable()),
                NetWorthMoney.format(totals.taxAdvantaged()));
    }

    private NetWorthSeriesTotals totals(Connection connection, long snapshotId) throws SQLException {
        Map<String, BigDecimal> byCategory = new LinkedHashMap<>();
        Map<String, BigDecimal> byAccount = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal taxable = BigDecimal.ZERO;
        BigDecimal taxAdvantaged = BigDecimal.ZERO;

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.name, a.account_category, ab.balance
                FROM account_balances ab
                JOIN accounts a ON a.id = ab.account_id
                WHERE ab.snapshot_id = ?
                ORDER BY a.sort_order, a.name
                """)) {
            statement.setLong(1, snapshotId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    BigDecimal rawBalance = rs.getBigDecimal("balance");
                    if (rawBalance == null) {
                        continue;
                    }
                    String category = rs.getString("account_category");
                    BigDecimal value = NetWorthMoney.signedBalance(category, rawBalance);
                    byCategory.merge(category, value, BigDecimal::add);
                    byAccount.merge(rs.getString("name"), value, BigDecimal::add);
                    total = total.add(value);
                    if (TAX_ADVANTAGED.equals(category)) {
                        taxAdvantaged = taxAdvantaged.add(value);
                    } else {
                        taxable = taxable.add(value);
                    }
                }
            }
        }

        return new NetWorthSeriesTotals(byCategory, byAccount, total, taxable, taxAdvantaged);
    }
}

record NetWorthSeriesQuery(String sql, List<LocalDate> params) {
}

record NetWorthSeriesTotals(
        Map<String, BigDecimal> byCategory,
        Map<String, BigDecimal> byAccount,
        BigDecimal total,
        BigDecimal taxable,
        BigDecimal taxAdvantaged) {
}
