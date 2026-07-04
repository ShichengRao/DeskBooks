package com.deskbooks.backend.rules;

public record RuleProposalRequest(
        String key,
        String name,
        String matchDescriptionPattern,
        Long matchAccountId,
        Long setCategoryId,
        String setKind,
        String setMerchant) {
}
