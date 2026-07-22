package com.pigpurchases.repository;

import com.pigpurchases.model.AppLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AppLogEntryRepository extends JpaRepository<AppLogEntry, Long> {

    // id breaks ties: several entries in one run commonly share a timestamp,
    // and the auto-increment id preserves their true insertion order.
    List<AppLogEntry> findTop500ByOrderByCreatedAtDescIdDesc();

    @Modifying
    @Query("delete from AppLogEntry e where e.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
