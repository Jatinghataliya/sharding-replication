package com.example.sharding.repository;

import com.example.sharding.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for idempotency records.
 *
 * <p>Stored on the same shard as the corresponding order so every
 * lookup is a single, local query with no cross-shard join.</p>
 */
@Repository
public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, String> {
}
