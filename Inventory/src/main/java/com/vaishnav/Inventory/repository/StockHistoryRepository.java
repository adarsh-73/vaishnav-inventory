package com.vaishnav.Inventory.repository;

import com.vaishnav.Inventory.entity.StockHistory;
import com.vaishnav.Inventory.entity.product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockHistoryRepository extends JpaRepository<StockHistory, Long> {

    List<StockHistory> findByProducthistory(product productData);
}
