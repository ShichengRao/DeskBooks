package com.deskbooks.backend.rules;

import java.util.List;

record RuleProposalContext(
        List<RuleTransactionRow> labeledTxs,
        List<RuleTransactionRow> allTxs,
        int totalLabeled,
        int totalTransactions,
        List<RuleProposalSignature> activeSignatures,
        List<RuleRecord> activeRules,
        List<String> rejectedSignatures,
        int minSupport) {
}
