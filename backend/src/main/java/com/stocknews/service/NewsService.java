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
import java.util.List;
import java.util.Locale;

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
                            .queryParam("display", 20)
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

            NewsArticle article = new NewsArticle();
            article.setTitle(title);
            article.setDescription(cleanHtml(item.path("description").asText("")));
            article.setLink(item.path("link").asText(""));
            article.setPubDate(pubDateStr);
            result.add(article);
        }
        return result;
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