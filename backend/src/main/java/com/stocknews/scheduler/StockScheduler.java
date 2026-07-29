package com.stocknews.scheduler;

import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.Stock;
import com.stocknews.service.ArchiveService;
import com.stocknews.service.GeminiAnalysisService;
import com.stocknews.service.MarketPriceService;
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
    private final ArchiveService archiveService;
    private final MarketPriceService marketPriceService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("서버 시작 - 초기 데이터 수집 시작");
        updateAllStocks();
    }

    // zone 지정: EC2(UTC) 서버에서도 한국시간(KST) 기준 8~20시(프리장~애프터마켓)에 실행되도록
    @Scheduled(cron = "${scheduler.stock-update.cron}", zone = "Asia/Seoul")
    public void scheduledUpdate() {
        // 주말/공휴일/임시휴장 자동 감지 → 휴장일이면 스킵 (마지막 거래일 데이터 유지)
        if (!marketPriceService.isTradingToday()) {
            log.info("휴장일로 판단 - 업데이트 스킵");
            return;
        }
        log.info("스케줄러 실행 - 급상승 종목 업데이트");
        updateAllStocks();
    }

    // 매 거래일 종가 후(16:05 KST) 그날의 TOP10 + 뉴스 + AI분석을 날짜별 아카이브로 적재
    @Scheduled(cron = "0 5 16 * * MON-FRI", zone = "Asia/Seoul")
    public void archiveDaily() {
        if (!marketPriceService.isTradingToday()) {
            log.info("휴장일로 판단 - 아카이브 스킵");
            return;
        }
        log.info("스케줄러 실행 - 일일 아카이브 적재");
        try {
            archiveService.archiveToday();
        } catch (Exception e) {
            log.error("일일 아카이브 실패: {}", e.getMessage());
        }
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
