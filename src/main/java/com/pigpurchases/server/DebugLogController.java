package com.pigpurchases.server;

import com.pigpurchases.model.AppLogEntry;
import com.pigpurchases.service.DebugLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The in-app debug log behind the Debug screen: read the recent entries, or clear them. */
@RestController
@RequestMapping("/api/debug-log")
public class DebugLogController {

    @Autowired private DebugLogService debugLog;

    @GetMapping
    public List<Map<String, Object>> recent() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AppLogEntry entry : debugLog.recent()) {
            Map<String, Object> map = new HashMap<>();
            map.put("at", entry.getCreatedAt() != null ? entry.getCreatedAt().toString() : null);
            map.put("level", entry.getLevel() != null ? entry.getLevel().name() : "INFO");
            map.put("category", entry.getCategory());
            map.put("message", entry.getMessage());
            result.add(map);
        }
        return result;
    }

    @DeleteMapping
    public void clear() {
        debugLog.clear();
    }
}
