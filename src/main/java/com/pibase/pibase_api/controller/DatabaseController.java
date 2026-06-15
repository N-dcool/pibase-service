package com.pibase.pibase_api.controller;

import com.pibase.pibase_api.dto.request.CreateDatabaseRequest;
import com.pibase.pibase_api.dto.response.DatabaseResponse;
import com.pibase.pibase_api.entity.DatabaseInstance;
import com.pibase.pibase_api.security.UserPrincipal;
import com.pibase.pibase_api.service.DatabaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/db")
@RequiredArgsConstructor
@Slf4j
public class DatabaseController {

    private final DatabaseService databaseService;

    @PostMapping("/create")
    public ResponseEntity<DatabaseResponse> createDatabase(
            @Valid @RequestBody CreateDatabaseRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        DatabaseInstance db = databaseService.createDatabase(principal.getId(), request.engine());

        log.info("DB create request accepted: {} for user {}", db.getId(), principal.getId());

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(DatabaseResponse.from(db));
    }

    @GetMapping("/status")
    public ResponseEntity<DatabaseResponse> getStatus(
            @AuthenticationPrincipal UserPrincipal principal) {

        return databaseService.findActiveByUserId(principal.getId())
                .map(db -> ResponseEntity.ok(DatabaseResponse.from(db)))
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/restart")
    public ResponseEntity<Void> restartDatabase(@AuthenticationPrincipal UserPrincipal principal) {
        databaseService.restartDatabase(principal.getId());

        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/delete")
    public ResponseEntity<Void> deleteDatabase(@AuthenticationPrincipal UserPrincipal principal) {
        databaseService.deleteDatabase(principal.getId());

        return ResponseEntity.accepted().build();
    }
}
