package com.deskbooks.backend.rules;

import java.time.LocalDateTime;
import java.util.List;

public record RuleRecord(
        long id,
        String name,
        int priority,
        boolean isActive,
        Long matchAccountId,
        String matchDescriptionPattern,
        String matchAmountMin,
        String matchAmountMax,
        Long setCategoryId,
        String setKind,
        String setMerchant,
        List<String> setTags,
        String notes,
        int applyCount,
        LocalDateTime lastAppliedAt) {
}
