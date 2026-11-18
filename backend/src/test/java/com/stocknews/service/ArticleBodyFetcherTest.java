package com.stocknews.service;

import com.stocknews.service.ArticleBodyFetcher.BodyResult;
import com.stocknews.service.ArticleBodyFetcher.PageResponse;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 네트워크 없이 가짜 PageLoader 와 직접 지어낸 HTML·robots 문자열만 쓴다. */
class ArticleBodyFetcherTest {

    /** 200자가 넘는 지어낸 본문 (같은 문장 12번) */
    static final String SENTENCE = "가나전자는 새로운 시험 제품을 공개했다. ";
    static final String LONG_BODY = SENTENCE.repeat(12).trim();

    static Document doc(String html) {
        return Jsoup.parse(html);
    }

    // ===== extractBody =====

    @Test
    @DisplayName("itemprop=articleBody 영역을 본문으로 쓴다")
    void extractsItemprop() {
        Document d = doc("<html><body><div itemprop=\"articleBody\">" + LONG_BODY + "</div><footer>하단 문구</footer></body></html>");
        Optional<String> body = ArticleBodyFetcher.extractBody(d, "news.example.test");
        assertThat(body).contains(LONG_BODY);
    }

    @Test
    @DisplayName("#article-view-content-div 영역을 본문으로 쓴다")
    void extractsArticleViewDiv() {
        Document d = doc("<div id=\"article-view-content-div\"><p>" + LONG_BODY + "</p></div>");
        assertThat(ArticleBodyFetcher.extractBody(d, "news.example.test")).contains(LONG_BODY);
    }

    @Test
    @DisplayName("호스트별 선택자 표: www. 를 떼고 #content 만 본다")
    void hostSelector() {
        Document d = doc("<div id=\"content\">" + LONG_BODY + "</div><div itemprop=\"articleBody\">" + "다른 글 ".repeat(80) + "</div>");
        assertThat(ArticleBodyFetcher.extractBody(d, "www.kjdaily.com")).contains(LONG_BODY);
        // 표에 없는 호스트에서는 #content 를 보지 않는다
        Document d2 = doc("<div id=\"content\">" + LONG_BODY + "</div>");
        assertThat(ArticleBodyFetcher.extractBody(d2, "news.example.test")).isEmpty();
    }

    @Test
    @DisplayName("선택자에 안 걸리고 하단 문구만 긴 페이지는 실패")
    void noSelectorMeansFailure() {
        Document d = doc("<body><div class=\"wrap\"><p>" + "저작권 안내와 문의 연락처 ".repeat(40) + "</p></div></body>");
        assertThat(ArticleBodyFetcher.extractBody(d, "news.example.test")).isEmpty();
    }

    @Test
    @DisplayName("공백 정리 후 200자 미만이면 실패")
    void tooShortFails() {
        Document d = doc("<div itemprop=\"articleBody\">" + "짧은   글 ".repeat(10) + "</div>");
        assertThat(ArticleBodyFetcher.extractBody(d, "news.example.test")).isEmpty();
    }

    @Test
    @DisplayName("사진과 사진 설명은 본문에 들어가지 않고 인자 문서는 바뀌지 않는다")
    void removesPhotosAndCaptions() {
        String html = "<div itemprop=\"articleBody\"><figure><img src=\"a.jpg\"><figcaption>사진 설명 문구</figcaption></figure>"
                + "<p>" + LONG_BODY + "</p><script>var x=1;</script><button>공유</button></div>";
        Document d = doc(html);
        Optional<String> body = ArticleBodyFetcher.extractBody(d, "news.example.test");
        assertThat(body).isPresent();
        assertThat(body.get()).doesNotContain("사진 설명").doesNotContain("var x").doesNotContain("공유");
        assertThat(d.select("figcaption")).hasSize(1);
    }

    // ===== cutAtSentence =====

