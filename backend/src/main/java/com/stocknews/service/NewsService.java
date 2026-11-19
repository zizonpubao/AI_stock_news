package com.stocknews.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.Stock;
import com.stocknews.repository.NewsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class NewsService {

    /** 종목 한 개의 본문 수집 총 시간 예산. 넘으면 남은 기사는 BUDGET_EXCEEDED (이미 시작한 요청은 끝까지 기다린다). */
    static final long BODY_BUDGET_MS = 20_000;

    private final NewsRepository newsRepository;
    private final ObjectMapper objectMapper;
    private final ArticleBodyFetcher bodyFetcher;
    private final StockAliases aliases;

    @Autowired
    public NewsService(NewsRepository newsRepository, ObjectMapper objectMapper, ArticleBodyFetcher bodyFetcher,
                       StockAliases aliases) {
        this.newsRepository = newsRepository;
        this.objectMapper = objectMapper;
        this.bodyFetcher = bodyFetcher;
        this.aliases = aliases == null ? StockAliases.none() : aliases;
    }

    /** 별칭 검색을 쓰지 않는 구성 (기존 호출·테스트 호환) */
    public NewsService(NewsRepository newsRepository, ObjectMapper objectMapper, ArticleBodyFetcher bodyFetcher) {
        this(newsRepository, objectMapper, bodyFetcher, StockAliases.none());
    }

    /** 본문 수집을 쓰지 않는 구성 (기존 호출·테스트 호환) */
    public NewsService(NewsRepository newsRepository, ObjectMapper objectMapper) {
        this(newsRepository, objectMapper, ArticleBodyFetcher.disabled(), StockAliases.none());
    }

    @Value("${naver.api.client-id}")
    private String clientId;

    @Value("${naver.api.client-secret}")
    private String clientSecret;

    @Value("${naver.api.news-url}")
    private String newsUrl;

    private static final DateTimeFormatter NAVER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    /**
     * 뉴스 수집 결과. success=false 는 API 호출 실패 또는 응답 해석 실패,
     * success=true 이고 articles 가 비어 있으면 호출은 성공했지만 조건에 맞는 기사가 없는 경우다.
     */
    public record NewsFetchResult(List<NewsArticle> articles, boolean success) {}

    @Transactional
    public NewsFetchResult fetchAndSaveNews(Stock stock) {
        log.info("뉴스 수집 시작: {}", stock.getName());
        newsRepository.deleteByStock(stock);

        List<NewsArticle> articles = new ArrayList<>();

        try {
            // 검색어 = 공식 종목명 + 별칭. 이름마다 API 를 한 번씩 부른다(정확히 일치 검색).
            List<String> aliasNames = aliases.aliasesFor(stock.getCode(), stock.getName());

            // 공식 종목명 호출이 실패하면 예외 → 아래 catch 에서 종목 전체 실패로 처리
            List<String> responses = new ArrayList<>();
            responses.add(callNewsApi("\"" + stock.getName() + "\""));
            for (String alias : aliasNames) {
                try {
                    responses.add(callNewsApi("\"" + alias + "\""));
                } catch (Exception e) {
                    // 별칭 호출 실패는 종목 실패가 아니다. 그 별칭 결과만 빠진다.
                    log.warn("별칭 뉴스 검색 실패 ({} / {}): {}", stock.getName(), alias, e.getMessage());
                    responses.add(null);
                }
            }

            // 공식 응답을 해석하지 못하면 예외 → 아래 catch 에서 실패로 처리
            List<NewsArticle> parsed = parseNews(responses, stock.getName(), aliasNames, ZonedDateTime.now());
            attachBodies(parsed, BODY_BUDGET_MS); // 실패해도 예외를 던지지 않고 기사별 상태로만 남긴다
            for (NewsArticle article : parsed) {
                article.setStock(stock);
                newsRepository.save(article);
                articles.add(article);
            }

            log.info("뉴스 {}건 저장: {}", articles.size(), stock.getName());
            if (!aliasNames.isEmpty()) {
                long aliasOnly = articles.stream()
                        .filter(a -> !mentionsAsOwnWord(a.getTitle(), stock.getName())).count();
                log.info("별칭 검색 - {}: 검색어 {}개, 채택 {}건 중 별칭 표기로만 걸린 기사 {}건",
                        stock.getName(), aliasNames.size() + 1, articles.size(), aliasOnly);
            }
            return new NewsFetchResult(articles, true);

        } catch (Exception e) {
            // 실패를 "뉴스 없음"으로 위장하지 않는다. 호출 측이 success=false 를 보고 상태를 FAILED 로 기록한다.
            log.error("뉴스 수집 실패 ({}): {}", stock.getName(), e.getMessage());
            return new NewsFetchResult(List.of(), false);
        }
    }

    /** 네이버 뉴스 검색 API 를 한 번 호출한다 (query 는 이미 따옴표로 감싼 검색어). 테스트에서 바꿔 끼울 수 있게 분리. */
    String callNewsApi(String query) {
        // TLS 인증서 검증은 JDK 기본 신뢰 저장소로 수행한다.
        // (이전에는 모든 인증서를 신뢰하도록 검증을 꺼 두어, 중간자 공격 시 API 시크릿이 노출될 수 있었다.)
        WebClient client = WebClient.builder()
                .baseUrl(newsUrl)
                .defaultHeader("X-Naver-Client-Id", clientId)
                .defaultHeader("X-Naver-Client-Secret", clientSecret)
                .build();

        return client.get()
                .uri(uriBuilder -> uriBuilder
                        .queryParam("query", query)
                        .queryParam("display", 30) // 중복·48시간·종목명 필터 후에도 5건을 채우기 위해 20→30
                        .queryParam("sort", "sim")
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(10)); // 응답이 없을 때 무한 대기하지 않도록 제한
    }

    /**
     * 저장할 기사마다 본문을 가져와 body·bodyStatus·bodyChars 를 채운다. 어떤 경우에도 예외를 던지지 않으며
     * 기사 목록 자체는 바꾸지 않는다. budgetMs 를 넘기면 남은 기사는 BUDGET_EXCEEDED.
     */
    void attachBodies(List<NewsArticle> articles, long budgetMs) {
        long start = System.nanoTime();
        for (NewsArticle article : articles) {
            ArticleBodyFetcher.BodyResult result;
            try {
                if (!bodyFetcher.isEnabled()) {
                    result = new ArticleBodyFetcher.BodyResult(ArticleBodyFetcher.DISABLED, null);
                } else if ((System.nanoTime() - start) / 1_000_000 >= budgetMs) {
                    result = new ArticleBodyFetcher.BodyResult(ArticleBodyFetcher.BUDGET_EXCEEDED, null);
                } else {
                    result = bodyFetcher.fetch(article.getOriginalLink(), article.getLink());
                }
            } catch (Exception e) {
                result = new ArticleBodyFetcher.BodyResult(ArticleBodyFetcher.FETCH_FAILED, null);
            }
            if (result == null) result = new ArticleBodyFetcher.BodyResult(ArticleBodyFetcher.FETCH_FAILED, null);
            boolean used = ArticleBodyFetcher.BODY.equals(result.status())
                    && result.text() != null && !result.text().isBlank();
            if (used) {
                article.setBody(result.text());
                article.setBodyStatus(ArticleBodyFetcher.BODY);
                article.setBodyChars(result.text().length());
            } else {
                article.setBody(null);
                article.setBodyStatus(ArticleBodyFetcher.BODY.equals(result.status())
                        ? ArticleBodyFetcher.EXTRACT_FAILED : result.status());
                article.setBodyChars(null);
            }
        }
    }

    /**
     * 네이버 뉴스 검색 응답(JSON)을 기사 목록으로 변환한다. 네트워크·DB 를 쓰지 않는 순수 로직.
     * 48시간 이내, 제목에 종목명 포함, 최대 5건, HTML 태그 제거. stock 은 설정하지 않는다.
     *
     * @throws IOException 응답이 비었거나 JSON 이 아니거나 items 배열이 없을 때 (= 해석 실패)
     */
    List<NewsArticle> parseNews(String response, String stockName, ZonedDateTime now) throws IOException {
        return parseNews(List.of(response == null ? "" : response), stockName, List.of(), now);
    }

    /**
     * 공식 종목명 응답(responses[0])과 별칭 응답(나머지)을 번갈아 합쳐(round-robin) 같은 필터를 적용한다.
     * 제목에 공식 종목명 또는 별칭이 독립 단어로 들어 있어야 한다. 별칭 응답이 null·비정상이면 건너뛴다.
     *
     * @throws IOException 공식 종목명 응답을 해석하지 못할 때
     */
    List<NewsArticle> parseNews(List<String> responses, String stockName, List<String> aliasNames, ZonedDateTime now)
            throws IOException {
        String official = (responses == null || responses.isEmpty()) ? null : responses.get(0);
        List<List<JsonNode>> lists = new ArrayList<>();
        lists.add(itemsOf(official));   // 해석 실패 시 IOException
        for (int i = 1; responses != null && i < responses.size(); i++) {
            try {
                lists.add(itemsOf(responses.get(i)));
            } catch (IOException e) {
                log.warn("별칭 응답 해석 실패, 건너뜀 ({}): {}", stockName, e.getMessage());
            }
        }
        // 별칭이 여러 개여도 공식 종목명 결과가 밀리지 않도록, 별칭 응답들은 먼저 한 줄(별칭 줄)로 번갈아 합치고
        // 그다음 공식 줄과 별칭 줄을 번갈아 놓는다: 공식1, 별칭1, 공식2, 별칭2, 공식3 …
        List<JsonNode> officialLane = lists.get(0);
        List<JsonNode> aliasLane = interleave(lists.subList(1, lists.size()));
        List<JsonNode> merged = interleave(List.of(officialLane, aliasLane));

        List<String> names = new ArrayList<>();
        names.add(stockName);
        if (aliasNames != null) {
            for (String a : aliasNames) if (a != null && !a.isEmpty() && !names.contains(a)) names.add(a);
        }

        ZonedDateTime cutoff = now.minusHours(48);
        List<NewsArticle> result = new ArrayList<>();
        List<String> keptTitles = new ArrayList<>();   // 채택한 기사의 정규화 제목 (중복 비교용)
        Set<String> keptLinks = new HashSet<>();
        int duplicates = 0;
        int otherCompany = 0;
        for (JsonNode item : merged) {
            if (result.size() >= 5) break;

            // 48시간 필터
            String pubDateStr = item.path("pubDate").asText("");
            if (!isWithin48Hours(pubDateStr, cutoff)) {
                log.debug("48시간 초과 제외: {}", pubDateStr);
                continue;
            }

            // 2차: 제목에 종목명 포함 여부 확인
            String title = cleanHtml(item.path("title").asText(""));
            if (!containsAny(title, names)) {
                log.debug("종목명 미포함 제외: {}", title);
                continue;
            }
            // 종목명이 다른 단어의 앞부분으로만 나오는 기사(예: 에스엠 종목의 "에스엠벡셀")는 다른 회사 기사로 본다
            if (!mentionsAnyAsOwnWord(title, names)) {
                otherCompany++;
                log.debug("다른 단어 일부로만 종목명이 나와 제외: {}", title);
                continue;
            }

            // 중복 제거: 같은 링크이거나 제목이 (거의) 같으면 먼저 나온 기사만 남긴다
            String link = item.path("link").asText("");
            String normalized = normalizeTitle(title, names);
            if ((!link.isBlank() && keptLinks.contains(link)) || isDuplicateOfAny(normalized, keptTitles)) {
                duplicates++;
                log.debug("중복 기사 제외: {}", title);
                continue;
            }

            NewsArticle article = new NewsArticle();
            article.setTitle(title);
            article.setDescription(cleanHtml(item.path("description").asText("")));
            article.setLink(link);
            article.setOriginalLink(item.path("originallink").asText(""));
            article.setPubDate(pubDateStr);
            result.add(article);
            keptTitles.add(normalized);
            if (!link.isBlank()) keptLinks.add(link);
        }
        log.debug("중복 기사 {}건, 다른 단어 일부로만 종목명이 나온 기사 {}건 제외 ({}): 최종 {}건",
                duplicates, otherCompany, stockName, result.size());
        return result;
    }

    private List<JsonNode> itemsOf(String response) throws IOException {
        if (response == null || response.isBlank()) {
            throw new IOException("뉴스 API 응답이 비어 있음");
        }
        JsonNode root = objectMapper.readTree(response);
        JsonNode items = root.get("items");
        if (items == null || !items.isArray()) {
            throw new IOException("뉴스 API 응답에 items 배열이 없음");
        }
        List<JsonNode> list = new ArrayList<>();
        items.forEach(list::add);
        return list;
    }

    /** 여러 목록을 앞에서부터 하나씩 번갈아 뽑아 한 줄로 만든다. 순수 함수. */
    static <T> List<T> interleave(List<List<T>> lists) {
        List<T> out = new ArrayList<>();
        for (int idx = 0, added = 1; added > 0; idx++) {
            added = 0;
            for (List<T> l : lists) {
                if (idx < l.size()) {
                    out.add(l.get(idx));
                    added++;
                }
            }
        }
        return out;
    }

    private static boolean containsAny(String title, List<String> names) {
        for (String n : names) if (title.contains(n)) return true;
        return false;
    }

    private static boolean mentionsAnyAsOwnWord(String title, List<String> names) {
        for (String n : names) if (mentionsAsOwnWord(title, n)) return true;
        return false;
    }

    /**
     * 종목명 바로 뒤에 붙어도 같은 종목을 가리키는 것으로 보는 꼬리(종목명 뒤 연속된 한글·영문·숫자 문자열).
     * 꼬리 전체가 항목과 정확히 같아야 한다(접두 일치 금지: "두산에너빌리티"의 "에너빌리티"는 조사 "에"로 통과하지 않는다).
     * <p>근거: 2026-11-18 아카이브(36거래일, 기사 1,487건)와 같은 날 운영 수집분 47건의 제목에서 종목명 뒤 꼬리를 쟀다.
     * 관찰된 정상 꼬리는 와, 는, 도, 가, 의, 에, 과, 만, 보다, 부터, 까지, 처럼, 사장, 그룹, 그룹주 이고,
     * 은, 이, 을, 를, 로, 에서, 에게 와 둘째 줄의 겹친 조사(에도, 에는, 와의 등)는 데이터에서 관찰하지 못했고
     * 문법상 조사라서 넣었다(조사를 넣어도 다른 회사 이름이 통과할 위험은 낮고, 빠뜨리면 정상 기사를 버린다).
     * <p>부작용(같은 데이터로 측정): 아카이브 1,487건 중 29건(1.9%)이 제외된다. 그중 13건은 따로 상장된 회사
     * (카카오뱅크·카카오게임즈·카카오페이) 기사이고, 14건은 자회사·브랜드(삼성전자서비스, 카카오엔터 등) 기사,
     * 2건은 ETF 상품명 기사다. 자회사 기사는 제목에 종목명이 독립 단어로 없으면 함께 제외된다.
     * (發, 株, 上 같은 한자는 한글·영문·숫자가 아니라 꼬리가 비므로 목록 없이 통과한다.)
     * <p>한계: 목록에 없는 새 표현이 붙은 정상 기사는 제외된다. 종목명과 정확히 같은 이름을 쓰는 다른 회사,
     * 종목명 앞에 다른 글자가 붙은 경우(앞 글자는 검사하지 않는다)는 걸러내지 못한다.
     */
    static final Set<String> ALLOWED_NAME_TAILS = Set.of(
            "은", "는", "이", "가", "을", "를", "의", "에", "와", "과", "도", "만", "로",
            "에서", "에게", "까지", "부터", "보다", "처럼",
            "에도", "에는", "엔", "와의", "과의", "에서도", "으로", "만의", "에만", "로부터", "마저", "조차",
            "사장", "그룹", "그룹주");

    /**
     * 제목에서 종목명이 한 번이라도 독립된 단어(꼬리 없음)이거나 허용된 꼬리(조사 등)와 함께 나오면 true.
     * 모든 등장 위치가 다른 단어의 앞부분(예: 에스엠 → 에스엠벡셀)이면 false. 종목명이 없거나 비어 있으면 기존대로
     * 단순 포함 여부를 따른다. 네트워크·DB 를 쓰지 않는 순수 함수.
     */
    static boolean mentionsAsOwnWord(String title, String stockName) {
        if (title == null) return false;
        if (stockName == null || stockName.isEmpty()) return title.contains(stockName == null ? "" : stockName);
        int idx = title.indexOf(stockName);
        if (idx < 0) return false;
        while (idx >= 0) {
            int start = idx + stockName.length();
            int end = start;
            while (end < title.length() && isNameChar(title.charAt(end))) end++;
            String tail = title.substring(start, end);
            if (tail.isEmpty() || ALLOWED_NAME_TAILS.contains(tail)) return true;
            idx = title.indexOf(stockName, idx + 1);
        }
        return false;
    }

    private static boolean isNameChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '가' && c <= '힣');
    }

    /** 제목 유사도가 이 값 이상이면 같은 기사로 본다. 문자 2-gram 자카드 기준. */
    static final double DUPLICATE_SIMILARITY_THRESHOLD = 0.7;
    /** 비교 대상(종목명 제거 후) 글자 수가 이보다 짧으면 2-gram 이 너무 적어 완전 일치만 중복으로 본다. */
    private static final int MIN_FUZZY_LENGTH = 5;

    private static final Pattern LEADING_TAG = Pattern.compile("^\\s*[\\[(【<][^\\]\\)】>]*[\\]\\)】>]\\s*");
    private static final Pattern TRAILING_TAG = Pattern.compile("\\s*[\\[(【<][^\\]\\)】>]*[\\]\\)】>]\\s*$");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]");
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    /**
     * 중복 비교용 제목 정규화. 앞뒤의 [속보]·[단독]·(종합) 같은 머리말·꼬리말을 떼고, 소문자로 바꾸고,
     * 공백·문장부호·따옴표를 지운 뒤 종목명을 제거한다(모든 기사 제목에 들어 있어 유사도를 부풀리므로).
     */
    static String normalizeTitle(String title, String stockName) {
        return normalizeTitle(title, stockName == null ? List.<String>of() : List.of(stockName));
    }

    /** 여러 이름(공식명·별칭)을 긴 것부터 제거하는 정규화. 이름이 하나면 위 오버로드와 같다. */
    static String normalizeTitle(String title, List<String> names) {
        String t = title == null ? "" : title;
        String prev;
        do {
            prev = t;
            t = LEADING_TAG.matcher(t).replaceFirst("");
            t = TRAILING_TAG.matcher(t).replaceFirst("");
        } while (!t.equals(prev));
        t = NON_WORD.matcher(t.toLowerCase(Locale.ROOT)).replaceAll("");
        List<String> normNames = new ArrayList<>();
        for (String n : names) {
            if (n == null) continue;
            String name = NON_WORD.matcher(n.toLowerCase(Locale.ROOT)).replaceAll("");
            if (!name.isEmpty()) normNames.add(name);
        }
        normNames.sort((x, y) -> Integer.compare(y.length(), x.length()));
        for (String name : normNames) t = t.replace(name, "");
        return t;
    }

    /** 정규화된 두 제목의 문자 2-gram 자카드 유사도(0~1). 순수 함수. */
    static double bigramJaccard(String a, String b) {
        Set<String> sa = bigrams(a);
        Set<String> sb = bigrams(b);
        if (sa.isEmpty() || sb.isEmpty()) return 0.0;
        Set<String> inter = new HashSet<>(sa);
        inter.retainAll(sb);
        int union = sa.size() + sb.size() - inter.size();
        return (double) inter.size() / union;
    }

    private static Set<String> bigrams(String s) {
        Set<String> set = new HashSet<>();
        for (int i = 0; i + 2 <= s.length(); i++) set.add(s.substring(i, i + 2));
        return set;
    }

    /**
     * 정규화된 두 제목이 같은 기사인지. 완전 일치이거나, (충분히 길고, 숫자 구성이 같고, 유사도가 임계값 이상)이면 true.
     * 숫자가 다르면(예: 1분기 vs 2분기) 문자 대부분이 같아도 다른 사실이므로 묶지 않는다.
     */
    static boolean isSimilarTitle(String a, String b) {
        if (a.equals(b)) return true;
        if (a.length() < MIN_FUZZY_LENGTH || b.length() < MIN_FUZZY_LENGTH) return false;
        if (!numbersOf(a).equals(numbersOf(b))) return false;
        return bigramJaccard(a, b) >= DUPLICATE_SIMILARITY_THRESHOLD;
    }

    private static List<String> numbersOf(String s) {
        List<String> nums = new ArrayList<>();
        Matcher m = NUMBER.matcher(s);
        while (m.find()) nums.add(m.group());
        return nums;
    }

    private static boolean isDuplicateOfAny(String normalized, List<String> kept) {
        for (String k : kept) {
            if (isSimilarTitle(normalized, k)) return true;
        }
        return false;
    }

    private boolean isWithin48Hours(String pubDateStr, ZonedDateTime cutoff) {
        try {
            ZonedDateTime pubDate = ZonedDateTime.parse(pubDateStr, NAVER_DATE_FORMAT);
            return pubDate.isAfter(cutoff);
        } catch (DateTimeParseException e) {
            log.warn("날짜 파싱 실패, 포함 처리: {}", pubDateStr);
            return true;
        }
    }

    private String cleanHtml(String text) {
        return text.replaceAll("<[^>]*>", "").trim();
    }
}