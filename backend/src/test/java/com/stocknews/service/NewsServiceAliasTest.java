package com.stocknews.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.Stock;
import com.stocknews.repository.NewsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 별칭 검색·채택. 네트워크 없이 합성 JSON 만 쓴다. */
class NewsServiceAliasTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 5, 20, 12, 0, 0, 0, ZoneOffset.ofHours(9));
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private final NewsService service = new NewsService(mock(NewsRepository.class), new ObjectMapper());

    private static String item(String title, String link) {
        return "{\"title\":\"" + title + "\",\"description\":\"설명\",\"link\":\"" + link
                + "\",\"pubDate\":\"" + NOW.minusHours(1).format(FMT) + "\"}";
    }

    private static String item(String title) {
        return item(title, "https://example.com/" + Math.abs(title.hashCode()));
    }

    private static String body(String... items) {
        return "{\"items\":[" + String.join(",", items) + "]}";
    }

    private static List<String> titles(List<NewsArticle> list) {
        return list.stream().map(NewsArticle::getTitle).toList();
    }

    @Test
    @DisplayName("별칭이 없으면 기존 3인자 parseNews 와 제목·순서가 같다")
    void noAliasSameAsLegacy() throws IOException {
        String r = body(item("가나전자 가 소식"), item("다른 회사"), item("가나전자 실적 발표"),
                item("가나전자벡셀 소식"), item("가나전자 신제품 공개"));
        assertThat(titles(service.parseNews(List.of(r), "가나전자", List.of(), NOW)))
                .isEqualTo(titles(service.parseNews(r, "가나전자", NOW)))
                .isNotEmpty();
    }

    @Test
    @DisplayName("공식 응답 0건이어도 별칭 응답의 기사는 채택된다")
    void aliasOnlyArticlesAdopted() throws IOException {
        String official = body();
        String alias = body(item("JYP엔터 신인 데뷔"), item("JYP엔터 콘서트 개최"), item("JYP엔터, 실적 발표"));
        List<NewsArticle> r = service.parseNews(List.of(official, alias), "JYP Ent.", List.of("JYP엔터"), NOW);
        assertThat(titles(r)).containsExactly("JYP엔터 신인 데뷔", "JYP엔터 콘서트 개최", "JYP엔터, 실적 발표");
    }

    @Test
    @DisplayName("round-robin: 공식1, 별칭1, 공식2, 별칭2, 공식3")
    void roundRobinOrder() throws IOException {
        String official = body(item("네이버A 공식 하나 소식"), item("네이버A 공식 둘 발표"), item("네이버A 공식 셋 공개"));
        String alias = body(item("네이버 별칭 가 서비스"), item("네이버 별칭 나 투자"), item("네이버 별칭 다 인수"));
        List<NewsArticle> r = service.parseNews(List.of(official, alias), "네이버A", List.of("네이버"), NOW);
        assertThat(titles(r)).containsExactly("네이버A 공식 하나 소식", "네이버 별칭 가 서비스",
                "네이버A 공식 둘 발표", "네이버 별칭 나 투자", "네이버A 공식 셋 공개");
    }

    @Test
    @DisplayName("별칭이 둘이어도 공식 종목명 결과가 5건 중 3건을 차지한다: 공식1, 별칭A1, 공식2, 별칭B1, 공식3")
    void twoAliasesShareOneLane() throws IOException {
        String official = body(item("가나엔터 공식 하나 소식"), item("가나엔터 공식 둘 발표"), item("가나엔터 공식 셋 공개"));
        String aliasA = body(item("GN엔터테인먼트 가 서비스"), item("GN엔터테인먼트 나 투자"));
        String aliasB = body(item("GN엔터 다 인수"), item("GN엔터 라 계약"));
        List<NewsArticle> r = service.parseNews(List.of(official, aliasA, aliasB), "가나엔터",
                List.of("GN엔터테인먼트", "GN엔터"), NOW);
        assertThat(titles(r)).containsExactly("가나엔터 공식 하나 소식", "GN엔터테인먼트 가 서비스",
                "가나엔터 공식 둘 발표", "GN엔터 다 인수", "가나엔터 공식 셋 공개");
    }

    @Test
    @DisplayName("별칭 JYP엔터 만 있으면 JYP엔터테인먼트 제목은 꼬리 규칙으로 제외, 별칭에 둘 다 있으면 채택")
    void tailRuleForAlias() throws IOException {
        String alias = body(item("JYP엔터테인먼트, 신인 데뷔"));
        String official = body();
        assertThat(service.parseNews(List.of(official, alias), "JYP Ent.", List.of("JYP엔터"), NOW)).isEmpty();
        assertThat(titles(service.parseNews(List.of(official, alias), "JYP Ent.",
                List.of("JYP엔터", "JYP엔터테인먼트"), NOW))).containsExactly("JYP엔터테인먼트, 신인 데뷔");
    }

    @Test
    @DisplayName("별칭이 다른 단어의 앞부분일 때는 제외, 띄어쓰기·쉼표·조사는 채택")
    void aliasAsOwnWord() throws IOException {
        String alias = body(item("네이버웹툰 신작 공개"), item("네이버 웹툰 신작 공개"),
                item("네이버, 신규 서비스 출시"), item("네이버가 클라우드 투자 확대"));
        List<NewsArticle> r = service.parseNews(List.of(body(), alias), "NAVER", List.of("네이버"), NOW);
        assertThat(titles(r)).containsExactly("네이버 웹툰 신작 공개", "네이버, 신규 서비스 출시", "네이버가 클라우드 투자 확대");
    }

    @Test
    @DisplayName("같은 링크는 한 번만, 표기만 다른 같은 제목도 한 번만 채택")
    void dedupAcrossResponses() throws IOException {
        String official = body(item("NAVER, 3분기 영업이익 10% 증가", "https://n.example.com/1"),
                item("NAVER 새 서비스 출시", "https://n.example.com/2"));
        // round-robin 순서: 공식1, 별칭1(표기만 다른 중복), 공식2, 별칭2(공식2와 같은 링크)
        String alias = body(item("네이버, 3분기 영업이익 10% 증가", "https://n.example.com/3"),
                item("네이버 전혀 다른 제목의 같은 링크", "https://n.example.com/2"));
        List<NewsArticle> r = service.parseNews(List.of(official, alias), "NAVER", List.of("네이버"), NOW);
        assertThat(titles(r)).containsExactly("NAVER, 3분기 영업이익 10% 증가", "NAVER 새 서비스 출시");
    }

    @Test
    @DisplayName("normalizeTitle 은 긴 이름부터 지운다")
    void normalizeLongestFirst() {
        assertThat(NewsService.normalizeTitle("JYP엔터테인먼트 실적", List.of("JYP엔터", "JYP엔터테인먼트")))
                .isEqualTo("실적");
        assertThat(NewsService.normalizeTitle("NAVER 실적", "NAVER")).isEqualTo("실적");
    }

    @Test
    @DisplayName("별칭 응답이 null 이거나 깨져도 공식 결과만으로 동작하고, 공식 응답이 깨지면 IOException")
    void brokenAliasResponses() throws IOException {
        String official = body(item("네이버 공식 소식 발표"));
        List<String> withNull = new ArrayList<>();
        withNull.add(official);
        withNull.add(null);
        withNull.add("{깨진 json");
        withNull.add("{\"foo\":1}");
        assertThat(titles(service.parseNews(withNull, "네이버", List.of("A", "B", "C"), NOW)))
                .containsExactly("네이버 공식 소식 발표");

        assertThatThrownBy(() -> service.parseNews(List.of("{깨진", official), "네이버", List.of("A"), NOW))
                .isInstanceOf(IOException.class);
    }

    /** callNewsApi 를 가짜로 바꾼 서비스: 질의마다 응답 함수를 돌리고 호출 기록을 남긴다. */
    static class FakeApiService extends NewsService {
        final List<String> queries = new ArrayList<>();
        final Function<String, String> answer;

        FakeApiService(StockAliases aliases, Function<String, String> answer) {
            super(mock(NewsRepository.class), new ObjectMapper(), ArticleBodyFetcher.disabled(), aliases);
            this.answer = answer;
        }

        @Override
        String callNewsApi(String query) {
            queries.add(query);
            return answer.apply(query);
        }
    }

    private static Stock stock() {
        Stock s = new Stock();
        s.setCode("035420");
        s.setName("NAVER");
        return s;
    }

    private static StockAliases aliases() {
        StockAliases a = new StockAliases();
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("035420", List.of("네이버", "NAVER Corp"));
        a.setByCode(m);
        return a;
    }

    private static String recent(String title) {
        // fetchAndSaveNews 는 실제 현재 시각을 쓰므로 pubDate 도 현재 기준으로 만든다
        return "{\"items\":[{\"title\":\"" + title + "\",\"description\":\"d\",\"link\":\"https://e.test/"
                + Math.abs(title.hashCode()) + "\",\"pubDate\":\""
                + ZonedDateTime.now().minusHours(1).format(FMT) + "\"}]}";
    }

    @Test
    @DisplayName("fetchAndSaveNews: 이름마다 한 번씩 호출한다")
    void callsOncePerName() {
        FakeApiService s = new FakeApiService(aliases(), q -> recent("NAVER 소식 " + q.length()));
        NewsService.NewsFetchResult r = s.fetchAndSaveNews(stock());
        assertThat(s.queries).containsExactly("\"NAVER\"", "\"네이버\"", "\"NAVER Corp\"");
        assertThat(r.success()).isTrue();
    }

    @Test
    @DisplayName("fetchAndSaveNews: 별칭 호출이 예외여도 성공이고 공식 기사가 저장된다")
    void aliasCallFailureIsNotStockFailure() {
        FakeApiService s = new FakeApiService(aliases(), q -> {
            if (!q.equals("\"NAVER\"")) throw new RuntimeException("별칭 호출 실패");
            return recent("NAVER 공식 소식");
        });
        NewsService.NewsFetchResult r = s.fetchAndSaveNews(stock());
        assertThat(r.success()).isTrue();
        assertThat(titles(r.articles())).containsExactly("NAVER 공식 소식");
    }

    @Test
    @DisplayName("fetchAndSaveNews: 공식 종목명 호출이 예외면 실패")
    void officialCallFailureIsStockFailure() {
        FakeApiService s = new FakeApiService(aliases(), q -> {
            throw new RuntimeException("공식 호출 실패");
        });
        NewsService.NewsFetchResult r = s.fetchAndSaveNews(stock());
        assertThat(r.success()).isFalse();
        assertThat(r.articles()).isEmpty();
    }
}
