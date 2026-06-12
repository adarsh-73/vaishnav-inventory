package com.vaishnav.Inventory.Controller;

import com.vaishnav.Inventory.entity.DailyBookEntry;
import com.vaishnav.Inventory.repository.DailyBookEntryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/daily-book")
@CrossOrigin(originPatterns = "*")
public class DailyBookController {
    @Autowired
    private DailyBookEntryRepository dailyBookEntryRepository;

    @GetMapping
    public List<DailyBookEntry> getEntries() {
        return dailyBookEntryRepository.findAll();
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
}
