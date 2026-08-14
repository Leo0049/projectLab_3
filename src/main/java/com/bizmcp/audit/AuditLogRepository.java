package com.bizmcp.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByToolNameOrderByIdDesc(String toolName);

    List<AuditLog> findByTenantIdOrderByIdDesc(long tenantId);

    List<AuditLog> findByDecisionOrderByIdDesc(String decision);
}
