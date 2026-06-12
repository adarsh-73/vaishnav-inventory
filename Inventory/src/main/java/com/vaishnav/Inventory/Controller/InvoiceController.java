package com.vaishnav.Inventory.Controller;

import com.vaishnav.Inventory.entity.Invoice;
import com.vaishnav.Inventory.repository.InvoiceRepository;
import com.vaishnav.Inventory.service.InvoiceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/invoices")
@CrossOrigin(originPatterns = "*")
public class InvoiceController {

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @PostMapping
    public Invoice createInvoice(@RequestBody Invoice invoice) {
        return invoiceService.createInvoice(invoice);
    }

    @GetMapping
    public List<Invoice> getInvoices() {
        return invoiceRepository.findAll();
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
}
