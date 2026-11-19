package com.stocknews.scheduler;

import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.PipelineRun;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 내보내지 않은 이슈 문장 수가 실행 기록(PipelineRun)에 저장되는지 검증한다. */
class IssuesDroppedRecordTest {

    StockCrawlerService crawler = mock(StockCrawlerService.class);
    NewsService newsService = mock(NewsService.class);
    GeminiAnalysisService gemini = mock(GeminiAnalysisService.class);
    PipelineRunRepository runRepository = mock(PipelineRunRepository.class);

    private PipelineRun run(AnalysisSummary summary) {
        Stock s = new Stock();
        s.setId(1L);
        s.setName("가나전자");
        s.setCode("000001");
        NewsArticle a = new NewsArticle();
        a.setTitle("소식");
        when(crawler.crawlAndSaveTop10()).thenReturn(List.of(s));
        when(newsService.fetchAndSaveNews(s)).thenReturn(new NewsFetchResult(List.of(a), true));
        when(gemini.analyzeAllStocks(any(), any())).thenReturn(summary);
        new StockScheduler(crawler, newsService, gemini, mock(ArchiveService.class),
                mock(MarketPriceService.class), mock(StockRepository.class), runRepository).updateAllStocks();
        ArgumentCaptor<PipelineRun> captor = ArgumentCaptor.forClass(PipelineRun.class);
        verify(runRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("issuesDropped 는 issuesDroppedUnverified 로 저장된다")
    void storesIssuesDropped() {
        PipelineRun r = run(new AnalysisSummary("claude-haiku-5-5", 1, 1, 0, 0, 1000, 500, 20000L, 14, 3, 1, "[]", 2));
        assertThat(r.getIssuesDroppedUnverified()).isEqualTo(2);
        assertThat(r.getNumbersUnverified()).isEqualTo(3);
    }

    @Test
    @DisplayName("측정하지 않았거나 가드가 꺼져 있으면 null 로 저장된다")
    void nullWhenNotMeasured() {
        PipelineRun r = run(new AnalysisSummary("claude-haiku-5-5", 1, 1, 0, 0, 1000, 500, 20000L, 14, 3));
        assertThat(r.getIssuesDroppedUnverified()).isNull();
    }
}
