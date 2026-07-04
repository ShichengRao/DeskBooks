package com.deskbooks.backend.rules;

import java.time.LocalDate;

public record RuleProposalExample(
        long transactionId,
        LocalDate date,
        String description,
        String amount,
        Long categoryId,
        String kind,
        boolean correct) {
}
