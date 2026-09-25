package com.example.sharding.repository;

import com.example.sharding.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link Order}.
 *
 * <p>No extra routing logic is needed here — the correct DataSource
 * is transparently selected by {@link com.example.sharding.routing.ShardReplicaRoutingDataSource}
 * based on the shard index and role set in
 * {@link com.example.sharding.context.ShardContextHolder}.</p>
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByUserId(Long userId);
}
