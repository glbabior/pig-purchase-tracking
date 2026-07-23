package com.pigpurchases.server;

import com.pigpurchases.service.AnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Budget-vs-actual analysis over completed mapping runs. */
@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    @Autowired private AnalysisService analysisService;

    /** Months with a completed mapping run, newest first — drives the month picker. */
    @GetMapping("/months")
    public List<String> months() {
        return analysisService.mappedMonths();
    }

    /** Budget vs actual for one month, total and per category. */
    @GetMapping("/month/{month}")
    public AnalysisService.MonthSummary month(@PathVariable String month) {
        return analysisService.month(month);
    }

    /** Rolling average across every mapped month. */
    @GetMapping("/rolling")
    public AnalysisService.RollingSummary rolling() {
        return analysisService.rolling();
    }

    /** Per-month totals for the trend chart, oldest first. */
    @GetMapping("/trends")
    public List<AnalysisService.TrendPoint> trends() {
        return analysisService.trends();
    }

    /** No mapped run for the requested month -> 400 rather than 500. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException ex) {
        return Map.of("error", ex.getMessage() != null ? ex.getMessage() : "Bad request");
    }
}
