package com.stocknews.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 날짜별 아카이브 뉴스 (아카이브 종목에 종속).
 */
@Entity
@Table(name = "daily_news")
@Getter @Setter @NoArgsConstructor
public class DailyArchiveNews {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "archive_stock_id")
    private DailyArchiveStock archiveStock;

    @Column(nullable = false, length = 500) private String title;
    @Column(length = 1000) private String description;
    @Column(length = 500) private String link;
    private String pubDate;
}
