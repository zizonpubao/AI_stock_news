package com.stocknews.service;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * 기사 원문 주소에서 본문 글자를 가져온다 (사진은 수집하지 않는다).
 *
 * 원칙: 틀린 본문은 요약문보다 나쁘다. 지정한 선택자(호스트별 표, 공통 선택자)에 걸린 영역만 본문으로 쓰고,
 * 걸리지 않으면 추출 실패로 둔다 (일반 추정 규칙 없음). robots.txt 가 금지하거나 확인되지 않으면 기사 페이지를 요청하지 않는다.
 * 어떤 예외도 밖으로 던지지 않고 상태값으로 돌려준다. 로그에는 호스트와 상태만 남기고 본문은 찍지 않는다.
 *
 * 한계: 호스트 이름을 IP·localhost 로 막지만, 이름이 사설 주소로 해석되는 경우(DNS)는 막지 않는다.
 * www 유무가 다른 호스트로의 리다이렉트는 원래 호스트의 robots 규칙으로 판정한다.
 */
@Slf4j
@Service
public class ArticleBodyFetcher {

    public static final String BODY = "BODY";
    public static final String DISABLED = "DISABLED";
    public static final String INVALID_URL = "INVALID_URL";
    public static final String ROBOTS_DISALLOWED = "ROBOTS_DISALLOWED";
    /** robots.txt 가 검색·인용형 AI 수집기(설정 news.body.ai-agents 의 이름)를 금지한 호스트 */
    public static final String AI_DISALLOWED = "AI_DISALLOWED";
    public static final String ROBOTS_UNKNOWN = "ROBOTS_UNKNOWN";
    public static final String FETCH_FAILED = "FETCH_FAILED";
    public static final String EXTRACT_FAILED = "EXTRACT_FAILED";
    public static final String BUDGET_EXCEEDED = "BUDGET_EXCEEDED";

    /** robots 판정에 쓰는 우리 토큰 */
    static final String ROBOTS_TOKEN = "NewsBodyFetcher";
    static final long ROBOTS_TTL_MS = 6L * 60 * 60 * 1000;
    /** robots 를 확인하지 못한 결과는 짧게만 기억한다 (일시 장애가 6시간 이어지지 않도록) */
    static final long ROBOTS_UNKNOWN_TTL_MS = 10L * 60 * 1000;
    static final int MIN_BODY_CHARS = 200;
    static final int MAX_REDIRECTS = 2;

    /**
     * 호스트(www. 제거) → 본문 선택자. 표에 있으면 그것만 본다.
     * 공통 선택자로 잡히지 않아 2026-11-19 에 실제 페이지에서 본문 위치를 확인하고 추가한 언론사들이다.
     */
    static final Map<String, String> HOST_SELECTORS = Map.of(
            "kjdaily.com", "#content",
            "kwangju.co.kr", "#joinskmbox",
            "sentv.co.kr", "#newsView",
            "thepowernews.co.kr", ".detailCont",
            "digitalchosun.dizzo.com", "#article");
    static final List<String> COMMON_SELECTORS = List.of("[itemprop=articleBody]", "#article-view-content-div");
    static final String REMOVE_SELECTOR =
            "script, style, noscript, iframe, figure, figcaption, img, button, aside, form, nav, header, footer";

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    public record BodyResult(String status, String text) {}

    /** text 는 HTML 이 아닌 응답(robots.txt 등)의 문자열, document 는 HTML 응답을 파싱한 결과. 해당 없으면 null. */
    public record PageResponse(int status, String contentType, String location, String text, Document document) {}

    /** HTTP 접근을 감싼 작은 인터페이스. 리다이렉트는 따라가지 않고 응답 그대로 돌려줘야 한다. */
    public interface PageLoader {
        PageResponse load(String url, String userAgent, int timeoutMs, int maxBytes) throws IOException;
    }

    private record RobotsEntry(RobotsRules rules, long expiresAtMs) {}

    private final PageLoader loader;
    private final boolean enabled;
    private final int maxChars;
    private final int timeoutMs;
    private final int maxBytes;
    private final String userAgent;
    private final List<String> aiAgents;
    private final LongSupplier clockMs;
    private final Map<String, RobotsEntry> robotsCache = new ConcurrentHashMap<>();

    @Autowired
    public ArticleBodyFetcher(
            @Value("${news.body.enabled:true}") boolean enabled,
            @Value("${news.body.max-chars:1500}") int maxChars,
            @Value("${news.body.timeout-ms:5000}") int timeoutMs,
            @Value("${news.body.max-bytes:1048576}") int maxBytes,
            @Value("${news.body.user-agent:Mozilla/5.0 (compatible; NewsBodyFetcher/1.0)}") String userAgent,
            @Value("${news.body.ai-agents:OAI-SearchBot,ChatGPT-User,Claude-SearchBot,Claude-User,PerplexityBot,Perplexity-User}") String aiAgents) {
        this(new JsoupPageLoader(), enabled, maxChars, timeoutMs, maxBytes, userAgent, splitAgents(aiAgents),
                System::currentTimeMillis);
    }

