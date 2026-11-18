package com.stocknews.repository;

import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface NewsRepository extends JpaRepository<NewsArticle, Long> {
    List<NewsArticle> findByStockOrderByCollectedAtDesc(Stock stock);
    /** 저장 순서(id 오름차순). AI 분석의 인용 번호와 화면의 기사 순서가 이 순서로 일치한다. */
    List<NewsArticle> findByStockOrderByIdAsc(Stock stock);
    void deleteByStock(Stock stock);
}
