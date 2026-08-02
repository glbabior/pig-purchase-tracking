package com.pigpurchases.service;

import com.pigpurchases.model.AppLogEntry;
import com.pigpurchases.model.AppSettings;
import com.pigpurchases.repository.AppLogEntryRepository;
import com.pigpurchases.repository.AppSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The in-app debug log behind the Debug screen. Services call {@link #record}
 * to leave a durable trail — most importantly every outbound Claude API call —
 * that survives past the moment and doesn't require watching the console.
 *
 * Writes are in their own transaction ({@code REQUIRES_NEW}) so logging never
 * joins, and therefore can never roll back or be rolled back by, the caller's
 * transaction: an AI failure still gets logged even though the surrounding
 * mapping work continues, and a logging hiccup never fails a real operation.
 */
@Service
public class DebugLogService {

    @Autowired private AppLogEntryRepository logRepository;
    @Autowired private AppSettingsRepository settingsRepository;

    private static final int PRUNE_EVERY = 20;
    private int writesSincePrune = 0;

    // REQUIRES_NEW belongs on THESE three, not only on record(). Every caller in the app
    // reaches the log through them, and `record(...)` from inside this class is a plain
    // this-call that never touches the Spring proxy — so the annotation below it never
    // applied and log writes silently joined the caller's transaction. A mapping batch or
    // an ingest that rolled back took its own explanation down with it, which is precisely
    // the moment the durable log exists for. Annotating here puts the new transaction on
    // the path callers actually use; record() keeps its own for any direct caller.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void info(String category, String message) { record(AppLogEntry.Level.INFO, category, message); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void warn(String category, String message) { record(AppLogEntry.Level.WARN, category, message); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void error(String category, String message) { record(AppLogEntry.Level.ERROR, category, message); }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AppLogEntry.Level level, String category, String message) {
        try {
            logRepository.save(new AppLogEntry(LocalDateTime.now(), level, category, truncate(message)));
        } catch (Exception ignored) {
            // Logging must never break the thing it's logging.
            return;
        }
        if (++writesSincePrune >= PRUNE_EVERY) {
            writesSincePrune = 0;
            pruneOld();
        }
    }

    @Transactional(readOnly = true)
    public List<AppLogEntry> recent() {
        return logRepository.findTop500ByOrderByCreatedAtDescIdDesc();
    }

    @Transactional
    public void clear() {
        logRepository.deleteAllInBatch();
    }

    /** Drop entries older than the configured retention window. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void pruneOld() {
        int days = settingsRepository.findById(1L)
                .map(AppSettings::getDebugLogRetentionDays).orElse(2);
        if (days > 0) {
            logRepository.deleteOlderThan(LocalDateTime.now().minusDays(days));
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= 4000 ? message : message.substring(0, 3997) + "...";
    }
}
