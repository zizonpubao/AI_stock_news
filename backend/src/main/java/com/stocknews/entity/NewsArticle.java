package com.stocknews.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "news_articles")
@Getter @Setter @NoArgsConstructor
public class NewsArticle {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id")
    private Stock stock;
    @Column(nullable = false, length = 500) private String title;
    @Column(length = 1000) private String description;
    @Column(length = 500) private String link;
    private String pubDate;
    @Column(name = "collected_at") private LocalDateTime collectedAt;


    /** 본문 수집 결과 상태 (ArticleBodyFetcher 의 상태값). 본문을 시도하지 않은 과거 기사는 null. */
    @Column(name = "body_status", length = 30) private String bodyStatus;
    /** 본문을 사용했을 때 AI 에 넣은 글자 수 (본문을 쓰지 않았으면 null) */
    @Column(name = "body_chars") private Integer bodyChars;

    /** 네이버 응답의 originallink (언론사 원문 주소). DB 에 저장하지 않는다. */
    @Transient @JsonIgnore private String originalLink;
    /** 추출한 본문. DB 에 저장하지 않고 API 로도 내보내지 않는다 (메모리로 분석 단계에만 넘긴다). */
    @Transient @JsonIgnore private String body;

    @PrePersist
    public void setCollectedAt() { this.collectedAt = LocalDateTime.now(); }
}
