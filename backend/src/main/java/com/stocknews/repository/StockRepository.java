package com.stocknews.repository;

import com.stocknews.entity.AnalysisStatus;
import com.stocknews.entity.NewsStatus;
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
     * 엔티티 전체를 save(merge) 하지 않고 분석 관련 컬럼만 UPDATE 하므로,
     * 외부 AI 호출이 끝난 뒤 짧은 트랜잭션 하나로 처리되고 뉴스 연관관계는 건드리지 않는다.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Stock s SET s.aiAnalysis = :analysis, s.analysisStatus = :status, "
            + "s.analysisModel = :model, s.analyzedAt = :analyzedAt, "
            + "s.analysisSentiment = :sentiment, s.analysisFailReason = :failReason WHERE s.id = :id")
    int updateAnalysis(@Param("id") Long id,
                       @Param("analysis") String analysis,
                       @Param("status") AnalysisStatus status,
                       @Param("model") String model,
                       @Param("analyzedAt") LocalDateTime analyzedAt,
                       @Param("sentiment") String sentiment,
                       @Param("failReason") String failReason);

    /**
     * 뉴스 수집 상태만 갱신한다. updateAnalysis 와 같은 이유로 UPDATE 쿼리를 쓴다:
     * 수집 사이클에서 넘겨받은 Stock 은 detached 이고 newsArticles 에 orphanRemoval 이 걸려 있어,
     * save(merge) 하면 방금 저장한 뉴스가 지워질 수 있다.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Stock s SET s.newsStatus = :status WHERE s.id = :id")
    int updateNewsStatus(@Param("id") Long id, @Param("status") NewsStatus status);
}
