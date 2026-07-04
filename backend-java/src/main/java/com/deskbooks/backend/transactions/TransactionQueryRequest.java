package com.deskbooks.backend.transactions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;

record TransactionQueryRequest(
        LocalDate start,
        LocalDate end,
        Long account_id,
        List<String> account_category,
        Long category_id,
        List<String> kind,
        BigDecimal amount_min,
        BigDecimal amount_max,
        String q,
        Boolean exclude_excluded,
        Integer limit,
        Integer offset) {
    int pageLimit() {
        return limit == null ? 100 : limit;
    }

    int pageOffset() {
        return offset == null ? 0 : offset;
    }

    void validatePage() {
        if (pageLimit() < 1 || pageOffset() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "limit must be >= 1 and offset must be >= 0");
        }
    }

    TransactionFilters.FilterSql filters() {
        return TransactionFilters.build(
                start,
                end,
                account_id,
                account_category,
                category_id,
                kind,
                amount_min,
                amount_max,
                q,
                Boolean.TRUE.equals(exclude_excluded));
    }
}
