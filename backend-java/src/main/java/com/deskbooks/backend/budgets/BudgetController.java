package com.deskbooks.backend.budgets;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/budgets")
class BudgetController {
    private static final DateTimeFormatter SQLITE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SqliteConnectionProvider connections;

    BudgetController(SqliteConnectionProvider connections) {
        this.connections = connections;
    }

    @GetMapping("")
    BudgetReportResponse getBudget(
            @RequestParam(name = "start", required = false) LocalDate start,
            @RequestParam(name = "end", required = false) LocalDate end,
            @RequestParam(name = "focus_month", required = false) LocalDate focusMonth,
            @RequestParam(name = "month", required = false) LocalDate month) {
        if (month != null && start == null && end == null) {
            start = month;
            end = month;
            focusMonth = month;
        }
        if (start == null || end == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "provide start/end or month");
        }
        if (end.isBefore(start)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "end must be on or after start");
        }
        try (Connection connection = connections.open()) {
            return budgetReport(connection, start, end, focusMonth);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PutMapping("/defaults")
    BudgetDefaultResponse upsertBudgetDefault(@Valid @RequestBody BudgetDefaultRequest body) {
        try (Connection connection = connections.open()) {
            validateBudgetCategory(connection, body.categoryId(), body.amount());
            Long existingId = existingBudgetDefaultId(connection, body.categoryId());
            if (existingId == null) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO budget_defaults (category_id, amount, notes)
                        VALUES (?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, body.categoryId());
                    statement.setBigDecimal(2, money(body.amount()));
                    statement.setString(3, body.notes());
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        keys.next();
                        return getBudgetDefault(connection, keys.getLong(1));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE budget_defaults
                    SET amount = ?, notes = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """)) {
                statement.setBigDecimal(1, money(body.amount()));
                statement.setString(2, body.notes());
                statement.setLong(3, existingId);
                statement.executeUpdate();
            }
            return getBudgetDefault(connection, existingId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PutMapping("/overrides")
    BudgetOverrideResponse upsertBudgetOverride(@Valid @RequestBody BudgetOverrideRequest body) {
        try (Connection connection = connections.open()) {
            validateBudgetCategory(connection, body.categoryId(), body.amount());
            LocalDate month = normalizeMonth(body.month());
            Long existingId = existingBudgetOverrideId(connection, month, body.categoryId());
            if (existingId == null) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO budget_overrides (month, category_id, amount, notes)
                        VALUES (?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, month.toString());
                    statement.setLong(2, body.categoryId());
                    statement.setBigDecimal(3, money(body.amount()));
                    statement.setString(4, body.notes());
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        keys.next();
                        return getBudgetOverride(connection, keys.getLong(1));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE budget_overrides
                    SET amount = ?, notes = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """)) {
                statement.setBigDecimal(1, money(body.amount()));
                statement.setString(2, body.notes());
                statement.setLong(3, existingId);
                statement.executeUpdate();
            }
            return getBudgetOverride(connection, existingId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @DeleteMapping("/defaults/{budgetId}")
    Map<String, Boolean> deleteBudgetDefault(@PathVariable long budgetId) {
        try (Connection connection = connections.open()) {
            deleteBudgetRow(connection, "budget_defaults", budgetId, "budget default not found");
            return Map.of("ok", true);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @DeleteMapping("/overrides/{budgetId}")
    Map<String, Boolean> deleteBudgetOverride(@PathVariable long budgetId) {
        try (Connection connection = connections.open()) {
            deleteBudgetRow(connection, "budget_overrides", budgetId, "budget override not found");
            return Map.of("ok", true);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private BudgetReportResponse budgetReport(
            Connection connection,
            LocalDate startValue,
            LocalDate endValue,
            LocalDate focusMonthValue) throws SQLException {
        List<LocalDate> months = monthRange(startValue, endValue);
        if (months.isEmpty()) {
            months = List.of(normalizeMonth(startValue));
        }
        LocalDate focusMonth = focusMonthValue == null ? null : normalizeMonth(focusMonthValue);
        if (focusMonth != null && (focusMonth.isBefore(months.getFirst()) || focusMonth.isAfter(months.getLast()))) {
            focusMonth = months.getLast();
        }

        CategoryContext context = categoryContext(connection);
        Map<Long, BudgetDefault> defaultByCategory = budgetDefaults(connection);
        Map<MonthCategoryKey, BudgetOverride> overrideByMonthCategory = budgetOverrides(connection, months.getFirst(), months.getLast());
        SpendingContext spending = spendingContext(connection, months);

        Map<MonthCategoryKey, BigDecimal> rollupCache = new HashMap<>();
        List<LocalDate> rowMonths = focusMonth == null ? months : List.of(focusMonth);
        List<BudgetReportRowResponse> rows = new ArrayList<>();
        for (CategoryRow category : context.orderedCategories()) {
            Set<Long> ids = descendants(context, category.id());
            BigDecimal actual = BigDecimal.ZERO;
            int transactionCount = 0;
            for (LocalDate rowMonth : rowMonths) {
                for (Long id : ids) {
                    actual = actual.add(spending.actualByMonthExact().getOrDefault(new MonthCategoryKey(rowMonth, id), BigDecimal.ZERO));
                    transactionCount += spending.countByMonthExact().getOrDefault(new MonthCategoryKey(rowMonth, id), 0);
                }
            }

            BudgetDefault defaultBudget = defaultByCategory.get(category.id());
            BudgetOverride override = focusMonth == null ? null : overrideByMonthCategory.get(new MonthCategoryKey(focusMonth, category.id()));
            List<BigDecimal> rowTargets = new ArrayList<>();
            for (LocalDate rowMonth : rowMonths) {
                BigDecimal target = rollupTargetFor(rowMonth, category.id(), context, defaultByCategory, overrideByMonthCategory, rollupCache);
                if (target != null) {
                    rowTargets.add(target);
                }
            }
            BigDecimal target = rowTargets.isEmpty()
                    ? null
                    : rowTargets.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            CategoryRow parent = category.parentId() == null ? null : context.categoryById().get(category.parentId());
            rows.add(new BudgetReportRowResponse(
                    category.id(),
                    category.name(),
                    category.parentId(),
                    parent == null ? null : parent.name(),
                    depth(context, category),
                    !context.childrenByParent().getOrDefault(category.id(), List.of()).isEmpty(),
                    defaultBudget == null ? null : defaultBudget.id(),
                    defaultBudget == null ? null : moneyString(defaultBudget.amount()),
                    override == null ? null : override.id(),
                    override == null ? null : moneyString(override.amount()),
                    moneyStringOrNull(target),
                    moneyString(actual),
                    target == null ? null : moneyString(target.subtract(actual)),
                    transactionCount,
                    defaultBudget == null ? null : defaultBudget.notes(),
                    override == null ? null : override.notes()));
        }

        List<BudgetMonthSummaryResponse> summaries = new ArrayList<>();
        for (LocalDate month : months) {
            summaries.add(monthSummary(month, context, defaultByCategory, overrideByMonthCategory, spending, rollupCache));
        }
        BigDecimal rangePlanned = summaries.stream().map(summary -> new BigDecimal(summary.plannedTotal())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rangeActual = summaries.stream().map(summary -> new BigDecimal(summary.actualTotal())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rangeBudgetedActual = summaries.stream().map(summary -> new BigDecimal(summary.budgetedActualTotal())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rangeUncategorized = summaries.stream().map(summary -> new BigDecimal(summary.uncategorizedActual())).reduce(BigDecimal.ZERO, BigDecimal::add);

        return new BudgetReportResponse(
                months.getFirst(),
                months.getLast(),
                focusMonth,
                summaries,
                moneyString(rangePlanned),
                moneyString(rangeActual),
                moneyString(rangePlanned.subtract(rangeActual)),
                moneyString(rangeBudgetedActual),
                moneyString(rangeActual.subtract(rangeBudgetedActual)),
                moneyString(rangeUncategorized),
                rows);
    }

    private BudgetMonthSummaryResponse monthSummary(
            LocalDate month,
            CategoryContext context,
            Map<Long, BudgetDefault> defaultByCategory,
            Map<MonthCategoryKey, BudgetOverride> overrideByMonthCategory,
            SpendingContext spending,
            Map<MonthCategoryKey, BigDecimal> rollupCache) {
        BigDecimal plannedTotal = BigDecimal.ZERO;
        Set<Long> coveredCategoryIds = new HashSet<>();
        for (CategoryRow root : context.roots()) {
            BigDecimal target = rollupTargetFor(month, root.id(), context, defaultByCategory, overrideByMonthCategory, rollupCache);
            if (target == null) {
                continue;
            }
            plannedTotal = plannedTotal.add(target);
            coveredCategoryIds.addAll(descendants(context, root.id()));
        }

        BigDecimal actualTotal = spending.transactionRowsByMonth().getOrDefault(month, List.of()).stream()
                .map(TransactionSpending::spending)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal budgetedActualTotal = BigDecimal.ZERO;
        for (TransactionSpending row : spending.transactionRowsByMonth().getOrDefault(month, List.of())) {
            if (row.categoryId() != null && coveredCategoryIds.contains(row.categoryId())) {
                budgetedActualTotal = budgetedActualTotal.add(row.spending());
            }
        }
        BigDecimal uncategorized = spending.uncategorizedByMonth().getOrDefault(month, BigDecimal.ZERO);
        return new BudgetMonthSummaryResponse(
                month,
                moneyString(plannedTotal),
                moneyString(actualTotal),
                moneyString(plannedTotal.subtract(actualTotal)),
                moneyString(budgetedActualTotal),
                moneyString(actualTotal.subtract(budgetedActualTotal)),
                moneyString(uncategorized));
    }

    private CategoryContext categoryContext(Connection connection) throws SQLException {
        List<CategoryRow> categories = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, name, parent_id, sort_order
                FROM categories
                WHERE kind = 'expense'
                ORDER BY sort_order, name
                """);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                categories.add(new CategoryRow(
                        rs.getLong("id"),
                        rs.getString("name"),
                        nullableLong(rs, "parent_id"),
                        rs.getInt("sort_order")));
            }
        }

        Map<Long, CategoryRow> categoryById = new LinkedHashMap<>();
        Map<Long, List<CategoryRow>> childrenByParent = new LinkedHashMap<>();
        for (CategoryRow category : categories) {
            categoryById.put(category.id(), category);
            if (category.parentId() != null) {
                childrenByParent.computeIfAbsent(category.parentId(), ignored -> new ArrayList<>()).add(category);
            }
        }
        childrenByParent.values().forEach(children -> children.sort(categoryComparator()));
        List<CategoryRow> roots = categories.stream()
                .filter(category -> category.parentId() == null || !categoryById.containsKey(category.parentId()))
                .sorted(categoryComparator())
                .toList();
        List<CategoryRow> ordered = new ArrayList<>();
        for (CategoryRow root : roots) {
            walk(root, childrenByParent, ordered);
        }
        return new CategoryContext(categories, roots, ordered, categoryById, childrenByParent);
    }

    private void walk(CategoryRow category, Map<Long, List<CategoryRow>> childrenByParent, List<CategoryRow> ordered) {
        ordered.add(category);
        for (CategoryRow child : childrenByParent.getOrDefault(category.id(), List.of())) {
            walk(child, childrenByParent, ordered);
        }
    }

    private Comparator<CategoryRow> categoryComparator() {
        return Comparator.comparingInt(CategoryRow::sortOrder)
                .thenComparing(category -> category.name().toLowerCase());
    }

    private Map<Long, BudgetDefault> budgetDefaults(Connection connection) throws SQLException {
        Map<Long, BudgetDefault> out = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, category_id, amount, notes FROM budget_defaults
                """);
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getLong("category_id"), new BudgetDefault(
                        rs.getLong("id"),
                        rs.getLong("category_id"),
                        rs.getBigDecimal("amount"),
                        rs.getString("notes")));
            }
        }
        return out;
    }

    private Map<MonthCategoryKey, BudgetOverride> budgetOverrides(
            Connection connection,
            LocalDate start,
            LocalDate end) throws SQLException {
        Map<MonthCategoryKey, BudgetOverride> out = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, month, category_id, amount, notes
                FROM budget_overrides
                WHERE month >= ? AND month <= ?
                """)) {
            statement.setString(1, start.toString());
            statement.setString(2, end.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    LocalDate month = LocalDate.parse(rs.getString("month"));
                    long categoryId = rs.getLong("category_id");
                    out.put(new MonthCategoryKey(month, categoryId), new BudgetOverride(
                            rs.getLong("id"),
                            month,
                            categoryId,
                            rs.getBigDecimal("amount"),
                            rs.getString("notes")));
                }
            }
        }
        return out;
    }

    private SpendingContext spendingContext(Connection connection, List<LocalDate> months) throws SQLException {
        Map<MonthCategoryKey, BigDecimal> actualByMonthExact = new HashMap<>();
        Map<MonthCategoryKey, Integer> countByMonthExact = new HashMap<>();
        Map<LocalDate, List<TransactionSpending>> transactionRowsByMonth = new HashMap<>();
        Map<LocalDate, BigDecimal> uncategorizedByMonth = new HashMap<>();

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT t.date, t.category_id, t.amount, s.personal_share
                FROM transactions t
                LEFT JOIN transaction_splits s ON s.transaction_id = t.id
                WHERE t.date >= ?
                  AND t.date < ?
                  AND t.kind = 'expense'
                  AND t.is_excluded_from_totals = 0
                """)) {
            statement.setString(1, months.getFirst().toString());
            statement.setString(2, monthEndExclusive(months.getLast()).toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    LocalDate month = normalizeMonth(LocalDate.parse(rs.getString("date")));
                    Long categoryId = nullableLong(rs, "category_id");
                    BigDecimal personalShare = rs.getBigDecimal("personal_share");
                    BigDecimal effective = personalShare == null
                            ? rs.getBigDecimal("amount")
                            : rs.getBigDecimal("amount").multiply(personalShare);
                    BigDecimal spending = effective.negate();
                    transactionRowsByMonth.computeIfAbsent(month, ignored -> new ArrayList<>()).add(new TransactionSpending(categoryId, spending));
                    if (categoryId == null) {
                        uncategorizedByMonth.merge(month, spending, BigDecimal::add);
                        continue;
                    }
                    MonthCategoryKey key = new MonthCategoryKey(month, categoryId);
                    actualByMonthExact.merge(key, spending, BigDecimal::add);
                    countByMonthExact.merge(key, 1, Integer::sum);
                }
            }
        }
        return new SpendingContext(actualByMonthExact, countByMonthExact, transactionRowsByMonth, uncategorizedByMonth);
    }

    private BigDecimal rollupTargetFor(
            LocalDate month,
            long categoryId,
            CategoryContext context,
            Map<Long, BudgetDefault> defaultByCategory,
            Map<MonthCategoryKey, BudgetOverride> overrideByMonthCategory,
            Map<MonthCategoryKey, BigDecimal> cache) {
        MonthCategoryKey key = new MonthCategoryKey(month, categoryId);
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        List<BigDecimal> childTargets = new ArrayList<>();
        for (CategoryRow child : context.childrenByParent().getOrDefault(categoryId, List.of())) {
            BigDecimal target = rollupTargetFor(month, child.id(), context, defaultByCategory, overrideByMonthCategory, cache);
            if (target != null) {
                childTargets.add(target);
            }
        }
        BigDecimal target;
        if (childTargets.isEmpty()) {
            target = directTargetFor(month, categoryId, defaultByCategory, overrideByMonthCategory);
        } else {
            target = childTargets.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        cache.put(key, target);
        return target;
    }

    private BigDecimal directTargetFor(
            LocalDate month,
            long categoryId,
            Map<Long, BudgetDefault> defaultByCategory,
            Map<MonthCategoryKey, BudgetOverride> overrideByMonthCategory) {
        BudgetOverride override = overrideByMonthCategory.get(new MonthCategoryKey(month, categoryId));
        if (override != null) {
            return override.amount();
        }
        BudgetDefault budgetDefault = defaultByCategory.get(categoryId);
        return budgetDefault == null ? null : budgetDefault.amount();
    }

    private Set<Long> descendants(CategoryContext context, long categoryId) {
        Set<Long> out = new LinkedHashSet<>();
        out.add(categoryId);
        for (CategoryRow child : context.childrenByParent().getOrDefault(categoryId, List.of())) {
            out.addAll(descendants(context, child.id()));
        }
        return out;
    }

    private int depth(CategoryContext context, CategoryRow category) {
        CategoryRow current = category;
        int count = 0;
        Set<Long> seen = new HashSet<>();
        while (current.parentId() != null
                && context.categoryById().containsKey(current.parentId())
                && seen.add(current.parentId())) {
            count++;
            current = context.categoryById().get(current.parentId());
        }
        return count;
    }

    private void validateBudgetCategory(Connection connection, long categoryId, BigDecimal amount) throws SQLException {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "budget amount must be zero or greater");
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT kind FROM categories WHERE id = ?")) {
            statement.setLong(1, categoryId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "category not found");
                }
                if (!"expense".equals(rs.getString("kind"))) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "budgets can only target expense categories");
                }
            }
        }
    }

    private Long existingBudgetDefaultId(Connection connection, long categoryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM budget_defaults WHERE category_id = ?")) {
            statement.setLong(1, categoryId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getLong("id");
            }
        }
    }

    private Long existingBudgetOverrideId(Connection connection, LocalDate month, long categoryId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id FROM budget_overrides WHERE month = ? AND category_id = ?
                """)) {
            statement.setString(1, month.toString());
            statement.setLong(2, categoryId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return rs.getLong("id");
            }
        }
    }

    private BudgetDefaultResponse getBudgetDefault(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, category_id, amount, notes, updated_at
                FROM budget_defaults
                WHERE id = ?
                """)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "budget default not found");
                }
                return new BudgetDefaultResponse(
                        rs.getLong("id"),
                        rs.getLong("category_id"),
                        moneyString(rs.getBigDecimal("amount")),
                        rs.getString("notes"),
                        localDateTime(rs.getString("updated_at")));
            }
        }
    }

    private BudgetOverrideResponse getBudgetOverride(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, month, category_id, amount, notes, updated_at
                FROM budget_overrides
                WHERE id = ?
                """)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "budget override not found");
                }
                return new BudgetOverrideResponse(
                        rs.getLong("id"),
                        LocalDate.parse(rs.getString("month")),
                        rs.getLong("category_id"),
                        moneyString(rs.getBigDecimal("amount")),
                        rs.getString("notes"),
                        localDateTime(rs.getString("updated_at")));
            }
        }
    }

    private void deleteBudgetRow(Connection connection, String table, long id, String missingMessage) throws SQLException {
        String selectSql = "SELECT 1 FROM " + table + " WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, missingMessage);
                }
            }
        }
        String deleteSql = "DELETE FROM " + table + " WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(deleteSql)) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    private List<LocalDate> monthRange(LocalDate start, LocalDate end) {
        LocalDate current = normalizeMonth(start);
        LocalDate finalMonth = normalizeMonth(end);
        List<LocalDate> out = new ArrayList<>();
        while (!current.isAfter(finalMonth)) {
            out.add(current);
            current = monthEndExclusive(current);
        }
        return out;
    }

    private LocalDate normalizeMonth(LocalDate value) {
        return LocalDate.of(value.getYear(), value.getMonth(), 1);
    }

    private LocalDate monthEndExclusive(LocalDate month) {
        return normalizeMonth(month).plusMonths(1);
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private String moneyString(BigDecimal value) {
        return money(value == null ? BigDecimal.ZERO : value).toPlainString();
    }

    private String moneyStringOrNull(BigDecimal value) {
        return value == null ? null : moneyString(value);
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private LocalDateTime localDateTime(String value) {
        if (value == null) {
            return null;
        }
        return value.contains("T") ? LocalDateTime.parse(value) : LocalDateTime.parse(value, SQLITE_TIMESTAMP);
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }

    record BudgetDefaultRequest(@NotNull Long categoryId, @NotNull BigDecimal amount, String notes) {
    }

    record BudgetOverrideRequest(@NotNull LocalDate month, @NotNull Long categoryId, @NotNull BigDecimal amount, String notes) {
    }

    record BudgetDefaultResponse(long id, long categoryId, String amount, String notes, LocalDateTime updatedAt) {
    }

    record BudgetOverrideResponse(long id, LocalDate month, long categoryId, String amount, String notes, LocalDateTime updatedAt) {
    }

    record BudgetMonthSummaryResponse(
            LocalDate month,
            String plannedTotal,
            String actualTotal,
            String deltaTotal,
            String budgetedActualTotal,
            String unbudgetedActualTotal,
            String uncategorizedActual) {
    }

    record BudgetReportRowResponse(
            long categoryId,
            String categoryName,
            Long parentId,
            String parentName,
            int depth,
            boolean hasChildren,
            Long defaultBudgetId,
            String defaultAmount,
            Long overrideBudgetId,
            String overrideAmount,
            String targetAmount,
            String actualAmount,
            String delta,
            int transactionCount,
            String defaultNotes,
            String overrideNotes) {
    }

    record BudgetReportResponse(
            LocalDate start,
            LocalDate end,
            LocalDate focusMonth,
            List<BudgetMonthSummaryResponse> months,
            String plannedTotal,
            String actualTotal,
            String deltaTotal,
            String budgetedActualTotal,
            String unbudgetedActualTotal,
            String uncategorizedActual,
            List<BudgetReportRowResponse> rows) {
    }

    private record CategoryRow(long id, String name, Long parentId, int sortOrder) {
    }

    private record CategoryContext(
            List<CategoryRow> categories,
            List<CategoryRow> roots,
            List<CategoryRow> orderedCategories,
            Map<Long, CategoryRow> categoryById,
            Map<Long, List<CategoryRow>> childrenByParent) {
    }

    private record BudgetDefault(long id, long categoryId, BigDecimal amount, String notes) {
    }

    private record BudgetOverride(long id, LocalDate month, long categoryId, BigDecimal amount, String notes) {
    }

    private record MonthCategoryKey(LocalDate month, long categoryId) {
    }

    private record TransactionSpending(Long categoryId, BigDecimal spending) {
    }

    private record SpendingContext(
            Map<MonthCategoryKey, BigDecimal> actualByMonthExact,
            Map<MonthCategoryKey, Integer> countByMonthExact,
            Map<LocalDate, List<TransactionSpending>> transactionRowsByMonth,
            Map<LocalDate, BigDecimal> uncategorizedByMonth) {
    }
}
