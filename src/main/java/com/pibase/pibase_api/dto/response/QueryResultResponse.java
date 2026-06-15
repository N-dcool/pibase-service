package com.pibase.pibase_api.dto.response;

import lombok.Builder;

import java.util.List;
import java.util.Map;

@Builder
public record QueryResultResponse(
        List<String> fields,
        List<Map<String, Object>> rows,
        int rowCount,
        long ms,
        String message
) {
}
