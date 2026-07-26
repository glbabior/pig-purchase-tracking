package com.pigpurchases.server;

import com.pigpurchases.service.BackupService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Lets the Settings screen show the backup state (last backup, folder, retention,
 * recent files, any warning) and trigger an on-demand backup.
 */
@RestController
@RequestMapping("/api/backup")
public class BackupController {

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return backupService.status();
    }

    @PostMapping("/now")
    public Map<String, Object> now() {
        return backupService.backupNow();
    }
}
