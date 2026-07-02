package com.vaishnav.Inventory.repository;

import com.vaishnav.Inventory.entity.AccessoryCatalogItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AccessoryCatalogRepository extends JpaRepository<AccessoryCatalogItem, Long>,
        JpaSpecificationExecutor<AccessoryCatalogItem> {

    Optional<AccessoryCatalogItem> findByBarcodeIgnoreCase(String barcode);

    Optional<AccessoryCatalogItem> findBySkuIgnoreCase(String sku);

    boolean existsByBarcodeIgnoreCaseAndIdNot(String barcode, Long id);

    boolean existsBySkuIgnoreCaseAndIdNot(String sku, Long id);
}
