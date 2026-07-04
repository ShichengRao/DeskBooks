package com.deskbooks.backend.rules;

import java.util.List;

public record RuleProposal(
        String key,
        String name,
        String matchDescriptionPattern,
        Long matchAccountId,
        Long setCategoryId,
        String setKind,
        String setMerchant,
        int support,
        int totalUserLabeledMatches,
        int allTransactionMatches,
        int addedTransactionMatches,
        int correctMatches,
        int incorrectMatches,
        double accuracy,
        double labeledCoveragePercent,
        double allCoveragePercent,
        double addedCoveragePercent,
        List<RuleProposalBreakdown> breakdown,
        List<RuleProposalExample> examples) {
}
