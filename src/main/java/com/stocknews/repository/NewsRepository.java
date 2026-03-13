package com.stocknews.repository;

import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface NewsRepository extends JpaRepository<NewsArticle, Long> {
    List<NewsArticle> findByStockOrderByCollectedAtDesc(Stock stock);
    void deleteByStock(Stock stock);
}
