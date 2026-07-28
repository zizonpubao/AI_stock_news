package com.stocknews.repository;

import com.stocknews.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, Long> {
    Optional<Stock> findByCode(String code);
    @Query("SELECT s FROM Stock s ORDER BY s.ranking ASC")
    List<Stock> findAllByOrderByRankingAsc();
}
