package com.deskbooks.backend.networth;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

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
