package com.vaishnav.Inventory.Controller;

import com.vaishnav.Inventory.entity.DailyBookEntry;
import com.vaishnav.Inventory.repository.DailyBookEntryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;

@RestController
@RequestMapping("/daily-book")
@CrossOrigin(originPatterns = "*")
public class DailyBookController {
    @Autowired
    private DailyBookEntryRepository dailyBookEntryRepository;

    @GetMapping
    public List<DailyBookEntry> getEntries(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "100") int size) {
        return dailyBookEntryRepository.findAll(PageRequest.of(
                safePage(page),
                safeSize(size),
                Sort.by(Sort.Direction.DESC, "entryDate").and(Sort.by(Sort.Direction.DESC, "id"))
        )).getContent();
    }

    @GetMapping("/current-month")
    public List<DailyBookEntry> getCurrentMonthEntries() {
        LocalDate start = LocalDate.now(ZoneId.of("Asia/Kolkata")).withDayOfMonth(1);
        return dailyBookEntryRepository
                .findByEntryDateGreaterThanEqualAndEntryDateLessThanOrderByEntryDateDesc(start, start.plusMonths(1));
    }

    @GetMapping("/month")
    public List<DailyBookEntry> getMonthEntries(@RequestParam String month) {
        YearMonth yearMonth = YearMonth.parse(month);
        LocalDate start = yearMonth.atDay(1);
        return dailyBookEntryRepository
                .findByEntryDateGreaterThanEqualAndEntryDateLessThanOrderByEntryDateDesc(start, start.plusMonths(1));
    }

    @GetMapping("/udhar")
    public List<DailyBookEntry> getManualUdhar(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "200") int size) {
        return dailyBookEntryRepository.findManualUdhar(PageRequest.of(safePage(page), safeSize(size)));
    }

    @PostMapping
    public DailyBookEntry addEntry(@RequestBody DailyBookEntry entry) {
        return dailyBookEntryRepository.save(entry);
    }

    @PutMapping("/{id}")
    public DailyBookEntry updateEntry(@PathVariable Long id, @RequestBody DailyBookEntry entry) {
        DailyBookEntry existing = dailyBookEntryRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Daily book entry not found"));
        existing.setEntryDate(entry.getEntryDate());
        existing.setEntryType(entry.getEntryType());
        existing.setIncomeCategory(entry.getIncomeCategory());
        existing.setPartyName(entry.getPartyName());
        existing.setNote(entry.getNote());
        existing.setAmount(entry.getAmount());
        existing.setPaymentStatus(entry.getPaymentStatus());
        return dailyBookEntryRepository.save(existing);
    }

    @DeleteMapping("/{id}")
    public String deleteEntry(@PathVariable Long id) {
        dailyBookEntryRepository.deleteById(id);
        return "Daily book entry deleted";
    }

    private int safePage(int page) {
        return Math.max(0, page);
    }

    private int safeSize(int size) {
        return Math.max(1, Math.min(size, 500));
    }
}
