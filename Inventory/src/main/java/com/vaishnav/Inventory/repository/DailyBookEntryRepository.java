package com.vaishnav.Inventory.repository;

import com.vaishnav.Inventory.entity.DailyBookEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.time.LocalDate;
import java.util.List;

public interface DailyBookEntryRepository extends JpaRepository<DailyBookEntry, Long> {
    Optional<DailyBookEntry> findFirstByNoteContainingIgnoreCase(String note);
    List<DailyBookEntry> findByEntryDateGreaterThanEqualAndEntryDateLessThanOrderByEntryDateDesc(
            LocalDate start,
            LocalDate end
    );

    @Query("""
            select coalesce(sum(coalesce(e.amount, 0)), 0)
            from DailyBookEntry e
            where e.entryDate >= :start and e.entryDate < :end
              and lower(e.entryType) = 'expense'
              and (e.paymentStatus is null or lower(e.paymentStatus) <> 'udhar')
            """)
    Double sumPaidExpenseBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("""
            select coalesce(sum(coalesce(e.amount, 0)), 0)
            from DailyBookEntry e
            where e.entryDate >= :start and e.entryDate < :end
              and lower(e.paymentStatus) = 'udhar'
              and (e.note is null or lower(e.note) not like '%invoice%')
            """)
    Double sumManualUdharBetween(@Param("start") LocalDate start, @Param("end") LocalDate end);
    int deleteByNoteContainingIgnoreCase(String note);
}
