package com.vaishnav.Inventory.repository;

import com.vaishnav.Inventory.entity.CryptoExchangeConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CryptoExchangeConnectionRepository extends JpaRepository<CryptoExchangeConnection, Long> {
    Optional<CryptoExchangeConnection> findFirstByExchangeNameOrderByIdDesc(String exchangeName);
}
