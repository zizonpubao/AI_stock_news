package com.stocknews.dto;

import com.stocknews.entity.NewsArticle;
import lombok.Getter;
import java.time.LocalDateTime;

@Getter
public class NewsDto {
    private Long id;
    private String title;
    private String description;
    private String link;
    private String pubDate;
    private LocalDateTime collectedAt;

    public static NewsDto from(NewsArticle article) {
        NewsDto dto = new NewsDto();
        dto.id = article.getId();
        dto.title = article.getTitle();
        dto.description = article.getDescription();
        dto.link = article.getLink();
        dto.pubDate = article.getPubDate();
        dto.collectedAt = article.getCollectedAt();
        return dto;
    }
}
