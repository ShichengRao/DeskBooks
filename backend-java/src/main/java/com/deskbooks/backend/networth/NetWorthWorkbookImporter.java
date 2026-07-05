package com.deskbooks.backend.networth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.deskbooks.backend.foundation.ApiException;
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
            return NetWorthWorkbookSnapshotImporter.importMappedSnapshots(connection, path, datesSheet, mapping);
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
}
