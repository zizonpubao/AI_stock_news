package com.stocknews.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

    // 아카이브 시점의 분석 상태. 기존 행(컬럼 추가 전)은 null
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AnalysisStatus analysisStatus;

    // 분석 근거 정보. enum 이 아닌 일반 컬럼이다(운영 DB 의 enum CHECK 제약과 무관하게 값을 추가할 수 있도록).
    // 기존 행(컬럼 추가 전)은 모두 null
    @Column(length = 100) private String analysisModel;      // 실제로 응답한 모델 이름
    private LocalDateTime analyzedAt;                        // 분석 저장 시각 (성공했을 때만)
    @Column(length = 20) private String analysisSentiment;   // POSITIVE / NEUTRAL / NEGATIVE, 판단 불가면 null
    @Column(length = 30) private String analysisFailReason;  // FAILED 일 때 사유 (AnalysisFailReason 이름)

    // 아카이브 시점의 뉴스 수집 상태. 기존 행(컬럼 추가 전)은 null
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private NewsStatus newsStatus;

    @OneToMany(mappedBy = "archiveStock", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DailyArchiveNews> news = new ArrayList<>();
}
