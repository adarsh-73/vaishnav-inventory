package com.vaishnav.Inventory.Controller;

import com.vaishnav.Inventory.entity.product;
import com.vaishnav.Inventory.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/products")
@CrossOrigin(originPatterns = "*")
public class ProductController {

    @Autowired
    private ProductService productService;

    // Add Product
    @PostMapping
    public product addProduct(@RequestBody product productdata) {
        return productService.addProduct(productdata);
    }

    // Get All Products
    @GetMapping
    public List<product> getAllProducts() {
        return productService.getAllProducts();
    }

    @GetMapping("/options")
    public List<Map<String, Object>> getProductOptions() {
        return productService.getAllProducts().stream()
                .map(productData -> {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", productData.getId());
                    row.put("productName", productData.getProductName());
                    row.put("quantity", productData.getQuantity());
                    row.put("minimumStock", productData.getMinimumStock());
                    row.put("purchasePrice", productData.getPurchasePrice());
                    row.put("sellPrice", productData.getSellPrice());
                    row.put("productLocation", productData.getProductLocation());
                    row.put("createdDate", productData.getCreatedDate());
                    row.put("updatedDate", productData.getUpdatedDate());
                    return row;
                })
                .toList();
    }

    // Get Product By Id
    @GetMapping("/{id}")
    public product getProductById(@PathVariable Long id) {
        return productService.getProductById(id);
    }

    // Update Product
    @PutMapping("/{id}")
    public product updateProduct(@PathVariable Long id,
                                 @RequestBody product productdata) {
        return productService.updateProduct(id, productdata);
    }

    // Delete Product
    @DeleteMapping("/{id}")
    public String deleteProduct(@PathVariable Long id) {
        return productService.deleteProduct(id);
    }

    // Low Stock Products
    @GetMapping("/low-stock")
    public List<product> getLowStockProducts() {
        return productService.getLowStockProducts();
    }
}
