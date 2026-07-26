package com.pigpurchases.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A duplicate group the user has confirmed is NOT a duplicate, so it stops being
 * offered. Identified by its signature — the group's transaction ids, sorted and
 * comma-joined. If the group's membership later changes (e.g. a new same-day,
 * same-amount transaction appears), the signature differs and it resurfaces for a
 * fresh decision.
 *
 * <p>New table, so no migration backfill concern.
 */
@Entity
@Table(name = "dismissed_duplicates")
public class DismissedDuplicate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 2048, unique = true)
    private String signature;

    public DismissedDuplicate() {}

    public DismissedDuplicate(String signature) {
        this.signature = signature;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }
}