    @Test
    @DisplayName("짧은 글은 그대로")
    void cutShortUnchanged() {
        assertThat(ArticleBodyFetcher.cutAtSentence("짧은 문장이다.", 100)).isEqualTo("짧은 문장이다.");
    }

    @Test
    @DisplayName("길면 마지막 문장 끝까지 자른다")
    void cutAtLastSentenceEnd() {
        String text = "첫째 문장이다. 둘째 문장이다. 셋째 문장은 길게 이어지다가 잘린다";
        assertThat(ArticleBodyFetcher.cutAtSentence(text, 25)).isEqualTo("첫째 문장이다. 둘째 문장이다.");
    }

    @Test
    @DisplayName("문장 끝이 없거나 앞 60% 안에만 있으면 maxChars 에서 자른다")
    void cutHardWhenNoLateSentenceEnd() {
        String noEnd = "마침표가없는아주긴글".repeat(10);
        assertThat(ArticleBodyFetcher.cutAtSentence(noEnd, 30)).isEqualTo(noEnd.substring(0, 30));
        String earlyEnd = "짧다. " + "이어지는글".repeat(20);
        assertThat(ArticleBodyFetcher.cutAtSentence(earlyEnd, 50)).hasSize(50);
    }

    @Test
    @DisplayName("소수점 같은 공백 없는 마침표는 문장 끝으로 보지 않는다")
    void decimalPointIsNotSentenceEnd() {
        String text = "수치는3.14정도로계속이어지는긴글이있다";
        assertThat(ArticleBodyFetcher.cutAtSentence(text, 8)).isEqualTo(text.substring(0, 8));
    }

    // ===== fetch (가짜 로더) =====

    static class FakeLoader implements ArticleBodyFetcher.PageLoader {
        final Map<String, PageResponse> pages = new HashMap<>();
        final List<String> requests = new ArrayList<>();
        final List<String> throwOn = new ArrayList<>();

        @Override
        public PageResponse load(String url, String userAgent, int timeoutMs, int maxBytes) throws IOException {
            requests.add(url);
            if (throwOn.contains(url)) throw new IOException("연결 오류");
            PageResponse r = pages.get(url);
            if (r == null) return new PageResponse(404, "text/html", null, null, null);
            return r;
        }

        FakeLoader robots(String host, String text) {
            pages.put("https://" + host + "/robots.txt", new PageResponse(200, "text/plain", null, text, null));
            return this;
        }

        FakeLoader robotsStatus(String host, int status) {
            pages.put("https://" + host + "/robots.txt", new PageResponse(status, "text/html", null, null, null));
            return this;
        }

        FakeLoader article(String url, String bodyHtml) {
            pages.put(url, new PageResponse(200, "text/html", null, null,
                    Jsoup.parse("<html><body>" + bodyHtml + "</body></html>")));
            return this;
        }

        FakeLoader redirect(String url, String location) {
            pages.put(url, new PageResponse(301, "text/html", location, null, null));
            return this;
        }

        long articleRequests() {
            return requests.stream().filter(u -> !u.endsWith("/robots.txt")).count();
        }

        long robotsRequests() {
            return requests.stream().filter(u -> u.endsWith("/robots.txt")).count();
        }
    }

    static final String HOST = "news.example.test";
    static final String URL = "https://" + HOST + "/a/1";
    static final String ARTICLE_HTML = "<div itemprop=\"articleBody\">" + LONG_BODY + "</div>";

    static ArticleBodyFetcher fetcher(FakeLoader loader, boolean enabled) {
        return new ArticleBodyFetcher(loader, enabled, 1500, 5000, 1048576, "TestAgent/1.0",
                List.of("GPTBot", "ClaudeBot"), System::currentTimeMillis);
    }

