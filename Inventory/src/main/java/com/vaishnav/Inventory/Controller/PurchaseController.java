package com.vaishnav.Inventory.Controller;

import com.vaishnav.Inventory.entity.Purchase;
import com.vaishnav.Inventory.repository.PurchaseRepository;
import com.vaishnav.Inventory.service.PurchaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/purchases")
@CrossOrigin(originPatterns = "*")
public class PurchaseController {

    @Autowired
    private PurchaseService purchaseService;

    @Autowired
    private PurchaseRepository purchaseRepository;

    @GetMapping
    public List<Purchase> getPurchases() {
        return purchaseRepository.findAll();
    }

    @GetMapping("/recent")
    public List<Purchase> getRecentPurchases(@RequestParam(defaultValue = "20") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return purchaseRepository.findAllByOrderByPurchaseDateDescIdDesc(PageRequest.of(0, safeLimit));
    }

    @PostMapping
    public Purchase addPurchase(@RequestBody Purchase purchase) {
        return purchaseService.addPurchase(purchase);
    }
}
