package com.stocknews.repository;

import com.stocknews.entity.AnalysisStatus;
import com.stocknews.entity.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, Long> {
    Optional<Stock> findByCode(String code);
    @Query("SELECT s FROM Stock s ORDER BY s.ranking ASC")
    List<Stock> findAllByOrderByRankingAsc();

    /** 마지막으로 수집에 성공한 시각 (종목이 하나도 없으면 null) */
    @Query("SELECT MAX(s.updatedAt) FROM Stock s")
    LocalDateTime findLastCollectedAt();

    /**
     * 분석 결과만 갱신한다.
     * 엔티티 전체를 save(merge) 하지 않고 두 컬럼만 UPDATE 하므로,
     * 외부 AI 호출이 끝난 뒤 짧은 트랜잭션 하나로 처리되고 뉴스 연관관계는 건드리지 않는다.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Stock s SET s.aiAnalysis = :analysis, s.analysisStatus = :status WHERE s.id = :id")
    int updateAnalysis(@Param("id") Long id,
                       @Param("analysis") String analysis,
                       @Param("status") AnalysisStatus status);
}
