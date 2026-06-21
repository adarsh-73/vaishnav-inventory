package com.vaishnav.Inventory.repository;

import com.vaishnav.Inventory.entity.CryptoLiquidationMemory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface CryptoLiquidationMemoryRepository extends JpaRepository<CryptoLiquidationMemory, Long> {
    List<CryptoLiquidationMemory> findBySymbolAndEventTimeAfterOrderByEventTimeDesc(String symbol, LocalDateTime after);
}
