package com.stocknews.repository;

import com.stocknews.entity.DailyArchiveStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface DailyArchiveStockRepository extends JpaRepository<DailyArchiveStock, Long> {

    List<DailyArchiveStock> findBySnapshotDateOrderByRankingAsc(LocalDate snapshotDate);

    void deleteBySnapshotDate(LocalDate snapshotDate);

    boolean existsBySnapshotDate(LocalDate snapshotDate);

    /** 아카이브가 존재하는 날짜 목록 (최신순) — 날짜 선택기용 */
    @Query("select distinct d.snapshotDate from DailyArchiveStock d order by d.snapshotDate desc")
    List<LocalDate> findDistinctDates();
}