    @Test
    @DisplayName("robots 가 없으면(404) 허용하고 본문을 돌려준다")
    void fetchesBodyWhenRobots404() {
        FakeLoader l = new FakeLoader().robotsStatus(HOST, 404).article(URL, ARTICLE_HTML);
        BodyResult r = fetcher(l, true).fetch(URL, "https://naver.example.test/x");
        assertThat(r.status()).isEqualTo(ArticleBodyFetcher.BODY);
        assertThat(r.text()).isEqualTo(LONG_BODY);
        assertThat(l.requests).first().isEqualTo("https://" + HOST + "/robots.txt");
    }

    @Test
    @DisplayName("originalLink 가 비면 link 를 쓴다")
    void fallsBackToLink() {
        FakeLoader l = new FakeLoader().robots(HOST, "").article(URL, ARTICLE_HTML);
        assertThat(fetcher(l, true).fetch("", URL).status()).isEqualTo(ArticleBodyFetcher.BODY);
    }

    @Test
    @DisplayName("robots 가 금지하면 기사 페이지를 요청하지 않는다")
    void robotsDisallowedSkipsArticleRequest() {
        FakeLoader l = new FakeLoader().robots(HOST, "User-agent: *\nDisallow: /\n").article(URL, ARTICLE_HTML);
        BodyResult r = fetcher(l, true).fetch(URL, null);
        assertThat(r.status()).isEqualTo(ArticleBodyFetcher.ROBOTS_DISALLOWED);
        assertThat(l.articleRequests()).isZero();
    }

    @Test
    @DisplayName("AI 수집기를 금지한 호스트는 AI_DISALLOWED 로 건너뛰고 기사 페이지를 요청하지 않는다")
    void aiDisallowed() {
        FakeLoader l = new FakeLoader().robots(HOST, "User-agent: GPTBot\nDisallow: /\n\nUser-agent: *\nAllow: /\n")
                .article(URL, ARTICLE_HTML);
        BodyResult r = fetcher(l, true).fetch(URL, null);
        assertThat(r.status()).isEqualTo(ArticleBodyFetcher.AI_DISALLOWED);
        assertThat(l.articleRequests()).isZero();
    }

    @Test
    @DisplayName("robots 403 은 ROBOTS_UNKNOWN, 기사 페이지를 요청하지 않는다")
    void robots403Unknown() {
        FakeLoader l = new FakeLoader().robotsStatus(HOST, 403).article(URL, ARTICLE_HTML);
        assertThat(fetcher(l, true).fetch(URL, null).status()).isEqualTo(ArticleBodyFetcher.ROBOTS_UNKNOWN);
        assertThat(l.articleRequests()).isZero();
    }

    @Test
    @DisplayName("robots 요청에서 예외가 나면 ROBOTS_UNKNOWN")
    void robotsErrorUnknown() {
        FakeLoader l = new FakeLoader().article(URL, ARTICLE_HTML);
        l.throwOn.add("https://" + HOST + "/robots.txt");
        assertThat(fetcher(l, true).fetch(URL, null).status()).isEqualTo(ArticleBodyFetcher.ROBOTS_UNKNOWN);
    }

    @Test
    @DisplayName("robots 가 200 인데 HTML 이면 규칙 없음으로 허용")
    void robotsHtmlMeansNoRules() {
        FakeLoader l = new FakeLoader().article(URL, ARTICLE_HTML);
        l.pages.put("https://" + HOST + "/robots.txt",
                new PageResponse(200, "text/html", null, null, Jsoup.parse("<html><body>없는 페이지</body></html>")));
        assertThat(fetcher(l, true).fetch(URL, null).status()).isEqualTo(ArticleBodyFetcher.BODY);
    }

    @Test
    @DisplayName("다른 호스트로 리다이렉트하면 FETCH_FAILED")
    void redirectToOtherHostFails() {
        FakeLoader l = new FakeLoader().robots(HOST, "").redirect(URL, "https://other.example.test/a/1");
        BodyResult r = fetcher(l, true).fetch(URL, null);
        assertThat(r.status()).isEqualTo(ArticleBodyFetcher.FETCH_FAILED);
        assertThat(l.requests).doesNotContain("https://other.example.test/a/1");
    }

