package com.deskbooks.backend.transactions;

import com.deskbooks.backend.foundation.ApiException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

final class TransactionJsonNodes {
    private TransactionJsonNodes() {
    }

    static JsonNode required(JsonNode body, String field) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return node;
    }
}
