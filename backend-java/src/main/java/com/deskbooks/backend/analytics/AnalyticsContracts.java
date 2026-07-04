package com.deskbooks.backend.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotNull;

record ReconcileRequest(
        @NotNull Long accountId,
        int year,
        int month,
        BigDecimal statementTotal,
        String notes) {
}

record MonthlyPointResponse(
        String month,
        Map<String, BigDecimal> byKind,
        Map<String, BigDecimal> byExpenseCategory,
        Map<String, BigDecimal> byIncomeCategory,
        BigDecimal expensesTotal,
        BigDecimal incomeTotal,
        BigDecimal donationsTotal,
        BigDecimal taxesTotal,
        BigDecimal net) {
}

record RecurringMerchantResponse(
        String merchant,
        int occurrences,
        String avgAmount,
        String totalAmount,
        LocalDate lastSeen,
        Double cadenceDaysEstimate) {
}

record SankeyResponse(
        int year,
        String label,
        List<SankeyNodeResponse> nodes,
        List<SankeyLinkResponse> links,
        List<String> notes) {
}

record SankeyNodeResponse(String name) {
}

record SankeyLinkResponse(int source, int target, double value, String label) {
}

record ReconcileResponse(
        long accountId,
        Integer year,
        Integer month,
        LocalDate start,
        LocalDate end,
        int transactionCount,
        String importedTotal,
        String importedInflows,
        String importedOutflows,
        Map<String, String> byKind,
        String statementTotal,
        String statementNotes,
        String delta) {
}

record SplitGroupSummaryResponse(
        String groupName,
        String sharedOutflows,
        String personalOutflows,
        String expectedReimbursement,
        String receivedReimbursement,
        String remainingOwed,
        int transactionCount) {
}
