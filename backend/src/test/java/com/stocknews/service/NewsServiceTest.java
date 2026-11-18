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

    /** 링크는 기본적으로 제목마다 다르게 만든다(같은 링크는 중복으로 걸러지므로). 첫 기사 링크는 .../a 로 고정. */
    private static String item(String title, ZonedDateTime pub) {
        return item(title, pub, "https://example.com/" + Math.abs(title.hashCode()));
    }

    private static String item(String title, ZonedDateTime pub, String link) {
        return "{\"title\":\"" + title + "\",\"description\":\"<b>설명</b> 본문\","
                + "\"link\":\"" + link + "\",\"pubDate\":\"" + pub.format(FMT) + "\"}";
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
        String json = body(item("<b>가나전자</b> 호실적", NOW.minusHours(1), "https://example.com/a"));

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

    private List<String> titles(String json) throws IOException {
        return service.parseNews(json, "가나전자", NOW).stream().map(NewsArticle::getTitle).toList();
    }

    @Test
    @DisplayName("제목이 완전히 같은 기사 여러 건은 1건만 남긴다")
    void dedupesIdenticalTitles() throws IOException {
        String t = "가나전자 3분기 영업이익 2조 돌파";
        String json = body(
                item(t, NOW.minusHours(1), "https://a.com/1"),
                item(t, NOW.minusHours(2), "https://b.com/2"),
                item(t, NOW.minusHours(3), "https://c.com/3"));

        assertThat(titles(json)).containsExactly(t);
    }

    @Test
    @DisplayName("머리말·문장부호·따옴표만 다른 제목은 중복으로 처리하고 먼저 나온 기사를 남긴다")
    void dedupesTitlesDifferingByTagsAndPunctuation() throws IOException {
        String json = body(
                item("가나전자 3분기 영업이익 2조 돌파", NOW.minusHours(1), "https://a.com/1"),
                item("[속보] 가나전자, 3분기 영업이익 2조 돌파", NOW.minusHours(2), "https://b.com/2"),
                item("'가나전자' 3분기 영업이익 2조 돌파 (종합)", NOW.minusHours(3), "https://c.com/3"),
                item("[단독][포토] 가나전자 3분기 영업이익 2조 돌파!", NOW.minusHours(4), "https://d.com/4"));

        assertThat(titles(json)).containsExactly("가나전자 3분기 영업이익 2조 돌파");
    }

    @Test
    @DisplayName("조사 한두 글자만 다른 거의 같은 제목은 중복으로 처리한다")
    void dedupesNearlyIdenticalTitles() throws IOException {
        String json = body(
                item("가나전자 3분기 영업이익 2조 돌파", NOW.minusHours(1), "https://a.com/1"),
                item("가나전자 3분기 영업이익 2조원 돌파", NOW.minusHours(2), "https://b.com/2"),
                item("가나전자가 신제품 공개", NOW.minusHours(3), "https://c.com/3"),
                item("가나전자 신제품 공개", NOW.minusHours(4), "https://d.com/4"));

        assertThat(titles(json)).containsExactly("가나전자 3분기 영업이익 2조 돌파", "가나전자가 신제품 공개");
    }

    @Test
    @DisplayName("같은 종목이라도 다른 사건을 다룬 제목은 둘 다 남긴다")
    void keepsDifferentEventsOfSameStock() throws IOException {
        String json = body(
                item("가나전자 3분기 영업이익 2조 돌파", NOW.minusHours(1), "https://a.com/1"),
                item("가나전자 신제품 공개", NOW.minusHours(2), "https://b.com/2"),
                item("가나전자 1분기 영업이익 증가", NOW.minusHours(3), "https://c.com/3"),
                item("가나전자 2분기 영업이익 증가", NOW.minusHours(4), "https://d.com/4"));

        assertThat(titles(json)).hasSize(4);
    }

    @Test
    @DisplayName("같은 링크가 반복되면 제목이 달라도 1건만 남긴다")
    void dedupesSameLink() throws IOException {
        String json = body(
                item("가나전자 신제품 공개", NOW.minusHours(1), "https://a.com/same"),
                item("가나전자 해외 공장 증설 발표", NOW.minusHours(2), "https://a.com/same"));

        assertThat(titles(json)).containsExactly("가나전자 신제품 공개");
    }

    @Test
    @DisplayName("중복을 걸러낸 뒤에도 최대 5건이며 뒤쪽의 서로 다른 기사가 채워진다")
    void limitsToFiveAfterDedupe() throws IOException {
        String dup = "가나전자 3분기 영업이익 2조 돌파";
        String json = body(
                item(dup, NOW.minusHours(1), "https://a.com/1"),
                item("[속보] " + dup, NOW.minusHours(1), "https://a.com/2"),
                item(dup + " (종합)", NOW.minusHours(1), "https://a.com/3"),
                item("가나전자 신제품 공개", NOW.minusHours(1), "https://a.com/4"),
                item("가나전자 해외 공장 증설 발표", NOW.minusHours(1), "https://a.com/5"),
                item("가나전자 대표이사 선임", NOW.minusHours(1), "https://a.com/6"),
                item("가나전자 배당 확대 결정", NOW.minusHours(1), "https://a.com/7"),
                item("가나전자 자사주 매입 소각", NOW.minusHours(1), "https://a.com/8"),
                item("가나전자 노조 파업 돌입", NOW.minusHours(1), "https://a.com/9"));

        assertThat(titles(json)).containsExactly(dup, "가나전자 신제품 공개", "가나전자 해외 공장 증설 발표",
                "가나전자 대표이사 선임", "가나전자 배당 확대 결정");
    }

    @Test
    @DisplayName("제목 유사도는 임계값 경계에서 보수적으로 동작한다")
    void similarityBoundary() {
        // 같은 문장에 글자 하나 추가: 0.75 -> 중복
        String a = NewsService.normalizeTitle("가나전자 3분기 영업이익 2조 돌파", "가나전자");
        String b = NewsService.normalizeTitle("가나전자 3분기 영업이익 2조원 돌파", "가나전자");
        assertThat(NewsService.bigramJaccard(a, b)).isGreaterThanOrEqualTo(NewsService.DUPLICATE_SIMILARITY_THRESHOLD);
        assertThat(NewsService.isSimilarTitle(a, b)).isTrue();

        // 숫자만 다른 분기 기사: 문자 유사도만으로는 임계값을 넘지만 숫자가 다르므로(다른 사실) 묶지 않는다
        String q1 = NewsService.normalizeTitle("가나전자 1분기 영업이익 증가", "가나전자");
        String q2 = NewsService.normalizeTitle("가나전자 2분기 영업이익 증가", "가나전자");
        assertThat(NewsService.bigramJaccard(q1, q2)).isGreaterThanOrEqualTo(NewsService.DUPLICATE_SIMILARITY_THRESHOLD);
        assertThat(NewsService.isSimilarTitle(q1, q2)).isFalse();

        // 완전히 다른 사건
        String c = NewsService.normalizeTitle("가나전자 신제품 공개", "가나전자");
        assertThat(NewsService.isSimilarTitle(a, c)).isFalse();
    }
}