    ArticleBodyFetcher(PageLoader loader, boolean enabled, int maxChars, int timeoutMs, int maxBytes,
                       String userAgent, List<String> aiAgents, LongSupplier clockMs) {
        this.loader = loader;
        this.enabled = enabled;
        this.maxChars = maxChars;
        this.timeoutMs = timeoutMs;
        this.maxBytes = maxBytes;
        this.userAgent = userAgent;
        this.aiAgents = aiAgents;
        this.clockMs = clockMs;
    }

    /** 꺼진 인스턴스 (NewsService 의 기존 2인자 생성자용). 모든 요청이 DISABLED 로 끝난다. */
    static ArticleBodyFetcher disabled() {
        return new ArticleBodyFetcher(new JsoupPageLoader(), false, 1500, 5000, 1048576,
                "Mozilla/5.0 (compatible; NewsBodyFetcher/1.0)", List.of(), System::currentTimeMillis);
    }

    private static List<String> splitAgents(String raw) {
        List<String> list = new ArrayList<>();
        if (raw != null) {
            for (String s : raw.split(",")) if (!s.isBlank()) list.add(s.trim());
        }
        return list;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 본문을 가져온다. 어떤 예외도 던지지 않는다. originalLink 가 비어 있으면 link 를 쓴다. */
    public BodyResult fetch(String originalLink, String link) {
        if (!enabled) return new BodyResult(DISABLED, null);
        String host = "?";
        try {
            String target = (originalLink != null && !originalLink.isBlank()) ? originalLink : link;
            URI uri = parseTarget(target);
            if (uri == null) {
                log.debug("본문 수집 {}: 주소 사용 불가", INVALID_URL);
                return new BodyResult(INVALID_URL, null);
            }
            host = uri.getHost();
            BodyResult result = doFetch(uri);
            log.debug("본문 수집 {} → {}", host, result.status());
            return result;
        } catch (Exception e) {
            log.debug("본문 수집 {} → {} ({})", host, FETCH_FAILED, e.getClass().getSimpleName());
            return new BodyResult(FETCH_FAILED, null);
        }
    }

    private BodyResult doFetch(URI uri) {
        RobotsRules rules = robotsFor(uri);
        if (rules == null) return new BodyResult(ROBOTS_UNKNOWN, null);
        String blocked = checkRobots(rules, uri);
        if (blocked != null) return new BodyResult(blocked, null);

        URI current = uri;
        for (int hop = 0; ; hop++) {
            PageResponse r;
            try {
                r = loader.load(current.toString(), userAgent, timeoutMs, maxBytes);
            } catch (Exception e) {
                return new BodyResult(FETCH_FAILED, null);
            }
            if (r == null) return new BodyResult(FETCH_FAILED, null);
            if (r.status() >= 300 && r.status() < 400) {
                if (hop >= MAX_REDIRECTS) return new BodyResult(FETCH_FAILED, null);
                URI next = redirectTarget(current, r.location());
                if (next == null) return new BodyResult(FETCH_FAILED, null);
                blocked = checkRobots(rules, next);
                if (blocked != null) return new BodyResult(blocked, null);
                current = next;
                continue;
            }
            if (r.status() != 200) return new BodyResult(FETCH_FAILED, null);
            if (r.document() == null) return new BodyResult(EXTRACT_FAILED, null);
            Optional<String> body = extractBody(r.document(), current.getHost());
            if (body.isEmpty()) return new BodyResult(EXTRACT_FAILED, null);
            return new BodyResult(BODY, cutAtSentence(body.get(), maxChars));
        }
    }

    // ===== 주소 =====

    /** http/https 만, IP 주소·localhost 가 아닌 호스트만 허용. 아니면 null. */
    static URI parseTarget(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return null;
            String host = uri.getHost();
            if (host == null || host.isBlank()) return null;
            String h = host.toLowerCase(Locale.ROOT);
            if (h.equals("localhost") || h.endsWith(".localhost") || h.contains(":") || h.startsWith("[")
                    || IPV4.matcher(h).matches()) return null;
            return uri;
        } catch (Exception e) {
            return null;
        }
    }

