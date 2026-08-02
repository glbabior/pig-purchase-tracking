package com.pigpurchases.server;

import com.pigpurchases.model.BudgetEntry;
import com.pigpurchases.repository.BudgetEntryRepository;
import com.pigpurchases.service.HintService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Hints screen: every {@code match:} rule across every category, in one place.
 *
 * <p>Rules are still stored as lines in {@code BudgetEntry.hints} — see {@link HintService}
 * for why that was left alone. Add and remove are deliberately <b>line-level</b> operations
 * rather than whole-field saves, so editing a rule here and editing the entry's prose on the
 * Items screen cannot silently overwrite each other.
 */
@RestController
@RequestMapping("/api")
public class HintController {

    private final HintService hintService;
    private final BudgetEntryRepository entryRepository;

    public HintController(HintService hintService, BudgetEntryRepository entryRepository) {
        this.hintService = hintService;
        this.entryRepository = entryRepository;
    }

    @GetMapping("/hints")
    public Map<String, Object> hints() {
        Map<String, Object> out = new HashMap<>();
        out.put("hints", hintService.allHints());

        // Sorted by name, not left in insertion order. The screen is a list you scan looking
        // for one category, and creation order is an order only the database knows. Sorted
        // here rather than in the page so the category picker on "add a hint" agrees with the
        // table behind it — the same list in two orders is its own small bug.
        List<BudgetEntry> sorted = new ArrayList<>(entryRepository.findAll());
        sorted.sort(Comparator.comparing(e -> e.getName() == null ? "" : e.getName(),
                String.CASE_INSENSITIVE_ORDER));

        List<Map<String, Object>> entries = new ArrayList<>();
        for (BudgetEntry e : sorted) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", e.getId());
            m.put("name", e.getName());
            entries.add(m);
        }
        out.put("entries", entries);
        return out;
    }

    /** Transactions more than one category claims — see HintService.conflicts(). */
    @GetMapping("/hints/conflicts")
    public Map<String, Object> conflicts() {
        List<HintService.Conflict> found = hintService.conflicts();
        Map<String, Object> out = new HashMap<>();
        out.put("conflicts", found);
        out.put("parking", found.stream().filter(HintService.Conflict::parks).count());
        return out;
    }

    /** What a rule would catch, without saving it. */
    @PostMapping("/hints/preview")
    public HintService.Preview preview(@RequestBody Map<String, Object> body) {
        Long entryId = body.get("entryId") == null ? null
                : Long.valueOf(String.valueOf(body.get("entryId")));
        String hint = body.get("hint") == null ? "" : String.valueOf(body.get("hint")).trim();
        if (entryId == null) {
            throw new IllegalArgumentException("Pick a category to preview the rule against.");
        }
        return hintService.preview(entryId, hint);
    }

    /**
     * Remove one rule, leaving the entry's other rules and its prose untouched.
     *
     * <p>Matched case-insensitively on the text after {@code match:}, which is how it is
     * shown, so the caller does not have to reproduce the stored line byte for byte.
     */
    @DeleteMapping("/entries/{id}/hints")
    @Transactional
    public Map<String, Object> removeHint(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String target = body.get("hint") == null ? "" : String.valueOf(body.get("hint")).trim();
        if (target.isEmpty()) {
            throw new IllegalArgumentException("Which rule should be removed?");
        }
        BudgetEntry entry = entryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such budget entry: " + id));

        StringBuilder kept = new StringBuilder();
        boolean removed = false;
        for (String line : (entry.getHints() == null ? "" : entry.getHints()).split("\\R")) {
            String trimmed = line.trim();
            boolean isTarget = trimmed.toLowerCase().startsWith("match:")
                    && trimmed.substring("match:".length()).trim().equalsIgnoreCase(target);
            if (isTarget && !removed) {
                removed = true;
                continue;
            }
            if (!kept.isEmpty()) {
                kept.append('\n');
            }
            kept.append(line);
        }
        entry.setHints(kept.toString().strip());
        entryRepository.save(entry);

        Map<String, Object> out = new HashMap<>();
        out.put("removed", removed);
        out.put("hints", entry.getHints());
        return out;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(IllegalArgumentException ex) {
        return Map.of("error", ex.getMessage() != null ? ex.getMessage() : "Bad request");
    }
}
