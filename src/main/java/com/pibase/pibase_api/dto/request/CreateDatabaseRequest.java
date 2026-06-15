package com.pibase.pibase_api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateDatabaseRequest(
        @NotBlank(message = "Engine is required")
        @Pattern(regexp = "^(postgresql|mysql)$", message = "Engine must be 'postgresql' or 'mysql")
        String engine
) {
}
