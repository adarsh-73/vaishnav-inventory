package com.vaishnav.Inventory.repository;

import com.vaishnav.Inventory.entity.CryptoPaperTrade;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface CryptoPaperTradeRepository extends JpaRepository<CryptoPaperTrade, Long> {
    List<CryptoPaperTrade> findByStatus(String status);
    List<CryptoPaperTrade> findBySymbolAndStatus(String symbol, String status);
    List<CryptoPaperTrade> findByCreatedAtAfter(LocalDateTime date);
}
