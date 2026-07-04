package com.deskbooks.backend.transactions;

import java.time.LocalDate;
import java.util.List;

record TransactionResponse(
        long id,
        long accountId,
        LocalDate date,
        LocalDate postDate,
        String descriptionRaw,
        String descriptionNormalized,
        String merchant,
        String amount,
        Long categoryId,
        String kind,
        boolean isUserCategorized,
        boolean isExcludedFromTotals,
        String notes,
        Long transferPairId,
        Long importBatchId,
        Long matchedRuleId,
        List<TagResponse> tags,
        TransactionSplitResponse split) {
}

record TagResponse(long id, String name, String color) {
}

record TransactionSplitResponse(long transactionId, String groupName, String personalShare, String notes) {
}
