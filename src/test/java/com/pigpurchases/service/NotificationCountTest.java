package com.pigpurchases.service;

import com.pigpurchases.model.AnalysisRun;
import com.pigpurchases.repository.AnalysisRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The reminder is "due" partly on whether any run was created this month, which
 * relies on this count-since-cutoff query. Fixed timestamps keep it deterministic.
 */
@SpringBootTest
@ActiveProfiles("test")
class NotificationCountTest {

    @Autowired private AnalysisRunRepository runRepo;

    @BeforeEach
    void reset() {
        runRepo.deleteAll();
    }

    @Test
    void countsOnlyRunsCreatedOnOrAfterTheCutoff() {
        runRepo.save(new AnalysisRun("2026-01", LocalDateTime.of(2026, 1, 15, 9, 0)));
        runRepo.save(new AnalysisRun("2026-06", LocalDateTime.of(2026, 6, 15, 9, 0)));

        LocalDateTime firstOfJune = LocalDateTime.of(2026, 6, 1, 0, 0);
        assertEquals(1, runRepo.countByCreatedAtGreaterThanEqual(firstOfJune),
                "only the June-created run counts as 'this month'");
        assertEquals(2, runRepo.countByCreatedAtGreaterThanEqual(LocalDateTime.of(2026, 1, 1, 0, 0)));
        assertEquals(0, runRepo.countByCreatedAtGreaterThanEqual(LocalDateTime.of(2026, 7, 1, 0, 0)));
    }
}
