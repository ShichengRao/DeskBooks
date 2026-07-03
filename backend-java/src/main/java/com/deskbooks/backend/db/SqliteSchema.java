package com.deskbooks.backend.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.springframework.stereotype.Component;

@Component
class SqliteSchema {
    void ensure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS accounts (
                      id INTEGER NOT NULL PRIMARY KEY,
                      name VARCHAR(120) NOT NULL UNIQUE,
                      institution VARCHAR(120),
                      account_category VARCHAR(32) NOT NULL,
                      type VARCHAR(32) NOT NULL,
                      currency VARCHAR(3) NOT NULL DEFAULT 'USD',
                      sign_convention VARCHAR(32) NOT NULL DEFAULT 'outflow_negative',
                      url TEXT,
                      notes TEXT,
                      is_closed BOOLEAN NOT NULL DEFAULT 0,
                      opened_at DATE,
                      closed_at DATE,
                      sort_order INTEGER NOT NULL DEFAULT 0,
                      created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS categories (
                      id INTEGER NOT NULL PRIMARY KEY,
                      name VARCHAR(120) NOT NULL UNIQUE,
                      parent_id INTEGER REFERENCES categories(id),
                      kind VARCHAR(32) NOT NULL,
                      color VARCHAR(16),
                      sort_order INTEGER NOT NULL DEFAULT 0,
                      archived BOOLEAN NOT NULL DEFAULT 0
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS import_batches (
                      id INTEGER NOT NULL PRIMARY KEY,
                      source_filename VARCHAR(255) NOT NULL,
                      importer_name VARCHAR(64) NOT NULL,
                      account_id INTEGER NOT NULL REFERENCES accounts(id),
                      imported_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                      row_count_total INTEGER NOT NULL DEFAULT 0,
                      row_count_applied INTEGER NOT NULL DEFAULT 0,
                      row_count_duplicate INTEGER NOT NULL DEFAULT 0,
                      status VARCHAR(32) NOT NULL DEFAULT 'preview',
                      notes TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS transactions (
                      id INTEGER NOT NULL PRIMARY KEY,
                      account_id INTEGER NOT NULL REFERENCES accounts(id),
                      date DATE NOT NULL DEFAULT '1970-01-01',
                      post_date DATE,
                      description_raw TEXT NOT NULL DEFAULT '',
                      description_normalized TEXT,
                      merchant VARCHAR(255),
                      amount NUMERIC(14, 2) NOT NULL DEFAULT 0,
                      category_id INTEGER REFERENCES categories(id),
                      kind VARCHAR(32) NOT NULL DEFAULT 'uncategorized',
                      is_user_categorized BOOLEAN NOT NULL DEFAULT 0,
                      is_excluded_from_totals BOOLEAN NOT NULL DEFAULT 0,
                      notes TEXT,
                      transfer_pair_id INTEGER REFERENCES transactions(id),
                      import_batch_id INTEGER REFERENCES import_batches(id),
                      matched_rule_id INTEGER,
                      raw TEXT,
                      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                      updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS tags (
                      id INTEGER NOT NULL PRIMARY KEY,
                      name VARCHAR(64) NOT NULL UNIQUE,
                      color VARCHAR(16)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS transaction_tags (
                      transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
                      tag_id INTEGER NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
                      PRIMARY KEY (transaction_id, tag_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS transaction_splits (
                      transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE CASCADE PRIMARY KEY,
                      group_name VARCHAR(120) NOT NULL,
                      personal_share NUMERIC(5, 4) NOT NULL DEFAULT 0.5000,
                      notes TEXT,
                      created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS rules (
                      id INTEGER NOT NULL PRIMARY KEY,
                      name VARCHAR(120) NOT NULL,
                      priority INTEGER NOT NULL DEFAULT 100,
                      is_active BOOLEAN NOT NULL DEFAULT 1,
                      match_account_id INTEGER REFERENCES accounts(id),
                      match_description_pattern TEXT,
                      match_amount_min NUMERIC(14, 2),
                      match_amount_max NUMERIC(14, 2),
                      set_category_id INTEGER REFERENCES categories(id),
                      set_kind VARCHAR(32),
                      set_merchant VARCHAR(255),
                      set_tags TEXT,
                      notes TEXT,
                      last_applied_at DATETIME,
                      apply_count INTEGER NOT NULL DEFAULT 0,
                      created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS rule_proposal_rejections (
                      id INTEGER NOT NULL PRIMARY KEY,
                      signature VARCHAR(512) NOT NULL UNIQUE,
                      key VARCHAR(255) NOT NULL,
                      name VARCHAR(255) NOT NULL,
                      match_account_id INTEGER REFERENCES accounts(id),
                      match_description_pattern TEXT NOT NULL,
                      set_category_id INTEGER REFERENCES categories(id),
                      set_kind VARCHAR(32) NOT NULL,
                      set_merchant VARCHAR(255),
                      created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS ix_transactions_date_kind ON transactions(date, kind)");
            statement.execute("CREATE INDEX IF NOT EXISTS ix_transactions_account_date ON transactions(account_id, date)");
            statement.execute("CREATE INDEX IF NOT EXISTS ix_transaction_splits_group_name ON transaction_splits(group_name)");
            statement.execute("CREATE INDEX IF NOT EXISTS ix_rules_priority ON rules(priority)");
            statement.execute("CREATE INDEX IF NOT EXISTS ix_rule_proposal_rejections_signature ON rule_proposal_rejections(signature)");
            ensureColumn(connection, "transactions", "post_date", "DATE");
            ensureColumn(connection, "transactions", "description_normalized", "TEXT");
            ensureColumn(connection, "transactions", "merchant", "VARCHAR(255)");
            ensureColumn(connection, "transactions", "is_excluded_from_totals", "BOOLEAN NOT NULL DEFAULT 0");
            ensureColumn(connection, "transactions", "notes", "TEXT");
            ensureColumn(connection, "transactions", "transfer_pair_id", "INTEGER REFERENCES transactions(id)");
            ensureColumn(connection, "transactions", "import_batch_id", "INTEGER REFERENCES import_batches(id)");
            ensureColumn(connection, "transactions", "matched_rule_id", "INTEGER");
            ensureColumn(connection, "transactions", "raw", "TEXT");
            ensureColumn(connection, "transactions", "created_at", "DATETIME");
            ensureColumn(connection, "transactions", "updated_at", "DATETIME");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS net_worth_snapshots (
                      id INTEGER NOT NULL PRIMARY KEY,
                      snapshot_date DATE NOT NULL UNIQUE,
                      notes TEXT,
                      created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS account_balances (
                      snapshot_id INTEGER NOT NULL REFERENCES net_worth_snapshots(id) ON DELETE CASCADE,
                      account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                      balance NUMERIC(14, 2),
                      notes TEXT,
                      PRIMARY KEY (snapshot_id, account_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS goals (
                      id INTEGER NOT NULL PRIMARY KEY,
                      title VARCHAR(255) NOT NULL,
                      target_amount NUMERIC(14, 2),
                      target_date DATE,
                      kind VARCHAR(32) NOT NULL DEFAULT 'savings',
                      status VARCHAR(32) NOT NULL DEFAULT 'active',
                      linked_account_ids TEXT,
                      notes_markdown TEXT,
                      sort_order INTEGER NOT NULL DEFAULT 0,
                      archived BOOLEAN NOT NULL DEFAULT 0,
                      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                      updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS goal_revisions (
                      id INTEGER NOT NULL PRIMARY KEY,
                      goal_id INTEGER NOT NULL REFERENCES goals(id) ON DELETE CASCADE,
                      snapshot TEXT NOT NULL,
                      changed_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                      change_summary TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS journal_entries (
                      id INTEGER NOT NULL PRIMARY KEY,
                      entry_date DATE NOT NULL,
                      title VARCHAR(255) NOT NULL,
                      body_markdown TEXT NOT NULL,
                      goal_id INTEGER REFERENCES goals(id) ON DELETE SET NULL,
                      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                      updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS journal_entry_revisions (
                      id INTEGER NOT NULL PRIMARY KEY,
                      entry_id INTEGER NOT NULL REFERENCES journal_entries(id) ON DELETE CASCADE,
                      body_markdown TEXT NOT NULL,
                      title VARCHAR(255),
                      entry_date DATE,
                      goal_id INTEGER REFERENCES goals(id) ON DELETE SET NULL,
                      changed_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                      change_summary TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS fire_settings (
                      id INTEGER NOT NULL PRIMARY KEY,
                      growth_bank NUMERIC(6, 4) NOT NULL DEFAULT 0.0100,
                      growth_investment NUMERIC(6, 4) NOT NULL DEFAULT 0.0500,
                      growth_tax_advantaged NUMERIC(6, 4) NOT NULL DEFAULT 0.0500,
                      growth_nonsense NUMERIC(6, 4) NOT NULL DEFAULT 0.0000,
                      growth_cash NUMERIC(6, 4) NOT NULL DEFAULT 0.0000,
                      growth_credit NUMERIC(6, 4) NOT NULL DEFAULT 0.0000,
                      annual_retirement_spending NUMERIC(14, 2) NOT NULL DEFAULT 75000.00,
                      withdrawal_rate NUMERIC(6, 4) NOT NULL DEFAULT 0.0400,
                      updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS budget_defaults (
                      id INTEGER NOT NULL PRIMARY KEY,
                      category_id INTEGER NOT NULL UNIQUE REFERENCES categories(id) ON DELETE CASCADE,
                      amount NUMERIC(14, 2) NOT NULL,
                      notes TEXT,
                      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                      updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS budget_overrides (
                      id INTEGER NOT NULL PRIMARY KEY,
                      month DATE NOT NULL,
                      category_id INTEGER NOT NULL REFERENCES categories(id) ON DELETE CASCADE,
                      amount NUMERIC(14, 2) NOT NULL,
                      notes TEXT,
                      created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                      updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                      UNIQUE(month, category_id)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS ix_budget_overrides_month ON budget_overrides(month)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS monthly_reconciliations (
                      id INTEGER NOT NULL PRIMARY KEY,
                      account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                      year INTEGER NOT NULL,
                      month INTEGER NOT NULL,
                      statement_total NUMERIC(14, 2),
                      notes TEXT,
                      updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
                      UNIQUE(account_id, year, month)
                    )
                    """);
        }
    }

    private void ensureColumn(Connection connection, String table, String column, String definition) throws SQLException {
        if (hasColumn(connection, table, column)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(" + table + ")");
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }
}
