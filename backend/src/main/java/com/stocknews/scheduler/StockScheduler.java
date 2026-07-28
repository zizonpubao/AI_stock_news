package com.stocknews.scheduler;

import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.Stock;
import com.stocknews.service.GeminiAnalysisService;
import com.stocknews.service.NewsService;
import com.stocknews.service.StockCrawlerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class StockScheduler {

    private final StockCrawlerService stockCrawlerService;
    private final NewsService newsService;
    private final GeminiAnalysisService geminiAnalysisService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("서버 시작 - 초기 데이터 수집 시작");
        updateAllStocks();
    }

    // zone 지정: EC2(UTC) 서버에서도 한국시간(KST) 기준 9~20시에 실행되도록
    @Scheduled(cron = "${scheduler.stock-update.cron}", zone = "Asia/Seoul")
    public void scheduledUpdate() {
        log.info("스케줄러 실행 - 급상승 종목 업데이트");
        updateAllStocks();
    }

    public void updateAllStocks() {
        try {
            // 1단계: 급상승 TOP 10 크롤링
            List<Stock> stocks = stockCrawlerService.crawlAndSaveTop10();
            if (stocks.isEmpty()) {
                log.warn("수집된 종목 없음 - 업데이트 중단");
                return;
            }

            // 2단계: 각 종목 뉴스 수집 (개별 수집)
            Map<Long, List<NewsArticle>> newsMap = new HashMap<>();
            for (Stock stock : stocks) {
                try {
                    List<NewsArticle> articles = newsService.fetchAndSaveNews(stock);
                    newsMap.put(stock.getId(), articles);
                    log.debug("뉴스 수집 완료: {} → {}건", stock.getName(), articles.size());
                } catch (Exception e) {
                    log.error("뉴스 수집 실패 ({}): {}", stock.getName(), e.getMessage());
                    newsMap.put(stock.getId(), List.of());
                }
            }

            // 3단계: Gemini AI 분석 → 10개 종목 한 번에 요청 (API 1회 호출)
            geminiAnalysisService.analyzeAllStocks(stocks, newsMap);

            log.info("전체 업데이트 완료 - {}개 종목", stocks.size());

        } catch (Exception e) {
            log.error("업데이트 중 오류 발생: {}", e.getMessage());
        }
    }
}