    @Test
    @DisplayName("같은 호스트(www 유무, 상대 경로)로 리다이렉트하면 따라가서 성공")
    void redirectSameHostSucceeds() {
        FakeLoader l = new FakeLoader().robots(HOST, "")
                .redirect(URL, "https://www." + HOST + "/a/2")
                .article("https://www." + HOST + "/a/2", ARTICLE_HTML);
        assertThat(fetcher(l, true).fetch(URL, null).status()).isEqualTo(ArticleBodyFetcher.BODY);

        FakeLoader l2 = new FakeLoader().robots("m.example.test", "")
                .redirect("https://m.example.test/a", "/b")
                .article("https://m.example.test/b", ARTICLE_HTML);
        assertThat(fetcher(l2, true).fetch("https://m.example.test/a", null).status()).isEqualTo(ArticleBodyFetcher.BODY);
    }

    @Test
    @DisplayName("http 에서 https 로의 리다이렉트는 따라간다")
    void redirectHttpToHttps() {
        FakeLoader l = new FakeLoader().robots("h.example.test", "")
                .article("https://h.example.test/a", ARTICLE_HTML);
        l.pages.put("http://h.example.test/a", new PageResponse(301, "text/html", "https://h.example.test/a", null, null));
        // robots 는 http 출처로 요청되므로 http 쪽 robots 도 준비한다
        l.pages.put("http://h.example.test/robots.txt", new PageResponse(200, "text/plain", null, "", null));
        assertThat(fetcher(l, true).fetch("http://h.example.test/a", null).status()).isEqualTo(ArticleBodyFetcher.BODY);
    }

    @Test
    @DisplayName("리다이렉트는 최대 2번까지만 따라간다")
    void redirectLimit() {
        FakeLoader l = new FakeLoader().robots(HOST, "")
                .redirect(URL, "/b").redirect("https://" + HOST + "/b", "/c")
                .redirect("https://" + HOST + "/c", "/d").article("https://" + HOST + "/d", ARTICLE_HTML);
        assertThat(fetcher(l, true).fetch(URL, null).status()).isEqualTo(ArticleBodyFetcher.FETCH_FAILED);
    }

    @Test
    @DisplayName("리다이렉트 도착 경로가 robots 로 금지면 그 주소를 요청하지 않는다")
    void redirectTargetChecksRobots() {
        FakeLoader l = new FakeLoader().robots(HOST, "User-agent: *\nDisallow: /secret\n")
                .redirect(URL, "/secret/1").article("https://" + HOST + "/secret/1", ARTICLE_HTML);
        assertThat(fetcher(l, true).fetch(URL, null).status()).isEqualTo(ArticleBodyFetcher.ROBOTS_DISALLOWED);
        assertThat(l.requests).doesNotContain("https://" + HOST + "/secret/1");
    }

    @Test
    @DisplayName("200 이 아니면 FETCH_FAILED, 선택자에 안 걸리면 EXTRACT_FAILED")
    void statusAndExtractFailures() {
        FakeLoader l = new FakeLoader().robots(HOST, "");
        l.pages.put(URL, new PageResponse(500, "text/html", null, null, null));
        assertThat(fetcher(l, true).fetch(URL, null).status()).isEqualTo(ArticleBodyFetcher.FETCH_FAILED);

        FakeLoader l2 = new FakeLoader().robots(HOST, "").article(URL, "<p>" + "안내 문구 ".repeat(100) + "</p>");
        assertThat(fetcher(l2, true).fetch(URL, null).status()).isEqualTo(ArticleBodyFetcher.EXTRACT_FAILED);
    }

