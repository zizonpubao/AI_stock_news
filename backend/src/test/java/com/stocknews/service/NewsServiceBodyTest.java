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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 본문 수집 연동: originallink 읽기, 실패해도 기사 목록 유지. 네트워크 없음. */
class NewsServiceBodyTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 5, 20, 12, 0, 0, 0, ZoneOffset.ofHours(9));
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private static String json(String title, String original) {
        String orig = original == null ? "" : ",\"originallink\":\"" + original + "\"";
        return "{\"items\":[{\"title\":\"" + title + "\",\"description\":\"설명\",\"link\":\"https://naver.example.test/1\""
                + orig + ",\"pubDate\":\"" + NOW.minusHours(1).format(FMT) + "\"}]}";
    }

    private static NewsService service(ArticleBodyFetcher fetcher) {
        return new NewsService(mock(NewsRepository.class), new ObjectMapper(), fetcher);
    }

    /** 호출 내용을 기록하는 가짜 수집기 (네트워크를 쓰지 않는다) */
    static class StubFetcher extends ArticleBodyFetcher {
        final List<String> seen = new ArrayList<>();
        final Supplier<BodyResult> answer;

        StubFetcher(Supplier<BodyResult> answer) {
            super((url, ua, timeout, max) -> null, true, 1500, 1, 1, "T", List.of(), System::currentTimeMillis);
            this.answer = answer;
        }

        @Override
        public BodyResult fetch(String originalLink, String link) {
            seen.add(originalLink + "|" + link);
            return answer.get();
        }
    }

    @Test
    @DisplayName("originallink 를 originalLink 로 읽고, 없으면 빈 문자열")
    void readsOriginalLink() throws IOException {
        NewsService s = service(ArticleBodyFetcher.disabled());
        List<NewsArticle> with = s.parseNews(json("가나전자 소식", "https://press.example.test/a"), "가나전자", NOW);
        assertThat(with.get(0).getOriginalLink()).isEqualTo("https://press.example.test/a");
        assertThat(with.get(0).getLink()).isEqualTo("https://naver.example.test/1");

        List<NewsArticle> without = s.parseNews(json("가나전자 소식", null), "가나전자", NOW);
        assertThat(without.get(0).getOriginalLink()).isEmpty();
    }

    @Test
    @DisplayName("본문 수집이 예외를 던져도 기사 목록은 그대로이고 상태만 남는다")
    void bodyFailureKeepsArticles() throws IOException {
        StubFetcher boom = new StubFetcher(() -> { throw new IllegalStateException("예상 못한 오류"); });
        NewsService s = service(boom);
        List<NewsArticle> list = s.parseNews(json("가나전자 소식", "https://press.example.test/a"), "가나전자", NOW);
        s.attachBodies(list, NewsService.BODY_BUDGET_MS);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getTitle()).isEqualTo("가나전자 소식");
        assertThat(list.get(0).getBody()).isNull();
        assertThat(list.get(0).getBodyStatus()).isEqualTo(ArticleBodyFetcher.FETCH_FAILED);
        assertThat(list.get(0).getBodyChars()).isNull();
        assertThat(boom.seen).containsExactly("https://press.example.test/a|https://naver.example.test/1");
    }

    @Test
    @DisplayName("본문을 얻으면 body 와 글자 수를 채운다")
    void bodyIsAttached() throws IOException {
        StubFetcher ok = new StubFetcher(() -> new ArticleBodyFetcher.BodyResult(ArticleBodyFetcher.BODY, "본문 글자입니다."));
        NewsService s = service(ok);
        List<NewsArticle> list = s.parseNews(json("가나전자 소식", "https://press.example.test/a"), "가나전자", NOW);
        s.attachBodies(list, NewsService.BODY_BUDGET_MS);
        assertThat(list.get(0).getBody()).isEqualTo("본문 글자입니다.");
        assertThat(list.get(0).getBodyStatus()).isEqualTo(ArticleBodyFetcher.BODY);
        assertThat(list.get(0).getBodyChars()).isEqualTo("본문 글자입니다.".length());
    }

    @Test
    @DisplayName("시간 예산을 넘으면 요청하지 않고 BUDGET_EXCEEDED")
    void budgetExceeded() throws IOException {
        StubFetcher ok = new StubFetcher(() -> new ArticleBodyFetcher.BodyResult(ArticleBodyFetcher.BODY, "본문"));
        NewsService s = service(ok);
        List<NewsArticle> list = s.parseNews(json("가나전자 소식", "https://press.example.test/a"), "가나전자", NOW);
        s.attachBodies(list, 0);
        assertThat(list.get(0).getBodyStatus()).isEqualTo(ArticleBodyFetcher.BUDGET_EXCEEDED);
        assertThat(ok.seen).isEmpty();
    }

    @Test
    @DisplayName("기능이 꺼져 있으면 DISABLED 이고 본문은 없다 (2인자 생성자도 같다)")
    void disabledStatus() throws IOException {
        NewsService s = new NewsService(mock(NewsRepository.class), new ObjectMapper());
        List<NewsArticle> list = s.parseNews(json("가나전자 소식", "https://press.example.test/a"), "가나전자", NOW);
        s.attachBodies(list, NewsService.BODY_BUDGET_MS);
        assertThat(list.get(0).getBodyStatus()).isEqualTo(ArticleBodyFetcher.DISABLED);
        assertThat(list.get(0).getBody()).isNull();
    }
}
