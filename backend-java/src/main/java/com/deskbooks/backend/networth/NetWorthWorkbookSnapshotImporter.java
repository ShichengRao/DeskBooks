package com.deskbooks.backend.networth;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;

final class NetWorthWorkbookSnapshotImporter {
    private NetWorthWorkbookSnapshotImporter() {
    }

    static NetWorthWorkbookImportResult importMappedSnapshots(
            Connection connection,
            Path path,
            Sheet datesSheet,
            NetWorthWorkbookMapping mapping) throws SQLException {
        Set<LocalDate> existingDates = NetWorthSnapshotStore.dates(connection);
        int imported = 0;
        int skipped = 0;
        boolean committed = false;
        try {
            connection.setAutoCommit(false);
            Row dateRow = datesSheet.getRow(0);
            short lastCell = dateRow == null ? -1 : dateRow.getLastCellNum();
            for (int column = 1; column < lastCell; column++) {
                LocalDate snapshotDate = NetWorthWorkbookCells.date(dateRow.getCell(column));
                if (snapshotDate == null) {
                    continue;
                }
                if (existingDates.contains(snapshotDate)) {
                    skipped++;
                    continue;
                }
                long snapshotId = NetWorthSnapshotStore.insert(
                        connection,
                        snapshotDate,
                        "Imported from %s".formatted(path.getFileName().toString()));
                insertMappedBalances(connection, snapshotId, column, mapping.rows());
                existingDates.add(snapshotDate);
                imported++;
            }
            connection.commit();
            committed = true;
            return new NetWorthWorkbookImportResult(imported, skipped, List.of());
        } finally {
            if (!committed) {
                rollback(connection);
            }
        }
    }

    private static void insertMappedBalances(
            Connection connection,
            long snapshotId,
            int column,
            List<NetWorthWorkbookRow> rows) throws SQLException {
        for (NetWorthWorkbookRow row : rows) {
            Row sheetRow = row.sheet().getRow(row.rowIndex());
            BigDecimal value = NetWorthWorkbookCells.decimal(sheetRow == null ? null : sheetRow.getCell(column));
            if (value != null) {
                insertBalance(connection, snapshotId, row.accountId(), value);
            }
        }
    }

    private static void insertBalance(
            Connection connection,
            long snapshotId,
            long accountId,
            BigDecimal balance) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO account_balances (snapshot_id, account_id, balance)
                VALUES (?, ?, ?)
                """)) {
            statement.setLong(1, snapshotId);
            statement.setLong(2, accountId);
            statement.setBigDecimal(3, balance);
            statement.executeUpdate();
        }
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original exception carries the actionable failure.
        }
    }
}
