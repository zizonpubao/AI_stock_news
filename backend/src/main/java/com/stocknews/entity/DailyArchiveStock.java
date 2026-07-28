package com.stocknews.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 날짜별 아카이브 종목 (매 거래일 종가 시점 스냅샷, append-only).
 * 현재 화면용 {@link Stock} 과 별개로 과거 조회를 위해 누적 저장한다.
 */
@Entity
@Table(name = "daily_stock",
        indexes = @Index(name = "idx_daily_stock_date", columnList = "snapshot_date"))
@Getter @Setter @NoArgsConstructor
public class DailyArchiveStock {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    private String code;
    private String name;
    private Integer ranking;
    private String currentPrice;
    private String changeRate;
    private String changePrice;
    private String volume;
    private Long tradingValue;
    private String high52Week;
    private String per;
    private String estimatedPer;
    private String pbr;

    @Column(columnDefinition = "TEXT")
    private String aiAnalysis;

    @OneToMany(mappedBy = "archiveStock", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DailyArchiveNews> news = new ArrayList<>();
}