    static String pathAndQuery(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    private static String stripWww(String host) {
        String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    /** 같은 호스트(www 유무 차이 허용)이고 스킴이 같거나 http→https 일 때만 리다이렉트를 따라간다. 아니면 null. */
    private static URI redirectTarget(URI current, String location) {
        if (location == null || location.isBlank()) return null;
        try {
            URI next = parseTarget(current.resolve(location.trim()).toString());
            if (next == null) return null;
            if (!stripWww(next.getHost()).equals(stripWww(current.getHost()))) return null;
            String from = current.getScheme().toLowerCase(Locale.ROOT);
            String to = next.getScheme().toLowerCase(Locale.ROOT);
            if (!(from.equals(to) || (from.equals("http") && to.equals("https")))) return null;
            return next;
        } catch (Exception e) {
            return null;
        }
    }

    // ===== robots.txt =====

    /** 금지면 상태값(ROBOTS_DISALLOWED / AI_DISALLOWED), 허용이면 null. */
    private String checkRobots(RobotsRules rules, URI uri) {
        String pq = pathAndQuery(uri);
        if (!rules.isAllowed(ROBOTS_TOKEN, pq)) return ROBOTS_DISALLOWED;
        for (String agent : aiAgents) {
            if (!rules.isAllowed(agent, pq)) return AI_DISALLOWED;
        }
        return null;
    }

    /** 호스트의 robots 규칙(캐시). 확인하지 못했으면 null. */
    private RobotsRules robotsFor(URI uri) {
        String origin = uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        long now = clockMs.getAsLong();
        RobotsEntry cached = robotsCache.get(origin);
        if (cached != null && cached.expiresAtMs() > now) return cached.rules();

        RobotsRules rules = loadRobots(origin);
        long ttl = rules == null ? ROBOTS_UNKNOWN_TTL_MS : ROBOTS_TTL_MS;
        robotsCache.put(origin, new RobotsEntry(rules, now + ttl));
        return rules;
    }

    private RobotsRules loadRobots(String origin) {
        try {
            URI current = new URI(origin + "/robots.txt");
            for (int hop = 0; ; hop++) {
                PageResponse r = loader.load(current.toString(), userAgent, timeoutMs, maxBytes);
                if (r == null) return null;
                int s = r.status();
                if (s >= 300 && s < 400) {
                    if (hop >= MAX_REDIRECTS) return null;
                    URI next = redirectTarget(current, r.location());
                    if (next == null) return null;
                    current = next;
                    continue;
                }
                if (s == 404 || s == 410) return RobotsRules.ALLOW_ALL;
                if (s != 200) return null; // 401, 403, 5xx 등: 확인하지 못함
                if (looksLikeHtml(r)) return RobotsRules.ALLOW_ALL;
                return RobotsRules.parse(r.text());
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean looksLikeHtml(PageResponse r) {
        if (r.document() != null) return true;
        String ct = r.contentType() == null ? "" : r.contentType().toLowerCase(Locale.ROOT);
        if (ct.contains("html")) return true;
        String t = r.text() == null ? "" : r.text().stripLeading().toLowerCase(Locale.ROOT);
        return t.startsWith("<!doctype html") || t.startsWith("<html");
    }

    // ===== 본문 추출 (순수 함수) =====

    /**
     * 지정한 선택자에 걸린 영역의 글자를 돌려준다. 걸리지 않거나 공백 정리 후 200자 미만이면 empty.
     * 호스트는 www. 를 뗀 값으로 표와 비교한다. 인자 문서는 바꾸지 않는다.
     */
    static Optional<String> extractBody(Document doc, String host) {
        if (doc == null) return Optional.empty();
        Document copy = doc.clone();
        copy.select(REMOVE_SELECTOR).remove();

        String hostSelector = HOST_SELECTORS.get(stripWww(host));
        List<String> selectors = hostSelector != null ? List.of(hostSelector) : COMMON_SELECTORS;
        for (String selector : selectors) {
            Element el = copy.selectFirst(selector);
            if (el == null) continue;
            String text = el.text().replaceAll("[\\s\\u00a0]+", " ").trim();
            if (text.length() >= MIN_BODY_CHARS) return Optional.of(text);
        }
        return Optional.empty();
    }

    /**
     * maxChars 이하면 그대로, 넘으면 maxChars 안의 마지막 문장 끝(. ? ! 뒤가 공백이거나 끝)까지 자른다.
     * 문장 끝이 앞 60% 안에만 있으면 maxChars 에서 자른다.
     */
    static String cutAtSentence(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        int lastEnd = -1;
        for (int i = maxChars - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '?' || c == '!') {
                boolean boundary = i + 1 >= text.length() || Character.isWhitespace(text.charAt(i + 1));
                if (boundary) {
                    lastEnd = i + 1;
                    break;
                }
            }
        }
        if (lastEnd > 0 && lastEnd >= maxChars * 0.6) return text.substring(0, lastEnd).trim();
        return text.substring(0, maxChars).trim();
    }

    // ===== 기본 HTTP 구현 =====

    static final class JsoupPageLoader implements PageLoader {
        @Override
        public PageResponse load(String url, String userAgent, int timeoutMs, int maxBytes) throws IOException {
            Connection.Response res = Jsoup.connect(url)
                    .userAgent(userAgent)
                    .timeout(timeoutMs)
                    .maxBodySize(maxBytes)
                    .followRedirects(false)
                    .ignoreHttpErrors(true)
                    .ignoreContentType(true)
                    .execute();
            String ct = res.contentType();
            boolean html = ct != null && ct.toLowerCase(Locale.ROOT).contains("html");
            Document doc = null;
            String text = null;
            if (res.statusCode() == 200) {
                if (html) doc = res.parse(); // 문자 인코딩(헤더·meta·BOM)은 Jsoup 이 판별한다
                else text = res.body();
            }
            return new PageResponse(res.statusCode(), ct, res.header("Location"), text, doc);
        }
    }
}
