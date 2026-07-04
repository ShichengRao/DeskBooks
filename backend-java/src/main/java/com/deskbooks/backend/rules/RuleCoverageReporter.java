package com.deskbooks.backend.rules;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

final class RuleCoverageReporter {
    private final RuleEngine rules;
    private final RuleTransactionReader transactions = new RuleTransactionReader();

    RuleCoverageReporter(RuleEngine rules) {
        this.rules = rules;
    }

    RuleEngine.RuleCoverage summarize(Connection connection) throws SQLException {
        List<RuleEngine.RuleRecord> activeRules = rules.loadActiveRules(connection);
        List<RuleTransactionRow> txs = transactions.load(connection, false);
        int total = txs.size();
        int labeledTransactions = 0;
        int matched = 0;
        int labeledMatched = 0;
        int labeledCorrect = 0;
        int labeledIncorrect = 0;
        for (RuleTransactionRow tx : txs) {
            boolean labeled = tx.categoryId() != null && !"uncategorized".equals(tx.kind());
            if (labeled) {
                labeledTransactions++;
            }
            RuleEngine.RuleEval eval = rules.evaluate(activeRules, tx.accountId(), tx.description(), tx.amount());
            if (!eval.matched()) {
                continue;
            }
            matched++;
            if (labeled) {
                labeledMatched++;
                boolean categoryOk = eval.categoryId() == null || eval.categoryId().equals(tx.categoryId());
                boolean kindOk = eval.kind() == null || eval.kind().equals(tx.kind());
                if (categoryOk && kindOk) {
                    labeledCorrect++;
                } else {
                    labeledIncorrect++;
                }
            }
        }
        Double accuracy = labeledMatched == 0 ? null : ((double) labeledCorrect) / labeledMatched;
        double coverage = total == 0 ? 0.0 : ((double) matched) / total * 100.0;
        return new RuleEngine.RuleCoverage(
                activeRules.size(),
                total,
                matched,
                coverage,
                labeledTransactions,
                labeledMatched,
                labeledCorrect,
                labeledIncorrect,
                accuracy);
    }
}
