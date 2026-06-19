package com.vaishnav.Inventory.repository;

import com.vaishnav.Inventory.entity.CryptoDecisionAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;

public interface CryptoDecisionAuditRepository extends JpaRepository<CryptoDecisionAudit, Long> {
    List<CryptoDecisionAudit> findTop50ByOrderByIdDesc();
    long countByCreatedAtAfter(LocalDateTime date);
}
