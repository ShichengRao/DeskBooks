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
        }
    }
}
