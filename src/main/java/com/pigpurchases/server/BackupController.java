package com.pigpurchases.server;

import com.pigpurchases.service.BackupService;
import com.pigpurchases.service.RestoreService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Lets the Settings screen show the backup state (last backup, folder, retention,
 * recent files, any warning), the restore-preview state, and trigger an on-demand
 * backup.
 */
@RestController
@RequestMapping("/api/backup")
public class BackupController {

    private final BackupService backupService;
    private final RestoreService restoreService;

    public BackupController(BackupService backupService, RestoreService restoreService) {
        this.backupService = backupService;
        this.restoreService = restoreService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new HashMap<>(backupService.status());
        m.put("restore", restoreService.state());
        return m;
    }

    @PostMapping("/now")
    public Map<String, Object> now() {
        return backupService.backupNow();
    }

    /**
     * "That drop was intentional." Clears the anti-clobber baseline and backs up normally.
     * Without it, deliberately deleting an analysis run wedged every future backup into a
     * .SUSPECT file — permanently, since a restart re-seeded the old baseline — and the
     * warning told the user to investigate while offering no way to resolve it.
     */
    @PostMapping("/accept-baseline")
    public Map<String, Object> acceptBaseline() {
        return backupService.acceptCurrentAsNormal();
    }
}
