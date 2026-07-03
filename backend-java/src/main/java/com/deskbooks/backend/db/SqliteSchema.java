package com.deskbooks.backend.db;

import java.sql.Connection;
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
                    CREATE TABLE IF NOT EXISTS transactions (
                      id INTEGER NOT NULL PRIMARY KEY,
                      account_id INTEGER NOT NULL REFERENCES accounts(id),
                      date DATE NOT NULL DEFAULT '1970-01-01',
                      description_raw TEXT NOT NULL DEFAULT '',
                      amount NUMERIC(14, 2) NOT NULL DEFAULT 0,
                      category_id INTEGER REFERENCES categories(id),
                      kind VARCHAR(32) NOT NULL DEFAULT 'uncategorized',
                      is_user_categorized BOOLEAN NOT NULL DEFAULT 0
                    )
                    """);
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
        }
    }
}