    @Test
    @DisplayName("꺼져 있으면 DISABLED 이고 아무 요청도 보내지 않는다")
    void disabled() {
        FakeLoader l = new FakeLoader().robots(HOST, "").article(URL, ARTICLE_HTML);
        assertThat(fetcher(l, false).fetch(URL, null).status()).isEqualTo(ArticleBodyFetcher.DISABLED);
        assertThat(l.requests).isEmpty();
    }

    @Test
    @DisplayName("IP 주소·localhost·http 가 아닌 주소는 INVALID_URL 이고 요청하지 않는다")
    void invalidUrls() {
        FakeLoader l = new FakeLoader();
        ArticleBodyFetcher f = fetcher(l, true);
        assertThat(f.fetch("http://10.0.0.5/a", null).status()).isEqualTo(ArticleBodyFetcher.INVALID_URL);
        assertThat(f.fetch("http://localhost/a", null).status()).isEqualTo(ArticleBodyFetcher.INVALID_URL);
        assertThat(f.fetch("http://[::1]/a", null).status()).isEqualTo(ArticleBodyFetcher.INVALID_URL);
        assertThat(f.fetch("ftp://news.example.test/a", null).status()).isEqualTo(ArticleBodyFetcher.INVALID_URL);
        assertThat(f.fetch("", "").status()).isEqualTo(ArticleBodyFetcher.INVALID_URL);
        assertThat(l.requests).isEmpty();
    }

    @Test
    @DisplayName("기사 페이지 로더가 예외를 던지면 FETCH_FAILED (예외는 밖으로 나가지 않는다)")
    void loaderExceptionBecomesFetchFailed() {
        FakeLoader l = new FakeLoader().robots(HOST, "");
        l.throwOn.add(URL);
        assertThat(fetcher(l, true).fetch(URL, null).status()).isEqualTo(ArticleBodyFetcher.FETCH_FAILED);
    }

    @Test
    @DisplayName("robots 는 호스트별로 캐시한다 (같은 호스트 두 번 → robots 요청 한 번)")
    void robotsIsCached() {
        FakeLoader l = new FakeLoader().robots(HOST, "").article(URL, ARTICLE_HTML)
                .article("https://" + HOST + "/a/2", ARTICLE_HTML);
        ArticleBodyFetcher f = fetcher(l, true);
        f.fetch(URL, null);
        f.fetch("https://" + HOST + "/a/2", null);
        assertThat(l.robotsRequests()).isEqualTo(1);
        assertThat(l.articleRequests()).isEqualTo(2);
    }

    @Test
    @DisplayName("robots 캐시는 6시간 뒤 만료된다")
    void robotsCacheExpires() {
        FakeLoader l = new FakeLoader().robots(HOST, "").article(URL, ARTICLE_HTML);
        long[] now = {1_000L};
        ArticleBodyFetcher f = new ArticleBodyFetcher(l, true, 1500, 5000, 1048576, "T", List.of(), () -> now[0]);
        f.fetch(URL, null);
        now[0] += ArticleBodyFetcher.ROBOTS_TTL_MS + 1;
        f.fetch(URL, null);
        assertThat(l.robotsRequests()).isEqualTo(2);
    }

    @Test
    @DisplayName("긴 본문은 문장 끝에서 잘려 maxChars 이하로 돌려준다")
    void longBodyIsCut() {
        String longBody = SENTENCE.repeat(100).trim();
        FakeLoader l = new FakeLoader().robots(HOST, "")
                .article(URL, "<div itemprop=\"articleBody\">" + longBody + "</div>");
        ArticleBodyFetcher f = new ArticleBodyFetcher(l, true, 300, 5000, 1048576, "T", List.of(), System::currentTimeMillis);
        BodyResult r = f.fetch(URL, null);
        assertThat(r.status()).isEqualTo(ArticleBodyFetcher.BODY);
        assertThat(r.text().length()).isLessThanOrEqualTo(300);
        assertThat(r.text()).endsWith("공개했다.");
    }
}
