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
        List<String> where = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        boolean joinAccounts = accountCategory != null && !accountCategory.isEmpty();
        if (start != null) {
            where.add("t.date >= ?");
            params.add(start);
        }
        if (end != null) {
            where.add("t.date <= ?");
            params.add(end);
        }
        if (accountId != null) {
            where.add("t.account_id = ?");
            params.add(accountId);
        }
        if (joinAccounts) {
            where.add("a.account_category IN (" + placeholders(accountCategory.size()) + ")");
            params.addAll(accountCategory);
        }
        if (categoryId != null) {
            where.add("t.category_id = ?");
            params.add(categoryId);
        }
        if (kind != null && !kind.isEmpty()) {
            where.add("t.kind IN (" + placeholders(kind.size()) + ")");
            params.addAll(kind);
        }
        if (amountMin != null) {
            where.add("t.amount >= ?");
            params.add(amountMin);
        }
        if (amountMax != null) {
            where.add("t.amount <= ?");
            params.add(amountMax);
        }
        if (q != null && !q.isBlank()) {
            addSearchFilter(where, params, q);
        }
        if (excludeExcluded) {
            where.add("t.is_excluded_from_totals = 0");
        }
        return new FilterSql(where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where), params, joinAccounts);
    }

    private static void addSearchFilter(List<String> where, List<Object> params, String query) {
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

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    record FilterSql(String whereSql, List<Object> params, boolean joinAccounts) {
    }
}
