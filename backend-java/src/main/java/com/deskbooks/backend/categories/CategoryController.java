package com.deskbooks.backend.categories;

import java.sql.Connection;
import java.sql.SQLException;
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
@RequestMapping("/api/categories")
class CategoryController {
    private final SqliteConnectionProvider connections;
    private final CategoryStore categories;

    CategoryController(SqliteConnectionProvider connections) {
        this.connections = connections;
        this.categories = new CategoryStore();
    }

    @GetMapping("")
    List<CategoryResponse> listCategories(
            @RequestParam(name = "include_archived", defaultValue = "false") boolean includeArchived) {
        try (Connection connection = connections.open()) {
            return categories.list(connection, includeArchived);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PostMapping("")
    CategoryResponse createCategory(@Valid @RequestBody CategoryRequest body) {
        try (Connection connection = connections.open()) {
            return categories.create(connection, body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @PatchMapping("/{categoryId}")
    CategoryResponse updateCategory(@PathVariable long categoryId, @RequestBody JsonNode body) {
        try (Connection connection = connections.open()) {
            return categories.update(connection, categoryId, body);
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    @DeleteMapping("/{categoryId}")
    Map<String, String> deleteCategory(@PathVariable long categoryId) {
        try (Connection connection = connections.open()) {
            categories.archive(connection, categoryId);
            return Map.of("status", "archived");
        } catch (SQLException exception) {
            throw databaseError(exception);
        }
    }

    private ApiException databaseError(SQLException exception) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, exception.getMessage());
    }

    record CategoryRequest(
            @NotBlank String name,
            Long parentId,
            @NotBlank String kind,
            String color,
            Integer sortOrder,
            Boolean archived) {
    }

    record CategoryResponse(
            long id,
            String name,
            Long parentId,
            String kind,
            String color,
            int sortOrder,
            boolean archived) {
    }

}
