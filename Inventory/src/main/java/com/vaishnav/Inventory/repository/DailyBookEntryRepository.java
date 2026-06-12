package com.vaishnav.Inventory.repository;

import com.vaishnav.Inventory.entity.DailyBookEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DailyBookEntryRepository extends JpaRepository<DailyBookEntry, Long> {
    Optional<DailyBookEntry> findFirstByNoteContainingIgnoreCase(String note);
}
