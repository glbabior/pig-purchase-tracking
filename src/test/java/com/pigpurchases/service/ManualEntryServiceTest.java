package com.pigpurchases.service;

import com.pigpurchases.model.BudgetEntry;
import com.pigpurchases.model.Transaction;
import com.pigpurchases.model.TransactionMapping;
import com.pigpurchases.repository.AnalysisRunRepository;
import com.pigpurchases.repository.BudgetEntryRepository;
import com.pigpurchases.repository.DismissedDuplicateRepository;
import com.pigpurchases.repository.TransactionMappingRepository;
import com.pigpurchases.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class ManualEntryServiceTest {

    @Autowired private ManualEntryService manualEntryService;
    @Autowired private TransactionRepository txnRepo;
    @Autowired private TransactionMappingRepository mappingRepo;
    @Autowired private AnalysisRunRepository runRepo;
    @Autowired private BudgetEntryRepository entryRepo;
    @Autowired private DismissedDuplicateRepository dismissedRepo;

    private Long entryId;

    @BeforeEach
    void setUp() {
        mappingRepo.deleteAll();
        txnRepo.deleteAll();
        runRepo.deleteAll();
        entryRepo.deleteAll();
        dismissedRepo.deleteAll();
        entryId = entryRepo.save(new BudgetEntry("Dining", new BigDecimal("100.00"))).getId();
    }

    @Test
    void addManualCreatesTransactionAndMappingOnTheHiddenManualRun() {
        Transaction t = manualEntryService.addManual(LocalDate.of(2026, 6, 15), "Venmo",
                new BigDecimal("42.00"), "lunch split", entryId, false);

        assertTrue(txnRepo.findById(t.getId()).isPresent());
        List<TransactionMapping> maps = mappingRepo.findByTransactionId(t.getId());
        assertEquals(1, maps.size());
        assertEquals(TransactionMapping.Status.MAPPED_MANUAL, maps.get(0).getStatus());
        assertEquals(entryId, maps.get(0).getBudgetEntryId());
        // Its run is the hidden "manual" run, not a real month.
        assertEquals(ManualEntryService.MANUAL_MONTH,
                runRepo.findById(maps.get(0).getAnalysisRunId()).orElseThrow().getMonth());
    }

    /**
     * A refund is not a duplicate of the purchase it reverses — both are real and both
     * belong in the total. Keying on the absolute amount grouped them, so the duplicates
     * dialog offered the pair for deletion and removing the refund raised the month by its
     * amount; it also blocked a manual entry with a spurious 409 against an unrelated
     * same-day refund.
     */
    @Test
    void aRefundIsNotADuplicateOfTheChargeItReverses() {
        Transaction purchase = new Transaction(LocalDate.of(2026, 6, 15), "BIG STORE", "BIG STORE",
                new BigDecimal("89.99"), "2026-06");
        purchase.setType("PURCHASE");
        txnRepo.save(purchase);

        Transaction refund = new Transaction(LocalDate.of(2026, 6, 15), "BIG STORE REFUND", "BIG STORE",
                new BigDecimal("-89.99"), "2026-06");
        refund.setType("CREDIT");
        txnRepo.save(refund);

        assertTrue(manualEntryService.findDuplicateGroups().isEmpty(),
                "money in and money out are never the same charge recorded twice");
        assertEquals(0, manualEntryService.findPotentialDuplicates(
                        LocalDate.of(2026, 6, 15), new BigDecimal("89.99")).stream()
                        .filter(t -> "CREDIT".equals(t.getType())).count(),
                "a refund must not block an unrelated manual entry of the same size");

        // The purchase itself is still a real duplicate candidate, so the check still works.
        assertEquals(1, manualEntryService.findPotentialDuplicates(
                LocalDate.of(2026, 6, 15), new BigDecimal("89.99")).size());
    }

    @Test
    void duplicateChecksMatchOnSameDateAndAbsoluteAmount() {
        manualEntryService.addManual(LocalDate.of(2026, 6, 15), "Venmo",
                new BigDecimal("42.00"), "first", entryId, false);

        assertEquals(1, manualEntryService.findPotentialDuplicates(
                LocalDate.of(2026, 6, 15), new BigDecimal("42.00")).size());
        assertEquals(0, manualEntryService.findPotentialDuplicates(
                LocalDate.of(2026, 6, 16), new BigDecimal("42.00")).size(), "different day is not a dup");

        // A second entry same day/amount forms a duplicate group.
        manualEntryService.addManual(LocalDate.of(2026, 6, 15), "Venmo again",
                new BigDecimal("42.00"), "second", null, false);
        List<List<Transaction>> groups = manualEntryService.findDuplicateGroups();
        assertEquals(1, groups.size());
        assertEquals(2, groups.get(0).size());
    }

    @Test
    void duplicateChecksIgnoreExcludedTransactions() {
        manualEntryService.addManual(LocalDate.of(2026, 6, 15), "Keep",
                new BigDecimal("42.00"), "counts", entryId, false);
        manualEntryService.addManual(LocalDate.of(2026, 6, 15), "Transfer",
                new BigDecimal("42.00"), "not spend", null, true); // excluded

        // The excluded item is ignored, so it's neither a candidate nor part of a group.
        assertEquals(1, manualEntryService.findPotentialDuplicates(
                LocalDate.of(2026, 6, 15), new BigDecimal("42.00")).size());
        assertEquals(0, manualEntryService.findDuplicateGroups().size(),
                "an excluded transaction is not a spend duplicate");
    }

    @Test
    void dismissingAGroupStopsItBeingOffered() {
        Transaction a = manualEntryService.addManual(LocalDate.of(2026, 6, 15), "One",
                new BigDecimal("42.00"), "a", entryId, false);
        Transaction b = manualEntryService.addManual(LocalDate.of(2026, 6, 15), "Two",
                new BigDecimal("42.00"), "b", null, false);
        assertEquals(1, manualEntryService.findDuplicateGroups().size());

        manualEntryService.dismissDuplicateGroup(List.of(a.getId(), b.getId()));
        assertEquals(0, manualEntryService.findDuplicateGroups().size(), "dismissed group is not offered again");
    }

    @Test
    void deleteRemovesTransactionAndItsMapping() {
        Transaction t = manualEntryService.addManual(LocalDate.of(2026, 6, 15), "Venmo",
                new BigDecimal("42.00"), "x", null, false);
        manualEntryService.deleteTransaction(t.getId());

        assertTrue(txnRepo.findById(t.getId()).isEmpty());
        assertEquals(0, mappingRepo.findByTransactionId(t.getId()).size());
    }
}
