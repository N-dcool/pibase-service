package com.pibase.pibase_api.controller;

import com.pibase.pibase_api.dto.request.ExecuteQueryRequest;
import com.pibase.pibase_api.dto.response.QueryResultResponse;
import com.pibase.pibase_api.security.UserPrincipal;
import com.pibase.pibase_api.service.PlaygroundService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/playground")
@RequiredArgsConstructor
public class PlaygroundController {

    private final PlaygroundService playgroundService;

    @PostMapping("/query")
    public ResponseEntity<QueryResultResponse> executeQuery(
            @Valid @RequestBody ExecuteQueryRequest request,
            @AuthenticationPrincipal UserPrincipal principal
    ) {

        QueryResultResponse result = playgroundService
                .executeQuery(principal.getId(), request.sql());

        return ResponseEntity.ok(result);
    }

    @GetMapping("/tables")
    public ResponseEntity<Map<String, List<Map<String, String>>>> getTables(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        return ResponseEntity.ok(
                playgroundService.getTableSchemas(principal.getId())
        );
    }
}
