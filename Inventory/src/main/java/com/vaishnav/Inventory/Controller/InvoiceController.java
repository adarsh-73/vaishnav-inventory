package com.vaishnav.Inventory.Controller;

import com.vaishnav.Inventory.entity.Invoice;
import com.vaishnav.Inventory.repository.InvoiceRepository;
import com.vaishnav.Inventory.service.InvoiceService;
import com.vaishnav.Inventory.service.InvoiceStorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.List;

@RestController
@RequestMapping("/invoices")
@CrossOrigin(originPatterns = "*")
public class InvoiceController {

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private InvoiceStorageService invoiceStorageService;

    @PostMapping
    public Invoice createInvoice(@RequestBody Invoice invoice) {
        return invoiceService.createInvoice(invoice);
    }

    @GetMapping
    public List<Invoice> getInvoices() {
        return invoiceRepository.findAll();
    }

    @GetMapping("/recent")
    public List<Invoice> getRecentInvoices(@RequestParam(defaultValue = "8") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        List<Long> ids = invoiceRepository.findRecentIds(PageRequest.of(0, safeLimit));
        Map<Long, Invoice> invoicesById = invoiceRepository.findByIdIn(ids).stream()
                .collect(Collectors.toMap(Invoice::getId, Function.identity()));
        return ids.stream().map(invoicesById::get).filter(java.util.Objects::nonNull).toList();
    }

    @GetMapping("/current-month")
    public List<Invoice> getCurrentMonthInvoices() {
        LocalDate monthStart = LocalDate.now(ZoneId.of("Asia/Kolkata")).withDayOfMonth(1);
        LocalDateTime start = monthStart.atStartOfDay();
        LocalDateTime end = monthStart.plusMonths(1).atStartOfDay();
        return invoiceRepository
                .findByInvoiceDateGreaterThanEqualAndInvoiceDateLessThanOrderByInvoiceDateDesc(start, end);
    }

    @GetMapping("/{id}")
    public Invoice getInvoice(@PathVariable Long id) {
        return invoiceRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Invoice not found"));
    }

    @PutMapping("/{id}")
    public Invoice updateInvoice(@PathVariable Long id, @RequestBody Invoice invoice) {
        return invoiceService.updateInvoice(id, invoice);
    }

    @PutMapping("/{id}/mark-paid")
    public Invoice markInvoicePaid(@PathVariable Long id) {
        return invoiceService.markInvoicePaid(id);
    }

    @PutMapping("/{id}/return-items")
    public Invoice returnInvoiceItems(@PathVariable Long id, @RequestBody InvoiceService.ReturnRequest request) {
        return invoiceService.returnInvoiceItems(id, request);
    }

    @DeleteMapping("/{id}")
    public void deleteInvoice(@PathVariable Long id) {
        invoiceService.deleteInvoice(id);
    }

    @GetMapping("/storage-stats")
    public Map<String, Object> getStorageStats() {
        return invoiceStorageService.storageStats();
    }

    @GetMapping("/cleanup-preview")
    public Map<String, Object> getCleanupPreview(@RequestParam(defaultValue = "2") int years) {
        return invoiceStorageService.cleanupPreview(years);
    }

    @DeleteMapping("/cleanup-old")
    public Map<String, Object> cleanupOldBills(@RequestParam(defaultValue = "2") int years,
                                               @RequestParam String confirmation) {
        return invoiceStorageService.cleanup(years, confirmation);
    }
}
