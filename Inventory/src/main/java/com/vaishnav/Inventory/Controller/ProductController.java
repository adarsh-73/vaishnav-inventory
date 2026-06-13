package com.vaishnav.Inventory.Controller;

import com.vaishnav.Inventory.entity.product;
import com.vaishnav.Inventory.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    @ExceptionHandler({IllegalArgumentException.class, RuntimeException.class})
    public ResponseEntity<String> handleProductError(RuntimeException error) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error.getMessage());
    }
}
