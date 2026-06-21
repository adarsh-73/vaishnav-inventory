package com.vaishnav.Inventory.repository;

import com.vaishnav.Inventory.entity.CryptoNewsMemory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CryptoNewsMemoryRepository extends JpaRepository<CryptoNewsMemory, Long> {
    Optional<CryptoNewsMemory> findByEventKeyAndSymbol(String eventKey, String symbol);
    List<CryptoNewsMemory> findTop500BySymbolOrderByObservedAtDesc(String symbol);
}
