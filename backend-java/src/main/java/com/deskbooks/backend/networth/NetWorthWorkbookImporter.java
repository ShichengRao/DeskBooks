package com.deskbooks.backend.networth;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.deskbooks.backend.foundation.ApiException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;

final class NetWorthWorkbookImporter {
    private NetWorthWorkbookImporter() {
    }

    static NetWorthWorkbookImportResult importWorkbook(
            Connection connection,
            NetWorthWorkbookImportRequest body) throws IOException, SQLException {
        Path path = expandUser(body.path()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "file not found");
        }

        try (InputStream input = Files.newInputStream(path);
                Workbook workbook = WorkbookFactory.create(input)) {
            Sheet datesSheet = workbook.getSheet("Dates");
            if (datesSheet == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "workbook is missing a Dates sheet");
            }

            NetWorthWorkbookMapping mapping = NetWorthWorkbookMapper.mapRows(
                    workbook,
                    body.accountMap(),
                    accountIdsByName(connection));
            if (!mapping.missingAccounts().isEmpty()) {
                return new NetWorthWorkbookImportResult(0, 0, mapping.missingAccounts());
            }
            return importMappedSnapshots(connection, path, datesSheet, mapping);
        }
    }

    private static NetWorthWorkbookImportResult importMappedSnapshots(
            Connection connection,
            Path path,
            Sheet datesSheet,
            NetWorthWorkbookMapping mapping) throws SQLException {
        Set<LocalDate> existingDates = snapshotDates(connection);
        int imported = 0;
        int skipped = 0;
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
            return new NetWorthWorkbookImportResult(imported, skipped, List.of());
        } catch (SQLException | RuntimeException exception) {
            rollback(connection);
            throw exception;
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

    private static Map<String, Long> accountIdsByName(Connection connection) throws SQLException {
        Map<String, Long> out = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, name
                FROM accounts
                """);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString("name"), rs.getLong("id"));
            }
        }
        return out;
    }

    private static Set<LocalDate> snapshotDates(Connection connection) throws SQLException {
        Set<LocalDate> out = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT snapshot_date FROM net_worth_snapshots");
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                out.add(LocalDate.parse(rs.getString("snapshot_date")));
            }
        }
        return out;
    }

    private static void insertBalance(Connection connection, long snapshotId, long accountId, BigDecimal balance) throws SQLException {
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

    private static Path expandUser(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "file not found");
        }
        if (rawPath.equals("~")) {
            return Path.of(System.getProperty("user.home"));
        }
        if (rawPath.startsWith("~/")) {
            return Path.of(System.getProperty("user.home"), rawPath.substring(2));
        }
        return Path.of(rawPath);
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original exception carries the actionable failure.
        }
    }
}
