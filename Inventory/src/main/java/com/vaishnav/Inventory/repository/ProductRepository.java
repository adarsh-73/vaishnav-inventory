package com.vaishnav.Inventory.repository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import com.vaishnav.Inventory.entity.product;

import java.util.Optional;
import java.util.List;

@Repository
public interface ProductRepository extends JpaRepository<product, Long> {

    // ✅ 1. Duplicate check (fast and efficient)
    boolean existsByBarcode(String barcode);

    // ✅ 2. Find by barcode (used in validation / sell flow)
    Optional<product> findByBarcode(String barcode);

    // ✅ 3. Search by product name (for UI / search feature)
    List<product> findByProductNameContainingIgnoreCase(String name);

    // ✅ 4. Filter low stock products (important for business)
    List<product> findByQuantityLessThan(Integer quantity);

    // ✅ 5. Exact match (rare but useful)
    Optional<product> findByProductName(String productName);

    Optional<product> findFirstByProductNameIgnoreCase(String productName);

    List<product> findByProductNameIgnoreCase(String productName);

    List<product> findByProductLocation(String productLocation);

    @Query("SELECT p FROM product p WHERE p.quantity <= p.minimumStock")
List<product> findLowStockProducts();
}
