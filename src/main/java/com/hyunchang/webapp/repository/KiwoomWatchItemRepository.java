package com.hyunchang.webapp.repository;
import com.hyunchang.webapp.entity.KiwoomWatchItem; import java.util.*; import org.springframework.data.jpa.repository.JpaRepository;
public interface KiwoomWatchItemRepository extends JpaRepository<KiwoomWatchItem,Long>{ boolean existsByStockCode(String stockCode); Optional<KiwoomWatchItem> findByStockCode(String stockCode); }
