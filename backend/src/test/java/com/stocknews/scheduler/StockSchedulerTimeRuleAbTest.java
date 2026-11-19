package com.stocknews.scheduler;

import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.Stock;
import com.stocknews.repository.PipelineRunRepository;
import com.stocknews.repository.StockRepository;
import com.stocknews.service.ArchiveService;
import com.stocknews.service.GeminiAnalysisService;
import com.stocknews.service.GeminiAnalysisService.AnalysisSummary;
import com.stocknews.service.MarketPriceService;
import com.stocknews.service.NewsService;
import com.stocknews.service.NewsService.NewsFetchResult;
import com.stocknews.service.StockCrawlerService;
import com.stocknews.service.TimeRuleAbResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StockSchedulerTimeRuleAbTest {

    StockCrawlerService crawler = mock(StockCrawlerService.class);
    NewsService newsService = mock(NewsService.class);
    GeminiAnalysisService gemini = mock(GeminiAnalysisService.class);
    PipelineRunRepository runRepository = mock(PipelineRunRepository.class);
    StockScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new StockScheduler(crawler, newsService, gemini, mock(ArchiveService.class),
                mock(MarketPriceService.class), mock(StockRepository.class), runRepository);
    }

    private static Stock stock() {
        Stock s = new Stock();
        s.setId(1L);
        s.setName("가나전자");
        s.setCode("000001");
        return s;
    }

    private boolean running() {
        return ((AtomicBoolean) ReflectionTestUtils.getField(scheduler, "running")).get();
    }

    @Test
    @DisplayName("운영 사이클을 돌린 뒤 같은 메모리의 뉴스와 옵션으로 비교하고, 끝나면 진행 표시를 푼다")
    @SuppressWarnings("unchecked")
    void runsNormalCycleThenCompareWithSameArticles() {
        Stock st = stock();
        NewsArticle article = new NewsArticle();
        article.setTitle("소식");
        article.setBody("본문");
        when(crawler.crawlAndSaveTop10()).thenReturn(List.of(st));
        when(newsService.fetchAndSaveNews(st)).thenReturn(new NewsFetchResult(List.of(article), true));
        when(gemini.analyzeAllStocks(any(), any()))
                .thenReturn(new AnalysisSummary("claude-haiku-5-5", 1, 1, 0, 0, 1000, 500, 100L));
        TimeRuleAbResult expected = TimeRuleAbResult.notRun(LocalDateTime.now(), 3,
                new TimeRuleAbResult.Input(1, 1, 1, 10, 10), "x");
        when(gemini.compareTimeRule(any(), any(), anyInt(), anyBoolean())).thenReturn(expected);

        Optional<TimeRuleAbResult> out = scheduler.runTimeRuleAb(3, true);

        assertThat(out).containsSame(expected);
        verify(gemini).analyzeAllStocks(any(), any());
        ArgumentCaptor<Map<Long, List<NewsArticle>>> news = ArgumentCaptor.forClass(Map.class);
        verify(gemini).compareTimeRule(any(), news.capture(), eq(3), eq(true));
        assertThat(news.getValue().get(1L).get(0)).isSameAs(article);
        assertThat(running()).isFalse();
    }

    @Test
    @DisplayName("수집이 실패해 분석 단계에 못 가면 비교용 모델 호출을 하지 않고 사유를 돌려준다")
    void collectionFailureSkipsExperiment() {
        when(crawler.crawlAndSaveTop10()).thenReturn(List.of());

        Optional<TimeRuleAbResult> out = scheduler.runTimeRuleAb(3, false);

        assertThat(out).isPresent();
        assertThat(out.get().message()).isNotBlank();
        assertThat(out.get().repetitions()).isEmpty();
        verify(gemini, never()).compareTimeRule(any(), any(), anyInt(), anyBoolean());
        assertThat(running()).isFalse();
    }

    @Test
    @DisplayName("다른 사이클이 진행 중이면 아무것도 하지 않고 빈 값을 돌려준다")
    void busyReturnsEmpty() {
        ((AtomicBoolean) ReflectionTestUtils.getField(scheduler, "running")).set(true);

        assertThat(scheduler.runTimeRuleAb(3, false)).isEmpty();

        verifyNoInteractions(crawler, newsService, gemini, runRepository);
        assertThat(running()).isTrue();
    }
}
