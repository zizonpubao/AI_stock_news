package com.stocknews.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.Stock;
import com.stocknews.repository.NewsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
public class NewsService {

    private final NewsRepository newsRepository;
    private final ObjectMapper objectMapper;

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
            String exactQuery = "\"" + stock.getName() + "\"";  // 1차: 정확히 일치 검색

            // TLS 인증서 검증은 JDK 기본 신뢰 저장소로 수행한다.
            // (이전에는 모든 인증서를 신뢰하도록 검증을 꺼 두어, 중간자 공격 시 API 시크릿이 노출될 수 있었다.)
            WebClient client = WebClient.builder()
                    .baseUrl(newsUrl)
                    .defaultHeader("X-Naver-Client-Id", clientId)
                    .defaultHeader("X-Naver-Client-Secret", clientSecret)
                    .build();

            String response = client.get()
                    .uri(uriBuilder -> uriBuilder
                            .queryParam("query", exactQuery)
                            .queryParam("display", 30) // 중복·48시간·종목명 필터 후에도 5건을 채우기 위해 20→30
                            .queryParam("sort", "sim")
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10)); // 응답이 없을 때 무한 대기하지 않도록 제한

            // 응답을 해석하지 못하면 예외 → 아래 catch 에서 실패로 처리
            for (NewsArticle article : parseNews(response, stock.getName(), ZonedDateTime.now())) {
                article.setStock(stock);
                newsRepository.save(article);
                articles.add(article);
            }

            log.info("뉴스 {}건 저장: {}", articles.size(), stock.getName());
            return new NewsFetchResult(articles, true);

        } catch (Exception e) {
            // 실패를 "뉴스 없음"으로 위장하지 않는다. 호출 측이 success=false 를 보고 상태를 FAILED 로 기록한다.
            log.error("뉴스 수집 실패 ({}): {}", stock.getName(), e.getMessage());
            return new NewsFetchResult(List.of(), false);
        }
    }

    /**
     * 네이버 뉴스 검색 응답(JSON)을 기사 목록으로 변환한다. 네트워크·DB 를 쓰지 않는 순수 로직.
     * 48시간 이내, 제목에 종목명 포함, 최대 5건, HTML 태그 제거. stock 은 설정하지 않는다.
     *
     * @throws IOException 응답이 비었거나 JSON 이 아니거나 items 배열이 없을 때 (= 해석 실패)
     */
    List<NewsArticle> parseNews(String response, String stockName, ZonedDateTime now) throws IOException {
        if (response == null || response.isBlank()) {
            throw new IOException("뉴스 API 응답이 비어 있음");
        }
        JsonNode root = objectMapper.readTree(response);
        JsonNode items = root.get("items");
        if (items == null || !items.isArray()) {
            throw new IOException("뉴스 API 응답에 items 배열이 없음");
        }

        ZonedDateTime cutoff = now.minusHours(48);
        List<NewsArticle> result = new ArrayList<>();
        List<String> keptTitles = new ArrayList<>();   // 채택한 기사의 정규화 제목 (중복 비교용)
        Set<String> keptLinks = new HashSet<>();
        int duplicates = 0;
        for (JsonNode item : items) {
            if (result.size() >= 5) break;

            // 48시간 필터
            String pubDateStr = item.path("pubDate").asText("");
            if (!isWithin48Hours(pubDateStr, cutoff)) {
                log.debug("48시간 초과 제외: {}", pubDateStr);
                continue;
            }

            // 2차: 제목에 종목명 포함 여부 확인
            String title = cleanHtml(item.path("title").asText(""));
            if (!title.contains(stockName)) {
                log.debug("종목명 미포함 제외: {}", title);
                continue;
            }

            // 중복 제거: 같은 링크이거나 제목이 (거의) 같으면 먼저 나온 기사만 남긴다
            String link = item.path("link").asText("");
            String normalized = normalizeTitle(title, stockName);
            if ((!link.isBlank() && keptLinks.contains(link)) || isDuplicateOfAny(normalized, keptTitles)) {
                duplicates++;
                log.debug("중복 기사 제외: {}", title);
                continue;
            }

            NewsArticle article = new NewsArticle();
            article.setTitle(title);
            article.setDescription(cleanHtml(item.path("description").asText("")));
            article.setLink(link);
            article.setPubDate(pubDateStr);
            result.add(article);
            keptTitles.add(normalized);
            if (!link.isBlank()) keptLinks.add(link);
        }
        log.debug("중복 기사 {}건 제외 ({}): 최종 {}건", duplicates, stockName, result.size());
        return result;
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
        String t = title == null ? "" : title;
        String prev;
        do {
            prev = t;
            t = LEADING_TAG.matcher(t).replaceFirst("");
            t = TRAILING_TAG.matcher(t).replaceFirst("");
        } while (!t.equals(prev));
        t = NON_WORD.matcher(t.toLowerCase(Locale.ROOT)).replaceAll("");
        String name = stockName == null ? ""
                : NON_WORD.matcher(stockName.toLowerCase(Locale.ROOT)).replaceAll("");
        if (!name.isEmpty()) t = t.replace(name, "");
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