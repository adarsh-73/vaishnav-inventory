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
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;
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

    @Autowired
    private ProductService productService;

    @Transactional
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
        entry.setIncomeCategory("parts");
        entry.setPartyName("Purchase");
        entry.setNote(productData.getProductName() + " purchase - Qty " + purchase.getQuantity());
        entry.setAmount((purchase.getPurchasePrice() == null ? 0 : purchase.getPurchasePrice()) * purchase.getQuantity());
        entry.setPaymentStatus("paid");
        dailyBookEntryRepository.save(entry);

        return purchaseRepository.save(purchase);
    }

    @Transactional
    public List<Purchase> importBillLines(List<PurchaseImportLine> lines) {
        if (lines == null || lines.isEmpty()) throw new IllegalArgumentException("Bill me import karne ke liye items nahi mile");
        for (PurchaseImportLine line : lines) {
            if (line.getQuantity() == null || line.getQuantity() <= 0) throw new IllegalArgumentException("Har item ki quantity 0 se zyada honi chahiye");
            if (line.getPurchasePrice() == null || line.getPurchasePrice() < 0) throw new IllegalArgumentException("Har item ka buy price check karein");
            if (line.getProductId() == null && (line.getProductName() == null || line.getProductName().isBlank())) {
                throw new IllegalArgumentException("Naye item ka naam bharna zaroori hai");
            }
            if (line.getProductId() == null && (line.getSellPrice() == null || line.getSellPrice() <= 0)) {
                throw new IllegalArgumentException("Naye product ka sell price bharna zaroori hai");
            }
        }

        List<Purchase> savedPurchases = new ArrayList<>();
        for (PurchaseImportLine line : lines) {
            product productData;
            if (line.getProductId() != null) {
                productData = productRepository.findById(line.getProductId())
                        .orElseThrow(() -> new RuntimeException("Selected product nahi mila"));
                if (line.getSellPrice() != null && line.getSellPrice() > 0) productData.setSellPrice(line.getSellPrice());
                productRepository.save(productData);
            } else {
                product newProduct = new product();
                newProduct.setProductName(line.getProductName().trim());
                newProduct.setQuantity(0);
                newProduct.setMinimumStock(1);
                newProduct.setSellPrice(line.getSellPrice());
                newProduct.setPurchasePrice(line.getPurchasePrice());
                productData = productService.addProduct(newProduct);
            }

            Purchase purchase = new Purchase();
            purchase.setProductdata(productData);
            purchase.setQuantity(line.getQuantity());
            purchase.setPurchasePrice(line.getPurchasePrice());
            purchase.setInvoiceNumber(line.getInvoiceNumber());
            savedPurchases.add(addPurchase(purchase));
        }
        return savedPurchases;
    }

    public static class PurchaseImportLine {
        private Long productId;
        private String productName;
        private Integer quantity;
        private Double purchasePrice;
        private Double sellPrice;
        private String invoiceNumber;

        public Long getProductId() { return productId; }
        public void setProductId(Long productId) { this.productId = productId; }
        public String getProductName() { return productName; }
        public void setProductName(String productName) { this.productName = productName; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
        public Double getPurchasePrice() { return purchasePrice; }
        public void setPurchasePrice(Double purchasePrice) { this.purchasePrice = purchasePrice; }
        public Double getSellPrice() { return sellPrice; }
        public void setSellPrice(Double sellPrice) { this.sellPrice = sellPrice; }
        public String getInvoiceNumber() { return invoiceNumber; }
        public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }
    }
}
