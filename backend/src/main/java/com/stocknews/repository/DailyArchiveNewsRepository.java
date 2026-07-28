package com.stocknews.repository;

import com.stocknews.entity.DailyArchiveNews;
import com.stocknews.entity.DailyArchiveStock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DailyArchiveNewsRepository extends JpaRepository<DailyArchiveNews, Long> {
    List<DailyArchiveNews> findByArchiveStockOrderByIdAsc(DailyArchiveStock archiveStock);
}
