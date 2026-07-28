package com.stocknews.entity;

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

    @PrePersist
    public void setCollectedAt() { this.collectedAt = LocalDateTime.now(); }
}
