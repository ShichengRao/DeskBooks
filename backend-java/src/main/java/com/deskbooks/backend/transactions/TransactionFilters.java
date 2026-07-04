package com.deskbooks.backend.transactions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class TransactionFilters {
    private TransactionFilters() {
    }

    static FilterSql build(
            LocalDate start,
            LocalDate end,
            Long accountId,
            List<String> accountCategory,
            Long categoryId,
            List<String> kind,
            BigDecimal amountMin,
            BigDecimal amountMax,
            String q,
            boolean excludeExcluded) {
        FilterBuilder filters = new FilterBuilder();
        boolean joinAccounts = accountCategory != null && !accountCategory.isEmpty();
        filters.addValue("t.date >= ?", start);
        filters.addValue("t.date <= ?", end);
        filters.addValue("t.account_id = ?", accountId);
        filters.addValues(joinAccounts, "a.account_category IN (" + placeholders(sizeOf(accountCategory)) + ")", accountCategory);
        filters.addValue("t.category_id = ?", categoryId);
        filters.addValues(kind != null && !kind.isEmpty(), "t.kind IN (" + placeholders(sizeOf(kind)) + ")", kind);
        filters.addValue("t.amount >= ?", amountMin);
        filters.addValue("t.amount <= ?", amountMax);
        filters.addSearch(q);
        filters.addClause(excludeExcluded, "t.is_excluded_from_totals = 0");
        return filters.toSql(joinAccounts);
    }

    private static int sizeOf(List<String> values) {
        return values == null ? 0 : values.size();
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    record FilterSql(String whereSql, List<Object> params, boolean joinAccounts) {
    }

    private static final class FilterBuilder {
        private final List<String> where = new ArrayList<>();
        private final List<Object> params = new ArrayList<>();

        void addValue(String clause, Object value) {
            if (value != null) {
                where.add(clause);
                params.add(value);
            }
        }

        void addValues(boolean condition, String clause, List<String> values) {
            if (condition) {
                where.add(clause);
                params.addAll(values);
            }
        }

        void addSearch(String query) {
            if (query == null || query.isBlank()) {
                return;
            }
            String like = "%" + query.toLowerCase(Locale.ROOT) + "%";
            where.add("""
                    (
                      LOWER(COALESCE(t.description_raw, '')) LIKE ?
                      OR LOWER(COALESCE(t.description_normalized, '')) LIKE ?
                      OR LOWER(COALESCE(t.merchant, '')) LIKE ?
                      OR LOWER(COALESCE(t.notes, '')) LIKE ?
                    )
                    """);
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }

        void addClause(boolean condition, String clause) {
            if (condition) {
                where.add(clause);
            }
        }

        FilterSql toSql(boolean joinAccounts) {
            String whereSql = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
            return new FilterSql(whereSql, params, joinAccounts);
        }
    }
}
