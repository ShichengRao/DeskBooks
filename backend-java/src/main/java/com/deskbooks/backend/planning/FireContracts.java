package com.deskbooks.backend.planning;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotNull;

record FireSettingsRequest(
        @NotNull BigDecimal growthBank,
        @NotNull BigDecimal growthInvestment,
        @NotNull BigDecimal growthTaxAdvantaged,
        @NotNull BigDecimal growthNonsense,
        @NotNull BigDecimal growthCash,
        @NotNull BigDecimal growthCredit,
        @NotNull BigDecimal annualRetirementSpending,
        @NotNull BigDecimal withdrawalRate) {
}

record FireSettingsResponse(
        String growthBank,
        String growthInvestment,
        String growthTaxAdvantaged,
        String growthNonsense,
        String growthCash,
        String growthCredit,
        String annualRetirementSpending,
        String withdrawalRate,
        LocalDateTime updatedAt) {
}

record FireProjectionYearResponse(
        int year,
        Integer age,
        String total,
        Map<String, String> byCategory,
        double pctOfTarget) {
}

record FireProjectionResponse(
        String targetTotal,
        String currentTotal,
        Map<String, String> currentByCategory,
        Integer retirementYear,
        List<FireProjectionYearResponse> years,
        List<String> notes) {
}
