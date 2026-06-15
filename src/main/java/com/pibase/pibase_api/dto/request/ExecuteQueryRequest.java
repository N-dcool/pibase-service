package com.pibase.pibase_api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ExecuteQueryRequest(
        @NotBlank(message = "SQL query is required")
        @Size(max = 10_000, message = "Query too long (max 10000 character)")
        String sql
) {
}
