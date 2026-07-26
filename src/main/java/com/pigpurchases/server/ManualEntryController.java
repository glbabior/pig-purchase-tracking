package com.pigpurchases.server;

import com.pigpurchases.model.StatementSource;
import com.pigpurchases.model.Transaction;
import com.pigpurchases.repository.StatementSourceRepository;
import com.pigpurchases.service.ManualEntryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Manual transaction entry (from the Ingest screen), with a same-day/same-amount dup check. */
@RestController
@RequestMapping("/api/transactions")
public class ManualEntryController {

    private final ManualEntryService manualEntryService;
    private final StatementSourceRepository sourceRepository;

    public ManualEntryController(ManualEntryService manualEntryService,
                                 StatementSourceRepository sourceRepository) {
        this.manualEntryService = manualEntryService;
        this.sourceRepository = sourceRepository;
    }

    /**
     * Add a manual transaction. Without {@code force}, a same-day/same-amount match
     * returns 409 with the candidates so the UI can confirm before committing.
     */
    @PostMapping("/manual")
    public ResponseEntity<Map<String, Object>> addManual(@RequestBody Map<String, Object> body) {
        LocalDate date;
        BigDecimal amount;
        try {
            date = LocalDate.parse(String.valueOf(body.get("date")));
            amount = new BigDecimal(String.valueOf(body.get("amount")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("message", "A valid date and amount are required."));
        }
        String vendor = str(body.get("vendor"));
        String description = str(body.get("description"));
        boolean excluded = Boolean.TRUE.equals(body.get("excluded"));
        Long budgetEntryId = body.get("budgetEntryId") == null ? null
                : ((Number) body.get("budgetEntryId")).longValue();
        boolean force = Boolean.TRUE.equals(body.get("force"));

        if (!force) {
            List<Transaction> dups = manualEntryService.findPotentialDuplicates(date, amount);
            if (!dups.isEmpty()) {
                List<Map<String, Object>> views = new ArrayList<>();
                for (Transaction t : dups) {
                    views.add(dupView(t));
                }
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("status", "duplicates", "duplicates", views));
            }
        }

        try {
            Transaction t = manualEntryService.addManual(date, vendor, amount, description, budgetEntryId, excluded);
            return ResponseEntity.ok(Map.of("status", "created", "id", t.getId()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /** Delete a transaction and its mapping(s) — used to resolve duplicates and remove mistaken manual entries. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        manualEntryService.deleteTransaction(id);
        return ResponseEntity.ok(Map.of("status", "deleted", "id", id));
    }

    private Map<String, Object> dupView(Transaction t) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", t.getId());
        m.put("date", t.getTransactionDate() != null ? t.getTransactionDate().toString() : null);
        m.put("vendor", t.getVendor());
        m.put("description", t.getDescription());
        m.put("amount", t.getAmount());
        String source = "Manual";
        if (t.getStatementSourceId() != null) {
            source = sourceRepository.findById(t.getStatementSourceId())
                    .map(StatementSource::getName).orElse("Statement");
        }
        m.put("source", source);
        return m;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
