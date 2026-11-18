package com.stocknews.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocknews.entity.NewsArticle;
import com.stocknews.repository.NewsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 네트워크·DB 없이 응답 해석 로직만 검증한다. 현재 시각은 인자로 고정한다. */
class NewsServiceTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 5, 20, 12, 0, 0, 0, ZoneOffset.ofHours(9));
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private final NewsService service = new NewsService(mock(NewsRepository.class), new ObjectMapper());

    private static String item(String title, ZonedDateTime pub) {
        return "{\"title\":\"" + title + "\",\"description\":\"<b>설명</b> 본문\","
                + "\"link\":\"https://example.com/a\",\"pubDate\":\"" + pub.format(FMT) + "\"}";
    }

    private static String body(String... items) {
        return "{\"items\":[" + String.join(",", items) + "]}";
    }

    @Test
    @DisplayName("48시간이 지난 기사는 제외한다")
    void excludesOlderThan48Hours() throws IOException {
        String json = body(
                item("가나전자 신제품", NOW.minusHours(47)),
                item("가나전자 옛날 기사", NOW.minusHours(49)));

        List<NewsArticle> result = service.parseNews(json, "가나전자", NOW);

        assertThat(result).extracting(NewsArticle::getTitle).containsExactly("가나전자 신제품");
    }

    @Test
    @DisplayName("제목에 종목명이 없는 기사는 제외한다")
    void excludesTitleWithoutStockName() throws IOException {
        String json = body(
                item("다른 회사 소식", NOW.minusHours(1)),
                item("가나전자 실적 발표", NOW.minusHours(2)));

        List<NewsArticle> result = service.parseNews(json, "가나전자", NOW);

        assertThat(result).extracting(NewsArticle::getTitle).containsExactly("가나전자 실적 발표");
    }

    @Test
    @DisplayName("기사는 최대 5건까지만 돌려준다")
    void limitsToFive() throws IOException {
        String[] items = new String[8];
        for (int i = 0; i < items.length; i++) items[i] = item("가나전자 기사" + i, NOW.minusHours(1));

        List<NewsArticle> result = service.parseNews(body(items), "가나전자", NOW);

        assertThat(result).hasSize(5);
        assertThat(result.get(4).getTitle()).isEqualTo("가나전자 기사4");
    }

    @Test
    @DisplayName("제목과 설명의 HTML 태그를 제거한다")
    void stripsHtmlTags() throws IOException {
        String json = body(item("<b>가나전자</b> 호실적", NOW.minusHours(1)));

        List<NewsArticle> result = service.parseNews(json, "가나전자", NOW);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("가나전자 호실적");
        assertThat(result.get(0).getDescription()).isEqualTo("설명 본문");
        assertThat(result.get(0).getLink()).isEqualTo("https://example.com/a");
    }

    @Test
    @DisplayName("조건에 맞는 기사가 없으면 실패가 아니라 빈 목록이다")
    void noMatchingArticlesIsNotAnError() throws IOException {
        assertThat(service.parseNews(body(), "가나전자", NOW)).isEmpty();
        assertThat(service.parseNews(body(item("다른 회사", NOW)), "가나전자", NOW)).isEmpty();
    }

    @Test
    @DisplayName("잘못된 JSON, 빈 응답, items 없는 응답은 해석 실패로 예외를 던진다")
    void unparseableResponseFails() {
        assertThatThrownBy(() -> service.parseNews("{not json", "가나전자", NOW)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> service.parseNews("", "가나전자", NOW)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> service.parseNews(null, "가나전자", NOW)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> service.parseNews("{\"errorMessage\":\"x\"}", "가나전자", NOW))
                .isInstanceOf(IOException.class);
    }
}
