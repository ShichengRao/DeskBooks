package com.deskbooks.backend.analytics;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class SankeyAccounts {
    private static final String BANK_CATEGORY = "bank";
    private static final String CREDIT_CATEGORY = "credit";
    private static final String LIABILITY_CATEGORY = "liability";
    private static final String INVESTMENT_CATEGORY = "investment";
    private static final String TAX_ADVANTAGED_CATEGORY = "tax_advantaged";
    private static final String CHECKING_TYPE = "checking";
    private static final String SAVINGS_TYPE = "savings";
    private static final String CD_TYPE = "cd";

    List<AccountRow> accounts(Connection connection) throws SQLException {
        List<AccountRow> out = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, name, account_category, type
                FROM accounts
                """);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                out.add(new AccountRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        rs.getString("account_category"),
                        rs.getString("type")));
            }
        }
        return out;
    }

    boolean excludedFromDelta(AccountRow account) {
        return CREDIT_CATEGORY.equals(account.accountCategory()) || LIABILITY_CATEGORY.equals(account.accountCategory());
    }

    String growthBucket(AccountRow account) {
        String name = account.name() == null ? "" : account.name().toLowerCase(Locale.ROOT);
        if (CD_TYPE.equals(account.type())) {
            return "CD Interest";
        }
        if (name.contains("bond")) {
            return "Bond Payments";
        }
        if (isInvestmentLike(account)) {
            return "Stock Growth";
        }
        if (CHECKING_TYPE.equals(account.type()) || SAVINGS_TYPE.equals(account.type())) {
            return "Bank Interest";
        }
        return "Other growth";
    }

    String deltaBucket(AccountRow account) {
        String name = account.name() == null ? "" : account.name().toLowerCase(Locale.ROOT);
        if (name.contains("bond")) {
            return "Bond Account";
        }
        if (isInvestmentLike(account)) {
            return "Stock Account";
        }
        if (BANK_CATEGORY.equals(account.accountCategory())) {
            return "CDs + Bank Accounts";
        }
        return "Other Accounts";
    }

    private boolean isInvestmentLike(AccountRow account) {
        return INVESTMENT_CATEGORY.equals(account.accountCategory())
                || TAX_ADVANTAGED_CATEGORY.equals(account.accountCategory());
    }
}

record AccountRow(long id, String name, String accountCategory, String type) {
}
