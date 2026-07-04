package com.deskbooks.backend.rules;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class RuleEngine {
    private final RuleMatcher matcher = new RuleMatcher();
    private final RuleRepository repository = new RuleRepository();
    private final RuleFireStamper fireStamper = new RuleFireStamper();
    private final RuleProposalEngine proposals = new RuleProposalEngine(matcher, this);
    private final RuleReapplyEngine reapply = new RuleReapplyEngine(this);
    private final RuleCoverageReporter coverage = new RuleCoverageReporter(this);

    public List<RuleRecord> loadActiveRules(Connection connection) throws SQLException {
        return repository.loadActive(connection);
    }

    public RuleEval evaluate(List<RuleRecord> rules, long accountId, String description, BigDecimal amount) {
        for (RuleRecord rule : rules) {
            if (matcher.matches(rule, accountId, description, amount)) {
                return new RuleEval(
                        rule.setCategoryId(),
                        rule.setKind(),
                        rule.setMerchant(),
                        rule.setTags(),
                        rule.id());
            }
        }
        return RuleEval.empty();
    }

    public void stampRuleFires(Connection connection, List<Long> ruleIds) throws SQLException {
        fireStamper.stamp(connection, ruleIds);
    }

    ReapplyResult reapplyToUnreviewed(Connection connection) throws SQLException {
        return reapply.reapplyToUnreviewed(connection);
    }

    RuleCoverage coverageSummary(Connection connection) throws SQLException {
        return coverage.summarize(connection);
    }

    List<RuleProposal> generateRuleProposals(Connection connection, int minSupport, int limit) throws SQLException {
        return proposals.generate(connection, minSupport, limit);
    }

    RuleProposal backtestRuleProposal(Connection connection, RuleProposalRequest request) throws SQLException {
        return proposals.backtest(connection, request);
    }

    boolean rejectRuleProposal(Connection connection, RuleProposalRequest request) throws SQLException {
        return proposals.reject(connection, request);
    }
}
