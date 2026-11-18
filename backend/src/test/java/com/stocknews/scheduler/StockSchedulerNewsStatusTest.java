package com.stocknews.scheduler;

import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.NewsStatus;
import com.stocknews.entity.Stock;
import com.stocknews.repository.StockRepository;
import com.stocknews.service.ArchiveService;
import com.stocknews.service.GeminiAnalysisService;
import com.stocknews.service.MarketPriceService;
import com.stocknews.service.NewsService;
import com.stocknews.service.NewsService.NewsFetchResult;
import com.stocknews.service.StockCrawlerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 뉴스 수집 결과가 종목별 newsStatus 로 기록되는지 Mockito 로 검증한다 (외부 호출 없음). */
class StockSchedulerNewsStatusTest {

    StockCrawlerService crawler = mock(StockCrawlerService.class);
    NewsService newsService = mock(NewsService.class);
    GeminiAnalysisService gemini = mock(GeminiAnalysisService.class);
    StockRepository stockRepository = mock(StockRepository.class);
    StockScheduler scheduler;

    Stock stock = stock(1L, "가나전자");

    @BeforeEach
    void setUp() {
        scheduler = new StockScheduler(crawler, newsService, gemini, mock(ArchiveService.class),
                mock(MarketPriceService.class), stockRepository);
        when(crawler.crawlAndSaveTop10()).thenReturn(List.of(stock));
    }

    private static Stock stock(Long id, String name) {
        Stock s = new Stock();
        s.setId(id);
        s.setName(name);
        s.setCode("00000" + id);
        return s;
    }

    @Test
    @DisplayName("뉴스 호출이 실패하면 FAILED 로 기록하고 AI 분석에는 빈 목록을 넘긴다")
    void failedFetchIsRecordedAsFailed() {
        when(newsService.fetchAndSaveNews(stock)).thenReturn(new NewsFetchResult(List.of(), false));

        scheduler.updateAllStocks();

        verify(stockRepository).updateNewsStatus(1L, NewsStatus.FAILED);
        verify(gemini).analyzeAllStocks(any(), eq(Map.of(1L, List.<NewsArticle>of())));
    }

    @Test
    @DisplayName("호출이 성공하면 기사가 0건이어도 SUCCESS 로 기록한다")
    void successWithZeroArticlesIsSuccess() {
        when(newsService.fetchAndSaveNews(stock)).thenReturn(new NewsFetchResult(List.of(), true));

        scheduler.updateAllStocks();

        verify(stockRepository).updateNewsStatus(1L, NewsStatus.SUCCESS);
    }

    @Test
    @DisplayName("기사를 가져오면 SUCCESS 로 기록하고 기사 목록을 AI 분석에 넘긴다")
    void successWithArticlesPassesNewsToAnalysis() {
        NewsArticle article = new NewsArticle();
        article.setTitle("가나전자 소식");
        when(newsService.fetchAndSaveNews(stock)).thenReturn(new NewsFetchResult(List.of(article), true));

        scheduler.updateAllStocks();

        verify(stockRepository).updateNewsStatus(1L, NewsStatus.SUCCESS);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Long, List<NewsArticle>>> captor = ArgumentCaptor.forClass(Map.class);
        verify(gemini).analyzeAllStocks(any(), captor.capture());
        assertThat(captor.getValue().get(1L)).containsExactly(article);
    }

    @Test
    @DisplayName("뉴스 서비스가 예외를 던져도 FAILED 로 기록하고 사이클은 계속된다")
    void exceptionIsRecordedAsFailed() {
        when(newsService.fetchAndSaveNews(stock)).thenThrow(new RuntimeException("boom"));

        scheduler.updateAllStocks();

        verify(stockRepository).updateNewsStatus(1L, NewsStatus.FAILED);
        verify(gemini).analyzeAllStocks(any(), any());
    }
}
