package com.deskbooks.backend.accounts;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.deskbooks.backend.db.SqliteConnectionProvider;
import com.deskbooks.backend.foundation.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/accounts")
class AccountController {
    private final AccountStore accounts;

    AccountController(SqliteConnectionProvider connections) {
        accounts = new AccountStore(connections);
    }

    @GetMapping("")
    List<AccountResponse> listAccounts(
            @RequestParam(name = "include_closed", defaultValue = "true") boolean includeClosed) {
        try {
            return accounts.list(includeClosed);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("")
    AccountResponse createAccount(@Valid @RequestBody AccountRequest body) {
        try {
            return accounts.create(body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @GetMapping("/{accountId}")
    AccountResponse getAccount(@PathVariable long accountId) {
        try {
            return accounts.get(accountId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PatchMapping("/{accountId}")
    AccountResponse updateAccount(@PathVariable long accountId, @RequestBody JsonNode body) {
        try {
            return accounts.update(accountId, body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @DeleteMapping("/{accountId}")
    Map<String, String> deleteAccount(@PathVariable long accountId) {
        try {
            return accounts.delete(accountId);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }

    record AccountRequest(
            @NotBlank String name,
            String institution,
            @NotBlank String accountCategory,
            @NotBlank String type,
            String currency,
            String signConvention,
            String url,
            String notes,
            Boolean isClosed,
            LocalDate openedAt,
            LocalDate closedAt,
            Integer sortOrder) {
    }

    record AccountResponse(
            long id,
            String name,
            String institution,
            String accountCategory,
            String type,
            String currency,
            String signConvention,
            String url,
            String notes,
            boolean isClosed,
            LocalDate openedAt,
            LocalDate closedAt,
            int sortOrder) {
    }
}
