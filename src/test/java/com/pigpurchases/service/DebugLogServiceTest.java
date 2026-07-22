package com.pigpurchases.service;

import com.pigpurchases.model.AppLogEntry;
import com.pigpurchases.model.AppSettings;
import com.pigpurchases.repository.AppLogEntryRepository;
import com.pigpurchases.repository.AppSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class DebugLogServiceTest {

    @Autowired private DebugLogService debugLog;
    @Autowired private AppLogEntryRepository logRepo;
    @Autowired private AppSettingsRepository settingsRepo;

    @BeforeEach
    void reset() {
        logRepo.deleteAll();
        AppSettings s = settingsRepo.findById(1L).orElseGet(AppSettings::new);
        s.setId(1L);
        s.setDebugLogRetentionDays(2);
        settingsRepo.save(s);
    }

    @Test
    void recordsAndReturnsNewestFirst() {
        debugLog.info("anthropic", "first");
        debugLog.warn("anthropic", "second");
        debugLog.error("mapping", "third");

        List<AppLogEntry> recent = debugLog.recent();
        assertEquals(3, recent.size());
        assertEquals("third", recent.get(0).getMessage(), "newest first");
        assertEquals(AppLogEntry.Level.ERROR, recent.get(0).getLevel());
    }

    @Test
    void prunesEntriesOlderThanTheRetentionWindow() {
        // One entry well outside the 2-day window, one fresh.
        logRepo.save(new AppLogEntry(LocalDateTime.now().minusDays(5), AppLogEntry.Level.INFO, "anthropic", "stale"));
        debugLog.info("anthropic", "fresh");

        debugLog.pruneOld();

        List<AppLogEntry> recent = debugLog.recent();
        assertEquals(1, recent.size());
        assertEquals("fresh", recent.get(0).getMessage());
    }

    @Test
    void retentionOfZeroKeepsEverything() {
        AppSettings s = settingsRepo.findById(1L).orElseThrow();
        s.setDebugLogRetentionDays(0);
        settingsRepo.save(s);

        logRepo.save(new AppLogEntry(LocalDateTime.now().minusDays(100), AppLogEntry.Level.INFO, "anthropic", "ancient"));
        debugLog.pruneOld();

        assertTrue(debugLog.recent().stream().anyMatch(e -> "ancient".equals(e.getMessage())),
                "retention 0 means keep until cleared by hand");
    }

    @Test
    void clearEmptiesTheLog() {
        debugLog.info("anthropic", "a");
        debugLog.info("anthropic", "b");
        debugLog.clear();
        assertEquals(0, debugLog.recent().size());
    }
}
