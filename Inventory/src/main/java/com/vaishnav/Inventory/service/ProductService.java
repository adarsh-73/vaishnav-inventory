package com.vaishnav.Inventory.service;
import com.vaishnav.Inventory.entity.product;
import com.vaishnav.Inventory.entity.InvoiceItem;
import com.vaishnav.Inventory.entity.Purchase;
import com.vaishnav.Inventory.entity.StockHistory;
import com.vaishnav.Inventory.repository.InvoiceItemRepository;
import com.vaishnav.Inventory.repository.ProductRepository;
import com.vaishnav.Inventory.repository.PurchaseRepository;
import com.vaishnav.Inventory.repository.StockHistoryRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductService {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InvoiceItemRepository invoiceItemRepository;

    @Autowired
    private PurchaseRepository purchaseRepository;

    @Autowired
    private StockHistoryRepository stockHistoryRepository;

    // Add Product
    public product addProduct(product productData) {
        normalizeProduct(productData);

        // default stock
        if (productData.getQuantity() == null) {
            productData.setQuantity(0);
        }

        if (productData.getMinimumStock() == null) {
            productData.setMinimumStock(1);
        }

        ensureUniqueNewProductCode(productData);

        return productRepository.save(productData);
    }

    // Get All Products
    public List<product> getAllProducts() {
        return productRepository.findAll();
    }

    // Get Product By Id
    public product getProductById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found"));
    }

    // Delete Product
    @Transactional
    public String deleteProduct(Long id) {

        product productData = getProductById(id);

        List<InvoiceItem> invoiceItems = invoiceItemRepository.findByProductInvoiceitem(productData);
        for (InvoiceItem item : invoiceItems) {
            if (item.getDescription() == null || item.getDescription().isBlank()) {
                item.setDescription(productData.getProductName());
            }
            item.setProductInvoiceitem(null);
        }
        invoiceItemRepository.saveAll(invoiceItems);

        List<Purchase> purchases = purchaseRepository.findByProductdata(productData);
        for (Purchase purchase : purchases) {
            purchase.setProductdata(null);
        }
        purchaseRepository.saveAll(purchases);

        List<StockHistory> stockHistories = stockHistoryRepository.findByProducthistory(productData);
        for (StockHistory history : stockHistories) {
            history.setProducthistory(null);
        }
        stockHistoryRepository.saveAll(stockHistories);

        productRepository.delete(productData);

        return "Product Deleted Successfully";
    }

    // Update Product (Clean Update)
    public product updateProduct(Long id, product updatedProduct) {

        product existingProduct = getProductById(id);

        // Only update fields if not null (professional approach)

        if (updatedProduct.getProductName() != null)
            existingProduct.setProductName(updatedProduct.getProductName());

        if (updatedProduct.getBrand() != null)
            existingProduct.setBrand(updatedProduct.getBrand());

        if (updatedProduct.getMake() != null)
            existingProduct.setMake(updatedProduct.getMake());

        if (updatedProduct.getModel() != null)
            existingProduct.setModel(updatedProduct.getModel());

        if (updatedProduct.getSerialNumber() != null)
            existingProduct.setSerialNumber(updatedProduct.getSerialNumber());

        if (updatedProduct.getCategory() != null)
            existingProduct.setCategory(updatedProduct.getCategory());

        if (updatedProduct.getBarcode() != null) {
            String barcode = clean(updatedProduct.getBarcode());
            if (barcode != null && productRepository.existsByBarcodeAndIdNot(barcode, id)) {
                throw new IllegalArgumentException("Barcode already kisi aur product me hai. Barcode change karo ya blank chhod do.");
            }
            existingProduct.setBarcode(updatedProduct.getBarcode());
        }

        if (updatedProduct.getQuantity() != null)
            existingProduct.setQuantity(updatedProduct.getQuantity());

        if (updatedProduct.getMinimumStock() != null)
            existingProduct.setMinimumStock(updatedProduct.getMinimumStock());

        if (updatedProduct.getPurchasePrice() != null)
            existingProduct.setPurchasePrice(updatedProduct.getPurchasePrice());

        if (updatedProduct.getSellPrice() != null)
            existingProduct.setSellPrice(updatedProduct.getSellPrice());

        if (updatedProduct.getProductLocation() != null)
            existingProduct.setProductLocation(updatedProduct.getProductLocation());

        if (updatedProduct.getDescription() != null)
            existingProduct.setDescription(updatedProduct.getDescription());

        return productRepository.save(existingProduct);
    }

    // Low Stock Products
public List<product> getLowStockProducts() {
    return productRepository.findLowStockProducts();
}

    private void normalizeProduct(product productData) {
        productData.setProductName(clean(productData.getProductName()));
        productData.setBrand(clean(productData.getBrand()));
        productData.setMake(clean(productData.getMake()));
        productData.setModel(clean(productData.getModel()));
        productData.setSerialNumber(clean(productData.getSerialNumber()));
        productData.setCategory(clean(productData.getCategory()));
        productData.setBarcode(clean(productData.getBarcode()));
        productData.setProductLocation(clean(productData.getProductLocation()));
        productData.setDescription(clean(productData.getDescription()));
    }

    private String clean(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private void ensureUniqueNewProductCode(product productData) {
        String barcode = productData.getBarcode();
        if (barcode != null && !productRepository.existsByBarcode(barcode)) return;

        String nextCode = nextProductCode();
        productData.setBarcode(nextCode);
        if (productData.getSerialNumber() == null || productData.getSerialNumber().isBlank() || barcode != null) {
            productData.setSerialNumber(nextCode);
        }
    }

    private String nextProductCode() {
        int maxCode = productRepository.findAll().stream()
                .mapToInt(item -> Math.max(numberFrom(item.getSerialNumber()), numberFrom(item.getBarcode())))
                .max()
                .orElse(0);

        String code;
        do {
            maxCode++;
            code = String.valueOf(maxCode);
        } while (productRepository.existsByBarcode(code));
        return code;
    }

    private int numberFrom(String value) {
        if (value == null) return 0;
        String digits = value.replaceAll("\\D", "");
        if (digits.isBlank()) return 0;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
