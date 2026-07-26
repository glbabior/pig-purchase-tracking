package com.pigpurchases.server;

import com.pigpurchases.service.RestoreService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The backup restore-preview workflow: preview a backup (the app shows its data
 * without touching the live db), then commit or cancel.
 */
@RestController
@RequestMapping("/api/restore")
public class RestoreController {

    private final RestoreService restoreService;

    public RestoreController(RestoreService restoreService) {
        this.restoreService = restoreService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return restoreService.state();
    }

    @PostMapping("/preview")
    public ResponseEntity<Map<String, Object>> preview(@RequestBody Map<String, Object> body) {
        String file = body.get("file") == null ? null : String.valueOf(body.get("file"));
        try {
            return ResponseEntity.ok(restoreService.preview(file));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/commit")
    public ResponseEntity<Map<String, Object>> commit() {
        try {
            return ResponseEntity.ok(restoreService.commit());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/cancel")
    public ResponseEntity<Map<String, Object>> cancel() {
        try {
            return ResponseEntity.ok(restoreService.cancel());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/comment")
    public Map<String, Object> comment(@RequestBody Map<String, Object> body) {
        restoreService.setReviewerComment(body.get("comment") == null ? null : String.valueOf(body.get("comment")));
        return restoreService.state();
    }
}
