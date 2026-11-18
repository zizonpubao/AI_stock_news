package com.stocknews.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "stocks")
@Getter @Setter @NoArgsConstructor
public class Stock {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private String code;
    @Column(nullable = false) private String name;
    private String currentPrice;
    private String changeRate;
    private String changePrice;
    private String volume;
    private Long tradingValue;   // 거래대금(원). 실시간 API의 통합(KRX+NXT) 거래대금
    private String marketSession; // REGULAR / PRE(프리장) / AFTER(애프터마켓) / CLOSED
    private Integer ranking;

    // 추가 지표
    private String high52Week;   // 52주 신고가
    private String per;          // PER
    private String estimatedPer; // 추정 PER
    private String pbr;          // PBR

    @Column(columnDefinition = "TEXT")
    private String aiAnalysis;

    // 분석 상태. 기존 행(컬럼 추가 전)은 null 일 수 있음
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AnalysisStatus analysisStatus = AnalysisStatus.PENDING;

    // 분석 근거 정보. enum 이 아닌 일반 컬럼이다(운영 DB 의 enum CHECK 제약과 무관하게 값을 추가할 수 있도록).
    // 기존 행(컬럼 추가 전)은 모두 null
    @Column(length = 100) private String analysisModel;      // 실제로 응답한 모델 이름
    private LocalDateTime analyzedAt;                        // 분석 저장 시각 (성공했을 때만)
    @Column(length = 20) private String analysisSentiment;   // POSITIVE / NEUTRAL / NEGATIVE, 판단 불가면 null
    @Column(length = 30) private String analysisFailReason;  // FAILED 일 때 사유 (AnalysisFailReason 이름)

    // 뉴스 수집 상태. 기존 행(컬럼 추가 전)은 null 일 수 있음
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private NewsStatus newsStatus = NewsStatus.PENDING;

    @Column(name = "updated_at") private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "stock", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<NewsArticle> newsArticles = new ArrayList<>();

    @PrePersist @PreUpdate
    public void updateTimestamp() { this.updatedAt = LocalDateTime.now(); }
}