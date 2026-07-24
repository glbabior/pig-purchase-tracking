package com.pigpurchases.server;

import com.pigpurchases.model.AppSettings;
import com.pigpurchases.repository.AnalysisRunRepository;
import com.pigpurchases.repository.AppSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * The monthly "time to map your statements" reminder. It's due once the month
 * has reached the configured day and no mapping run has been created yet this
 * calendar month — so it clears the moment a new run is created.
 */
@RestController
@RequestMapping("/api/notification")
public class NotificationController {

    @Autowired private AppSettingsRepository appSettingsRepository;
    @Autowired private AnalysisRunRepository analysisRunRepository;

    @GetMapping("/status")
    public Map<String, Object> status() {
        int day = appSettingsRepository.findById(1L)
                .map(AppSettings::getNotificationDayOfMonth).orElse(0);

        Map<String, Object> response = new HashMap<>();
        response.put("day", day);
        response.put("enabled", day > 0);

        if (day <= 0) {
            response.put("due", false);
            return response;
        }

        LocalDate today = LocalDate.now();
        boolean reachedDay = today.getDayOfMonth() >= day;
        long runsThisMonth = analysisRunRepository.countByCreatedAtGreaterThanEqual(
                today.withDayOfMonth(1).atStartOfDay());
        boolean due = reachedDay && runsThisMonth == 0;

        response.put("due", due);
        response.put("message", "It's the " + day + "th or later and no mapping run has been "
                + "created this month — time to map the latest statements.");
        return response;
    }
}
