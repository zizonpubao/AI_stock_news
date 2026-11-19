package com.stocknews.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.Stock;
import com.stocknews.repository.NewsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 본문을 얻지 못한 기사는 채택하지 않는 경로(news.body.require). 네트워크 없음. */
class NewsServiceRequireBodyTest {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);
    private static final String BODY_TEXT = "본문입니다. ".repeat(40);

    private static String pub() {
        return ZonedDateTime.now(ZoneOffset.ofHours(9)).minusHours(1).format(FMT);
    }

    /** 기사 id 로 주소를 만든다: 원문 https://press.example.test/{id}, 네이버 https://n.example.test/{id} */
    private static String item(String title, String id) {
        return "{\"title\":\"" + title + "\",\"description\":\"설명\",\"link\":\"https://n.example.test/" + id
                + "\",\"originallink\":\"https://press.example.test/" + id + "\",\"pubDate\":\"" + pub() + "\"}";
    }

    private static String body(String... items) {
        return "{\"items\":[" + String.join(",", items) + "]}";
    }

    /** 원문 주소 끝의 id → 결과. 없는 id 는 BODY. 요청한 id 를 순서대로 기록한다. */
    static class StubFetcher extends ArticleBodyFetcher {
        final Map<String, String> statusById = new HashMap<>();
        final List<String> seen = new ArrayList<>();
        final boolean on;
        long sleepMs = 0;

        StubFetcher(boolean enabled) {
            super((url, ua, timeout, max) -> null, enabled, 1500, 1, 1, "T", List.of(), System::currentTimeMillis);
            this.on = enabled;
        }

        @Override
        public BodyResult fetch(String originalLink, String link) {
            String id = originalLink.substring(originalLink.lastIndexOf('/') + 1);
            seen.add(id);
            if (sleepMs > 0) {
                try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            String st = statusById.getOrDefault(id, BODY);
            return BODY.equals(st) ? new BodyResult(BODY, BODY_TEXT) : new BodyResult(st, null);
        }
    }

    static class FakeApiService extends NewsService {
        final Function<String, String> answer;
        final NewsRepository repo;

        FakeApiService(NewsRepository repo, ArticleBodyFetcher fetcher, StockAliases aliases,
                       Function<String, String> answer) {
            super(repo, new ObjectMapper(), fetcher, aliases);
            this.repo = repo;
            this.answer = answer;
        }

        @Override
        String callNewsApi(String query) {
            return answer.apply(query);
        }
    }

    private static Stock stock() {
        Stock s = new Stock();
        s.setCode("000001");
        s.setName("가나전자");
        return s;
    }

    private static FakeApiService service(StubFetcher f, boolean require, Function<String, String> answer) {
        FakeApiService s = new FakeApiService(mock(NewsRepository.class), f, StockAliases.none(), answer);
        s.setRequireBody(require);
        return s;
    }

    private static final String[] TITLES = {
            "가나전자 알파 실적 호조 발표", "가나전자 베타 신제품 공개 행사", "가나전자 감마 해외 공장 증설 확정",
            "가나전자 델타 배당 확대 결정", "가나전자 엡실론 신규 수주 소식", "가나전자 제타 임원 인사 단행",
            "가나전자 에타 연구소 개소 소식"};

    private static String sevenItems() {
        String[] items = new String[7];
        for (int i = 0; i < 7; i++) items[i] = item(TITLES[i], "a" + (i + 1));
        return body(items);
    }

    private static List<String> ids(List<NewsArticle> list) {
        return list.stream().map(a -> a.getOriginalLink().substring(a.getOriginalLink().lastIndexOf('/') + 1)).toList();
    }

    @Test
    @DisplayName("2번째·4번째 본문이 없으면 1,3,5,6,7번이 이 순서로 채택되고 상태가 기록된다")
    void skipsBodylessAndKeepsOrder() {
        StubFetcher f = new StubFetcher(true);
        f.statusById.put("a2", ArticleBodyFetcher.ROBOTS_DISALLOWED);
        f.statusById.put("a4", ArticleBodyFetcher.FETCH_FAILED);
        FakeApiService s = service(f, true, q -> sevenItems());

        NewsService.NewsFetchResult r = s.fetchAndSaveNews(stock());

        assertThat(r.success()).isTrue();
        assertThat(ids(r.articles())).containsExactly("a1", "a3", "a5", "a6", "a7");
        assertThat(r.articles()).allMatch(a -> ArticleBodyFetcher.BODY.equals(a.getBodyStatus()) && a.getBody() != null);
        assertThat(r.skippedBodyStatuses())
                .containsExactly(ArticleBodyFetcher.ROBOTS_DISALLOWED, ArticleBodyFetcher.FETCH_FAILED);
        verify(s.repo, times(5)).save(org.mockito.ArgumentMatchers.any(NewsArticle.class));
        assertThat(f.seen).containsExactly("a1", "a2", "a3", "a4", "a5", "a6", "a7");
    }

    @Test
    @DisplayName("5건을 채우면 나머지 후보의 본문은 요청하지 않는다")
    void stopsAtFive() {
        StubFetcher f = new StubFetcher(true);
        FakeApiService s = service(f, true, q -> sevenItems());
        NewsService.NewsFetchResult r = s.fetchAndSaveNews(stock());
        assertThat(ids(r.articles())).containsExactly("a1", "a2", "a3", "a4", "a5");
        assertThat(f.seen).containsExactly("a1", "a2", "a3", "a4", "a5");
        assertThat(r.skippedBodyStatuses()).isEmpty();
    }

    @Test
    @DisplayName("본문 없는 후보와 거의 같은 제목은 채택되고, 채택된 기사와 같은 제목은 본문 요청 없이 건너뛴다")
    void duplicateOnlyAgainstAdopted() {
        StubFetcher f = new StubFetcher(true);
        f.statusById.put("b1", ArticleBodyFetcher.ROBOTS_DISALLOWED);
        String r = body(
                item("가나전자 베타 신제품 공개 행사", "b1"),     // 본문 없음 → 건너뜀
                item("가나전자 베타 신제품 공개 행사!", "b2"),    // b1 은 채택되지 않았으므로 채택된다
                item("가나전자 베타 신제품 공개 행사?", "b3"),    // b2 와 같은 제목 → 본문 요청 없이 건너뜀
                item("가나전자 감마 해외 공장 증설 확정", "b4"));
        FakeApiService s = service(f, true, q -> r);

        NewsService.NewsFetchResult res = s.fetchAndSaveNews(stock());

        assertThat(ids(res.articles())).containsExactly("b2", "b4");
        assertThat(f.seen).containsExactly("b1", "b2", "b4");
        assertThat(res.skippedBodyStatuses()).containsExactly(ArticleBodyFetcher.ROBOTS_DISALLOWED);
    }

    @Test
    @DisplayName("같은 링크도 채택된 기사에 대해서만 중복이다")
    void sameLinkDuplicateOfAdopted() {
        StubFetcher f = new StubFetcher(true);
        String dup = "{\"title\":\"가나전자 다른 제목 소식 전합니다\",\"description\":\"설명\",\"link\":\"https://n.example.test/c1\","
                + "\"originallink\":\"https://press.example.test/c1x\",\"pubDate\":\"" + pub() + "\"}";
        FakeApiService s = service(f, true, q -> body(item("가나전자 알파 실적 호조 발표", "c1"), dup));
        NewsService.NewsFetchResult res = s.fetchAndSaveNews(stock());
        assertThat(ids(res.articles())).containsExactly("c1");
        assertThat(f.seen).containsExactly("c1");
    }

    @Test
    @DisplayName("본문이 있는 기사가 5건 미만이면 있는 만큼만 채택한다")
    void fewerThanFive() {
        StubFetcher f = new StubFetcher(true);
        for (String id : List.of("a1", "a2", "a4", "a5", "a6")) f.statusById.put(id, ArticleBodyFetcher.FETCH_FAILED);
        FakeApiService s = service(f, true, q -> sevenItems());
        NewsService.NewsFetchResult r = s.fetchAndSaveNews(stock());
        assertThat(r.success()).isTrue();
        assertThat(ids(r.articles())).containsExactly("a3", "a7");
        assertThat(r.skippedBodyStatuses()).hasSize(5);
    }

    @Test
    @DisplayName("본문이 있는 기사가 하나도 없으면 빈 목록이고 success=true")
    void noneWithBody() {
        StubFetcher f = new StubFetcher(true);
        for (int i = 1; i <= 7; i++) f.statusById.put("a" + i, ArticleBodyFetcher.EXTRACT_FAILED);
        FakeApiService s = service(f, true, q -> sevenItems());
        NewsService.NewsFetchResult r = s.fetchAndSaveNews(stock());
        assertThat(r.success()).isTrue();
        assertThat(r.articles()).isEmpty();
        assertThat(r.skippedBodyStatuses()).hasSize(7).containsOnly(ArticleBodyFetcher.EXTRACT_FAILED);
    }

    @Test
    @DisplayName("시간 예산을 넘기면 더 시도하지 않는다")
    void budgetStopsTrying() throws Exception {
        StubFetcher f = new StubFetcher(true);
        f.sleepMs = 30;
        f.statusById.put("a1", ArticleBodyFetcher.FETCH_FAILED);
        NewsService s = service(f, true, q -> sevenItems());
        List<NewsService.Candidate> cands = s.buildCandidates(List.of(sevenItems()), "가나전자", List.of(),
                ZonedDateTime.now());
        List<String> skipped = new ArrayList<>();

        List<NewsArticle> adopted = s.selectWithBodies(cands, 10, skipped);

        assertThat(f.seen).containsExactly("a1"); // 첫 요청(30ms)이 예산 10ms 를 넘겼으므로 두 번째는 시도하지 않는다
        assertThat(adopted).isEmpty();
        assertThat(skipped).containsExactly(ArticleBodyFetcher.FETCH_FAILED);
    }

    @Test
    @DisplayName("require=false 면 기존처럼 앞 5건을 본문 없는 기사까지 상태와 함께 저장한다")
    void requireFalseKeepsLegacy() {
        StubFetcher f = new StubFetcher(true);
        f.statusById.put("a2", ArticleBodyFetcher.ROBOTS_DISALLOWED);
        f.statusById.put("a4", ArticleBodyFetcher.FETCH_FAILED);
        FakeApiService s = service(f, false, q -> sevenItems());
        NewsService.NewsFetchResult r = s.fetchAndSaveNews(stock());
        assertThat(ids(r.articles())).containsExactly("a1", "a2", "a3", "a4", "a5");
        assertThat(r.articles().stream().map(NewsArticle::getBodyStatus).toList()).containsExactly(
                ArticleBodyFetcher.BODY, ArticleBodyFetcher.ROBOTS_DISALLOWED, ArticleBodyFetcher.BODY,
                ArticleBodyFetcher.FETCH_FAILED, ArticleBodyFetcher.BODY);
        assertThat(r.skippedBodyStatuses()).isEmpty();
    }

    @Test
    @DisplayName("본문 수집기가 꺼져 있으면 require=true 여도 기존처럼 5건 모두 저장한다")
    void fetcherDisabledKeepsLegacy() {
        StubFetcher f = new StubFetcher(false);
        FakeApiService s = service(f, true, q -> sevenItems());
        NewsService.NewsFetchResult r = s.fetchAndSaveNews(stock());
        assertThat(ids(r.articles())).containsExactly("a1", "a2", "a3", "a4", "a5");
        assertThat(r.articles()).allMatch(a -> ArticleBodyFetcher.DISABLED.equals(a.getBodyStatus()));
        assertThat(f.seen).isEmpty();
        assertThat(r.skippedBodyStatuses()).isEmpty();
    }

    @Test
    @DisplayName("별칭 줄이 있어도 후보 순서는 공식1, 별칭1, 공식2, 별칭2 로 번갈아 놓인다")
    void aliasLanesInterleave() {
        StubFetcher f = new StubFetcher(true);
        StockAliases aliases = new StockAliases();
        aliases.setByCode(Map.of("000001", List.of("가나일렉")));
        String official = body(item("가나전자 알파 실적 호조 발표", "o1"), item("가나전자 감마 해외 공장 증설 확정", "o2"));
        String alias = body(item("가나일렉 베타 신제품 공개 행사", "x1"), item("가나일렉 델타 배당 확대 결정", "x2"));
        FakeApiService s = new FakeApiService(mock(NewsRepository.class), f, aliases,
                q -> q.contains("가나일렉") ? alias : official);
        s.setRequireBody(true);
        f.statusById.put("x1", ArticleBodyFetcher.FETCH_FAILED);

        NewsService.NewsFetchResult r = s.fetchAndSaveNews(stock());

        assertThat(f.seen).containsExactly("o1", "x1", "o2", "x2");
        assertThat(ids(r.articles())).containsExactly("o1", "o2", "x2");
        assertThat(r.skippedBodyStatuses()).containsExactly(ArticleBodyFetcher.FETCH_FAILED);
    }
}
