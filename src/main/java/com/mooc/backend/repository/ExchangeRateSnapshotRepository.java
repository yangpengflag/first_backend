package com.mooc.backend.repository;

import com.mooc.backend.entity.ExchangeRateSnapshot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * 汇率快照仓储（change: add-travel-services，task 3.4）。
 *
 * <p>不加 {@code findByXxxAndDeletedFalse} 这类软删过滤：本表是单行覆盖写的技术缓存，
 * 没有软删语义（{@code deleted} 恒为 false），读路径只有 {@code findById(SINGLETON_ID)}。
 */
public interface ExchangeRateSnapshotRepository extends JpaRepository<ExchangeRateSnapshot, UUID> {
}
