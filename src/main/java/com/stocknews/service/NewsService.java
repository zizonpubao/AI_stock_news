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

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
// 상단 import 추가
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;
import javax.net.ssl.SSLException;


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

    @Transactional
    public List<NewsArticle> fetchAndSaveNews(Stock stock) {
        log.info("뉴스 수집 시작: {}", stock.getName());
        newsRepository.deleteByStock(stock);

        List<NewsArticle> articles = new ArrayList<>();

        try {
            String exactQuery = "\"" + stock.getName() + "\"";  // 1차: 정확히 일치 검색

            HttpClient httpClient = HttpClient.create()
                    .secure(sslSpec -> {
                        try {
                            sslSpec.sslContext(
                                    SslContextBuilder.forClient()
                                            .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                            .build()
                            );
                        } catch (SSLException e) {
                            throw new RuntimeException(e);
                        }
                    });

            WebClient client = WebClient.builder()
                    .baseUrl(newsUrl)
                    .clientConnector(new ReactorClientHttpConnector(httpClient))
                    .defaultHeader("X-Naver-Client-Id", clientId)
                    .defaultHeader("X-Naver-Client-Secret", clientSecret)
                    .defaultHeader("Referer", "https://web-production-fffaa.up.railway.app")
                    .defaultHeader("Origin", "https://web-production-fffaa.up.railway.app")
                    .build();

            String response = client.get()
                    .uri(uriBuilder -> uriBuilder
                            .queryParam("query", exactQuery)
                            .queryParam("display", 20)
                            .queryParam("sort", "sim")
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(response);
            JsonNode items = root.get("items");
            ZonedDateTime cutoff = ZonedDateTime.now().minusHours(48);
            int savedCount = 0;

            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    if (savedCount >= 5) break;

                    // 48시간 필터
                    String pubDateStr = item.get("pubDate").asText();
                    if (!isWithin48Hours(pubDateStr, cutoff)) {
                        log.debug("48시간 초과 제외: {}", pubDateStr);
                        continue;
                    }

                    // 2차: 제목에 종목명 포함 여부 확인
                    String title = cleanHtml(item.get("title").asText());
                    if (!title.contains(stock.getName())) {
                        log.debug("종목명 미포함 제외: {}", title);
                        continue;
                    }

                    NewsArticle article = new NewsArticle();
                    article.setStock(stock);
                    article.setTitle(title);
                    article.setDescription(cleanHtml(item.get("description").asText()));
                    article.setLink(item.get("link").asText());
                    article.setPubDate(pubDateStr);

                    newsRepository.save(article);
                    articles.add(article);
                    savedCount++;
                }
            }

            log.info("뉴스 {}건 저장: {}", articles.size(), stock.getName());

        } catch (Exception e) {
            log.error("뉴스 수집 실패 ({}): {}", stock.getName(), e.getMessage());
        }

        return articles;  // 조건 불만족 시 빈 리스트 반환
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