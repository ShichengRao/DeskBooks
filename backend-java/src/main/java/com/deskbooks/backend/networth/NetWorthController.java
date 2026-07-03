package com.deskbooks.backend.networth;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/snapshots")
class NetWorthController {
    private static final DataFormatter EXCEL_FORMATTER = new DataFormatter();

    private final SqliteConnectionProvider connections;

    NetWorthController(SqliteConnectionProvider connections) {
        this.connections = connections;
    }

    @GetMapping("")
    List<NetWorthSnapshotResponse> listSnapshots() {
        try (Connection connection = connections.open()) {
            List<NetWorthSnapshotResponse> snapshots = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT id, snapshot_date, notes
                    FROM net_worth_snapshots
                    ORDER BY snapshot_date DESC
                    """);
                    ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    snapshots.add(snapshotFrom(connection, rs));
                }
            }
            return snapshots;
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("")
    NetWorthSnapshotResponse createSnapshot(@Valid @RequestBody NetWorthSnapshotRequest body) {
        try (Connection connection = connections.open()) {
            if (snapshotExists(connection, body.snapshotDate())) {
                throw new ApiException(HttpStatus.CONFLICT, "snapshot for this date already exists");
            }

            try {
                connection.setAutoCommit(false);
                long snapshotId;
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO net_worth_snapshots (snapshot_date, notes)
                        VALUES (?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, body.snapshotDate().toString());
                    statement.setString(2, body.notes());
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        keys.next();
                        snapshotId = keys.getLong(1);
                    }
                }
                upsertBalances(connection, snapshotId, balancesOrEmpty(body.balances()));
                connection.commit();
                return getSnapshot(connection, snapshotId);
            } catch (SQLException exception) {
                rollback(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("/import-workbook")
    NetWorthWorkbookImportResult importWorkbook(@Valid @RequestBody NetWorthWorkbookImportRequest body) {
        Path path = expandUser(body.path()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "file not found");
        }

        try (Connection connection = connections.open();
                InputStream input = Files.newInputStream(path);
                Workbook workbook = WorkbookFactory.create(input)) {
            Sheet datesSheet = workbook.getSheet("Dates");
            if (datesSheet == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "workbook is missing a Dates sheet");
            }

            Map<String, Long> accountByName = accountIdsByName(connection);
            WorkbookMapping mapping = mappedWorkbookRows(workbook, body.accountMap(), accountByName);
            if (!mapping.missingAccounts().isEmpty()) {
                return new NetWorthWorkbookImportResult(0, 0, mapping.missingAccounts());
            }

            Set<LocalDate> existingDates = snapshotDates(connection);
            int imported = 0;
            int skipped = 0;
            try {
                connection.setAutoCommit(false);
                Row dateRow = datesSheet.getRow(0);
                short lastCell = dateRow == null ? -1 : dateRow.getLastCellNum();
                for (int column = 1; column < lastCell; column++) {
                    LocalDate snapshotDate = cellDate(dateRow.getCell(column));
                    if (snapshotDate == null) {
                        continue;
                    }
                    if (existingDates.contains(snapshotDate)) {
                        skipped++;
                        continue;
                    }
                    long snapshotId = insertSnapshot(
                            connection,
                            snapshotDate,
                            "Imported from %s".formatted(path.getFileName().toString()));
                    for (WorkbookAccountRow row : mapping.rows()) {
                        Row sheetRow = row.sheet().getRow(row.rowIndex());
                        BigDecimal value = cellDecimal(sheetRow == null ? null : sheetRow.getCell(column));
                        if (value != null) {
                            insertBalance(connection, snapshotId, row.accountId(), value);
                        }
                    }
                    existingDates.add(snapshotDate);
                    imported++;
                }
                connection.commit();
                return new NetWorthWorkbookImportResult(imported, skipped, List.of());
            } catch (SQLException | RuntimeException exception) {
                rollback(connection);
                throw exception;
            }
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "could not read workbook");
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PatchMapping("/{snapshotId}")
    NetWorthSnapshotResponse updateSnapshot(@PathVariable long snapshotId, @RequestBody JsonNode body) {
        try (Connection connection = connections.open()) {
            requireSnapshot(connection, snapshotId);
            try {
                connection.setAutoCommit(false);
                if (body.has("snapshot_date") && !body.get("snapshot_date").isNull()) {
                    LocalDate snapshotDate = LocalDate.parse(body.get("snapshot_date").asText());
                    if (snapshotExistsForDifferentId(connection, snapshotDate, snapshotId)) {
                        throw new ApiException(HttpStatus.CONFLICT, "snapshot for this date already exists");
                    }
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE net_worth_snapshots SET snapshot_date = ? WHERE id = ?
                            """)) {
                        statement.setString(1, snapshotDate.toString());
                        statement.setLong(2, snapshotId);
                        statement.executeUpdate();
                    }
                }
                if (body.has("notes") && !body.get("notes").isNull()) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            UPDATE net_worth_snapshots SET notes = ? WHERE id = ?
                            """)) {
                        statement.setString(1, body.get("notes").asText());
                        statement.setLong(2, snapshotId);
                        statement.executeUpdate();
                    }
                }
                if (body.has("balances") && !body.get("balances").isNull()) {
                    replaceBalances(connection, snapshotId, balancesFromJson(body.get("balances")));
                }
                connection.commit();
                return getSnapshot(connection, snapshotId);
            } catch (SQLException | RuntimeException exception) {
                rollback(connection);
                throw exception;
            }
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @DeleteMapping("/{snapshotId}")
    Map<String, String> deleteSnapshot(@PathVariable long snapshotId) {
        try (Connection connection = connections.open()) {
            requireSnapshot(connection, snapshotId);
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM net_worth_snapshots WHERE id = ?")) {
                statement.setLong(1, snapshotId);
                statement.executeUpdate();
            }
            return Map.of("status", "deleted");
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/series")
    List<NetWorthSeriesPointResponse> series(
            @RequestParam(name = "start", required = false) LocalDate start,
            @RequestParam(name = "end", required = false) LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "end must be on or after start");
        }

        try (Connection connection = connections.open()) {
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

            List<NetWorthSeriesPointResponse> points = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    statement.setString(i + 1, params.get(i).toString());
                }
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        points.add(seriesPoint(connection, rs.getLong("id"), LocalDate.parse(rs.getString("snapshot_date"))));
                    }
                }
            }
            return points;
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private NetWorthSnapshotResponse getSnapshot(Connection connection, long snapshotId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, snapshot_date, notes FROM net_worth_snapshots WHERE id = ?
                """)) {
            statement.setLong(1, snapshotId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "snapshot not found");
                }
                return snapshotFrom(connection, rs);
            }
        }
    }

    private NetWorthSnapshotResponse snapshotFrom(Connection connection, ResultSet rs) throws SQLException {
        long snapshotId = rs.getLong("id");
        return new NetWorthSnapshotResponse(
                snapshotId,
                LocalDate.parse(rs.getString("snapshot_date")),
                rs.getString("notes"),
                balances(connection, snapshotId));
    }

    private List<AccountBalanceResponse> balances(Connection connection, long snapshotId) throws SQLException {
        List<AccountBalanceResponse> balances = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT account_id, balance, notes
                FROM account_balances
                WHERE snapshot_id = ?
                ORDER BY account_id
                """)) {
            statement.setLong(1, snapshotId);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    BigDecimal balance = rs.getBigDecimal("balance");
                    balances.add(new AccountBalanceResponse(
                            rs.getLong("account_id"),
                            balance == null ? null : moneyString(balance),
                            rs.getString("notes")));
                }
            }
        }
        return balances;
    }

    private NetWorthSeriesPointResponse seriesPoint(Connection connection, long snapshotId, LocalDate snapshotDate) throws SQLException {
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
                    BigDecimal value = signedBalance(category, rawBalance);
                    byCategory.merge(category, value, BigDecimal::add);
                    byAccount.merge(rs.getString("name"), value, BigDecimal::add);
                    total = total.add(value);
                    if ("tax_advantaged".equals(category)) {
                        taxAdvantaged = taxAdvantaged.add(value);
                    } else {
                        taxable = taxable.add(value);
                    }
                }
            }
        }

        return new NetWorthSeriesPointResponse(
                snapshotDate,
                moneyString(total),
                stringifyMoney(byCategory),
                stringifyMoney(byAccount),
                moneyString(taxable),
                moneyString(taxAdvantaged));
    }

    private void upsertBalances(Connection connection, long snapshotId, List<AccountBalanceRequest> balances) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO account_balances (snapshot_id, account_id, balance, notes)
                VALUES (?, ?, ?, ?)
                """)) {
            for (AccountBalanceRequest balance : balances) {
                statement.setLong(1, snapshotId);
                statement.setLong(2, balance.accountId());
                statement.setBigDecimal(3, balance.balance());
                statement.setString(4, balance.notes());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void replaceBalances(Connection connection, long snapshotId, List<AccountBalanceRequest> balances) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM account_balances WHERE snapshot_id = ?
                """)) {
            statement.setLong(1, snapshotId);
            statement.executeUpdate();
        }
        upsertBalances(connection, snapshotId, balances);
    }

    private List<AccountBalanceRequest> balancesFromJson(JsonNode balancesNode) {
        List<AccountBalanceRequest> balances = new ArrayList<>();
        Set<Long> seenAccountIds = new LinkedHashSet<>();
        for (JsonNode node : balancesNode) {
            long accountId = node.get("account_id").asLong();
            if (!seenAccountIds.add(accountId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "duplicate account balance in snapshot");
            }
            JsonNode balanceNode = node.get("balance");
            BigDecimal balance = balanceNode == null || balanceNode.isNull() ? null : new BigDecimal(balanceNode.asText());
            JsonNode notesNode = node.get("notes");
            String notes = notesNode == null || notesNode.isNull() ? null : notesNode.asText();
            balances.add(new AccountBalanceRequest(accountId, balance, notes));
        }
        return balances;
    }

    private boolean snapshotExists(Connection connection, LocalDate snapshotDate) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM net_worth_snapshots WHERE snapshot_date = ?
                """)) {
            statement.setString(1, snapshotDate.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean snapshotExistsForDifferentId(Connection connection, LocalDate snapshotDate, long snapshotId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM net_worth_snapshots WHERE snapshot_date = ? AND id <> ?
                """)) {
            statement.setString(1, snapshotDate.toString());
            statement.setLong(2, snapshotId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void requireSnapshot(Connection connection, long snapshotId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM net_worth_snapshots WHERE id = ?")) {
            statement.setLong(1, snapshotId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "snapshot not found");
                }
            }
        }
    }

    private Path expandUser(String rawPath) {
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

    private Map<String, Long> accountIdsByName(Connection connection) throws SQLException {
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

    private WorkbookMapping mappedWorkbookRows(
            Workbook workbook,
            Map<String, String> requestedMap,
            Map<String, Long> accountByName) {
        Map<String, String> accountMap = requestedMap == null ? Map.of() : requestedMap;
        if (!accountMap.isEmpty()) {
            List<WorkbookAccountRow> rows = new ArrayList<>();
            Set<String> missingAccounts = new LinkedHashSet<>();
            List<String> invalidKeys = new ArrayList<>();
            for (Map.Entry<String, String> entry : accountMap.entrySet()) {
                String accountName = entry.getValue();
                Long accountId = accountByName.get(accountName);
                if (accountId == null) {
                    missingAccounts.add(accountName);
                    continue;
                }
                String key = entry.getKey();
                int bang = key == null ? -1 : key.lastIndexOf('!');
                if (bang <= 0 || bang == key.length() - 1) {
                    invalidKeys.add(key);
                    continue;
                }
                String sheetName = key.substring(0, bang);
                Sheet sheet = workbook.getSheet(sheetName);
                if (sheet == null) {
                    invalidKeys.add(key);
                    continue;
                }
                try {
                    int oneBasedRow = Integer.parseInt(key.substring(bang + 1));
                    if (oneBasedRow < 1) {
                        invalidKeys.add(key);
                        continue;
                    }
                    rows.add(new WorkbookAccountRow(sheet, oneBasedRow - 1, accountId));
                } catch (NumberFormatException exception) {
                    invalidKeys.add(key);
                }
            }
            if (!invalidKeys.isEmpty()) {
                Collections.sort(invalidKeys);
                throw new ApiException(HttpStatus.BAD_REQUEST, "invalid account_map row key(s): " + String.join(", ", invalidKeys));
            }
            List<String> missing = new ArrayList<>(missingAccounts);
            Collections.sort(missing);
            return new WorkbookMapping(rows, missing);
        }

        List<WorkbookAccountRow> rows = new ArrayList<>();
        for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            if ("Dates".equals(sheet.getSheetName())) {
                continue;
            }
            for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                String label = cellString(row == null ? null : row.getCell(0)).trim();
                Long accountId = accountByName.get(label);
                if (accountId != null) {
                    rows.add(new WorkbookAccountRow(sheet, rowIndex, accountId));
                }
            }
        }
        if (rows.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "No account rows were found. Add a JSON account map using keys like \"Sheet name!12\" and account names as values.");
        }
        return new WorkbookMapping(rows, List.of());
    }

    private Set<LocalDate> snapshotDates(Connection connection) throws SQLException {
        Set<LocalDate> out = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT snapshot_date FROM net_worth_snapshots");
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                out.add(LocalDate.parse(rs.getString("snapshot_date")));
            }
        }
        return out;
    }

    private long insertSnapshot(Connection connection, LocalDate snapshotDate, String notes) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO net_worth_snapshots (snapshot_date, notes)
                VALUES (?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, snapshotDate.toString());
            statement.setString(2, notes);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private void insertBalance(Connection connection, long snapshotId, long accountId, BigDecimal balance) throws SQLException {
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

    private List<AccountBalanceRequest> balancesOrEmpty(List<AccountBalanceRequest> balances) {
        return balances == null ? List.of() : balances;
    }

    private BigDecimal signedBalance(String category, BigDecimal balance) {
        if ("credit".equals(category) || "liability".equals(category)) {
            return balance.abs().negate();
        }
        return balance;
    }

    private Map<String, String> stringifyMoney(Map<String, BigDecimal> values) {
        Map<String, String> out = new LinkedHashMap<>();
        values.forEach((key, value) -> out.put(key, moneyString(value)));
        return out;
    }

    private String moneyString(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private BigDecimal cellDecimal(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC || cell.getCellType() == CellType.FORMULA) {
            try {
                return BigDecimal.valueOf(cell.getNumericCellValue()).setScale(2, RoundingMode.HALF_UP);
            } catch (IllegalStateException ignored) {
                // Fall through to string parsing.
            }
        }
        String value = cellString(cell);
        if (value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private LocalDate cellDate(Cell cell) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC || cell.getCellType() == CellType.FORMULA) {
            try {
                if (DateUtil.isValidExcelDate(cell.getNumericCellValue())) {
                    return cell.getLocalDateTimeCellValue().toLocalDate();
                }
            } catch (RuntimeException ignored) {
                // Fall through to ISO string parsing.
            }
        }
        try {
            return LocalDate.parse(cellString(cell));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String cellString(Cell cell) {
        if (cell == null) {
            return "";
        }
        return EXCEL_FORMATTER.formatCellValue(cell).trim();
    }

    private void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The original exception carries the actionable failure.
        }
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }

    record NetWorthSnapshotRequest(
            @NotNull LocalDate snapshotDate,
            String notes,
            List<@Valid AccountBalanceRequest> balances) {
    }

    record AccountBalanceRequest(
            @NotNull Long accountId,
            BigDecimal balance,
            String notes) {
    }

    record NetWorthWorkbookImportRequest(
            @NotNull String path,
            Map<String, String> accountMap) {
    }

    record NetWorthWorkbookImportResult(
            int imported,
            int skippedExisting,
            List<String> missingAccounts) {
    }

    record NetWorthSnapshotResponse(
            long id,
            LocalDate snapshotDate,
            String notes,
            List<AccountBalanceResponse> balances) {
    }

    record AccountBalanceResponse(
            long accountId,
            String balance,
            String notes) {
    }

    record NetWorthSeriesPointResponse(
            LocalDate snapshotDate,
            String total,
            Map<String, String> byCategory,
            Map<String, String> byAccount,
            String taxable,
            String taxAdvantaged) {
    }

    private record WorkbookAccountRow(Sheet sheet, int rowIndex, long accountId) {
    }

    private record WorkbookMapping(List<WorkbookAccountRow> rows, List<String> missingAccounts) {
    }
}
