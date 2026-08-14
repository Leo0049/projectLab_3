package com.bizmcp.approval;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, String> {

    List<ApprovalRequest> findByStatusOrderByCreatedAtDesc(ApprovalStatus status);

    List<ApprovalRequest> findByTenantIdOrderByCreatedAtDesc(long tenantId);

    /**
     * Locks the row for the execute path. Without this, two approvals racing on
     * the same request could both pass the status check and apply the write
     * twice, which is exactly what the idempotency key exists to prevent.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ApprovalRequest r WHERE r.id = :id")
    Optional<ApprovalRequest> findByIdForUpdate(@Param("id") String id);

    @Query("SELECT r FROM ApprovalRequest r WHERE r.status = 'PENDING' AND r.expiresAt < :now")
    List<ApprovalRequest> findExpired(@Param("now") OffsetDateTime now);
}
