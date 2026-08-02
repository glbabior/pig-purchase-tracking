package com.pigpurchases.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pigpurchases.model.BudgetEntry;
import com.pigpurchases.repository.BudgetEntryRepository;
import com.pigpurchases.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Matching Hints screen's payload.
 *
 * <p>The screen renders one row per category straight from {@code entries}, and the "add a
 * hint" picker is built from the same list. Order therefore has to be settled here rather
 * than in the page, or the table and the picker can disagree.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HintControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private BudgetEntryRepository entryRepo;
    @Autowired private TransactionRepository txnRepo;

    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void reset() {
        txnRepo.deleteAll();
        entryRepo.deleteAll();
    }

    private void entry(String name) {
        entryRepo.save(new BudgetEntry(name, new BigDecimal("50.00")));
    }

    @Test
    void categoriesComeBackSortedByNameNotByWhenTheyWereCreated() throws Exception {
        // Saved deliberately out of order: creation order is an order only the database knows,
        // and the screen is a list you scan looking for one category.
        entry("Utilities");
        entry("roadster");   // lower case, to pin the comparison as case-insensitive
        entry("Groceries");
        entry("Dining");

        JsonNode body = om.readTree(mvc.perform(get("/api/hints"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        List<String> names = new ArrayList<>();
        body.get("entries").forEach(e -> names.add(e.get("name").asText()));

        assertEquals(List.of("roadster", "Dining", "Groceries", "Utilities"), names,
                "sorted by name, and 'roadster' must not sort after 'Utilities' for its case");
    }

    @Test
    void everyCategoryIsListedEvenWithNoHintsAtAll() throws Exception {
        // The point of the screen: a category nothing matches is the one worth seeing. Listing
        // only categories that already have hints makes its absence read as "no such category".
        entry("Roadster");
        entry("Dining");
        entryRepo.findAll().stream()
                .filter(e -> "Dining".equals(e.getName()))
                .forEach(e -> { e.setHints("match: DAILYGRIND"); entryRepo.save(e); });

        JsonNode body = om.readTree(mvc.perform(get("/api/hints"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertEquals(2, body.get("entries").size(), "the hint-less category must still be listed");
        assertEquals(1, body.get("hints").size(), "only one category actually has a rule");
    }
}
