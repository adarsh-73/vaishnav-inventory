package com.vaishnav.Inventory.repository;

import com.vaishnav.Inventory.entity.DailyBookEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.time.LocalDate;
import java.util.List;

public interface DailyBookEntryRepository extends JpaRepository<DailyBookEntry, Long> {
    Optional<DailyBookEntry> findFirstByNoteContainingIgnoreCase(String note);
    List<DailyBookEntry> findByEntryDateGreaterThanEqualAndEntryDateLessThanOrderByEntryDateDesc(
            LocalDate start,
            LocalDate end
    );
    int deleteByNoteContainingIgnoreCase(String note);
}
