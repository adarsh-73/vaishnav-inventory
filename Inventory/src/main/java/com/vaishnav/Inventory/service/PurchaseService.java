package com.vaishnav.Inventory.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.vaishnav.Inventory.entity.Purchase;
import com.vaishnav.Inventory.entity.StockHistory;
import com.vaishnav.Inventory.entity.DailyBookEntry;
import com.vaishnav.Inventory.entity.product;
import com.vaishnav.Inventory.repository.DailyBookEntryRepository;
import com.vaishnav.Inventory.repository.ProductRepository;
import com.vaishnav.Inventory.repository.PurchaseRepository;
import com.vaishnav.Inventory.repository.StockHistoryRepository;
@Service
public class PurchaseService {

    @Autowired
    private PurchaseRepository purchaseRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StockHistoryRepository stockHistoryRepository;

    @Autowired
    private DailyBookEntryRepository dailyBookEntryRepository;

    public Purchase addPurchase(Purchase purchase) {

        product productData = productRepository.findById(
                purchase.getProductdata().getId()
        ).orElseThrow(() -> new RuntimeException("Product not found"));

        // STOCK INCREASE
        productData.setQuantity((productData.getQuantity() == null ? 0 : productData.getQuantity()) + purchase.getQuantity());
        productData.setPurchasePrice(purchase.getPurchasePrice() == null ? 0 : purchase.getPurchasePrice());
        productRepository.save(productData);

        // STOCK HISTORY
        StockHistory history = new StockHistory();
        history.setProducthistory(productData);  // ya setProductdata(...) depending on your entity
        history.setQuantity(purchase.getQuantity());
        history.setStockType("IN");
        history.setNote("Purchase added");

        stockHistoryRepository.save(history);

        DailyBookEntry entry = new DailyBookEntry();
        entry.setEntryType("expense");
        entry.setIncomeCategory("purchase");
        entry.setPartyName("Purchase");
        entry.setNote(productData.getProductName() + " purchase - Qty " + purchase.getQuantity());
        entry.setAmount((purchase.getPurchasePrice() == null ? 0 : purchase.getPurchasePrice()) * purchase.getQuantity());
        entry.setPaymentStatus("paid");
        dailyBookEntryRepository.save(entry);

        return purchaseRepository.save(purchase);
    }
}
