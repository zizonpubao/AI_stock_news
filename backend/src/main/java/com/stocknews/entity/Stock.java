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
    private Long tradingValue;   // 거래대금(원) = 현재가 × 거래량 (프론트에서 조 단위 표시)
    private Integer ranking;

    // 추가 지표
    private String high52Week;   // 52주 신고가
    private String per;          // PER
    private String estimatedPer; // 추정 PER
    private String pbr;          // PBR

    @Column(columnDefinition = "TEXT")
    private String aiAnalysis;

    @Column(name = "updated_at") private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "stock", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<NewsArticle> newsArticles = new ArrayList<>();

    @PrePersist @PreUpdate
    public void updateTimestamp() { this.updatedAt = LocalDateTime.now(); }
}