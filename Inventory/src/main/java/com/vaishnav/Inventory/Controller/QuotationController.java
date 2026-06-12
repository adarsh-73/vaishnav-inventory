package com.vaishnav.Inventory.Controller;

import com.vaishnav.Inventory.entity.Quotation;
import com.vaishnav.Inventory.repository.QuotationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/quotations")
@CrossOrigin(originPatterns = "*")
public class QuotationController {
    @Autowired
    private QuotationRepository quotationRepository;

    @GetMapping
    public List<Quotation> getQuotations() {
        return quotationRepository.findAll();
    }

    @PostMapping
    public Quotation addQuotation(@RequestBody Quotation quotation) {
        return quotationRepository.save(quotation);
    }

    @PutMapping("/{id}")
    public Quotation updateQuotation(@PathVariable Long id, @RequestBody Quotation quotation) {
        Quotation existing = quotationRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Quotation not found"));
        existing.setQuotationNumber(quotation.getQuotationNumber());
        existing.setQuotationDate(quotation.getQuotationDate());
        existing.setCustomerName(quotation.getCustomerName());
        existing.setMobileNumber(quotation.getMobileNumber());
        existing.setVehicleNumber(quotation.getVehicleNumber());
        existing.setItemsJson(quotation.getItemsJson());
        existing.setTotalAmount(quotation.getTotalAmount());
        existing.setNote(quotation.getNote());
        return quotationRepository.save(existing);
    }

    @DeleteMapping("/{id}")
    public String deleteQuotation(@PathVariable Long id) {
        quotationRepository.deleteById(id);
        return "Quotation deleted";
    }
}
