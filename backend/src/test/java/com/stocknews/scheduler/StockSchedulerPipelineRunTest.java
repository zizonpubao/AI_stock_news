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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 사이클 결과가 PipelineRun 으로 저장되는지 검증한다 (외부 호출 없음). */
class StockSchedulerPipelineRunTest {

    StockCrawlerService crawler = mock(StockCrawlerService.class);
    NewsService newsService = mock(NewsService.class);
    GeminiAnalysisService gemini = mock(GeminiAnalysisService.class);
    PipelineRunRepository runRepository = mock(PipelineRunRepository.class);
    StockScheduler scheduler;

    Stock stock1 = stock(1L, "가나전자");
    Stock stock2 = stock(2L, "다라화학");

    @BeforeEach
    void setUp() {
        scheduler = new StockScheduler(crawler, newsService, gemini, mock(ArchiveService.class),
                mock(MarketPriceService.class), mock(StockRepository.class), runRepository);
    }

    private static Stock stock(Long id, String name) {
        Stock s = new Stock();
        s.setId(id);
        s.setName(name);
        s.setCode("00000" + id);
        return s;
    }

    private static NewsArticle article() {
        NewsArticle a = new NewsArticle();
        a.setTitle("소식");
        return a;
    }

    private void stubNewsOk() {
        when(newsService.fetchAndSaveNews(stock1)).thenReturn(new NewsFetchResult(List.of(article(), article()), true));
        when(newsService.fetchAndSaveNews(stock2)).thenReturn(new NewsFetchResult(List.of(article()), true));
    }

    private PipelineRun savedRun() {
        ArgumentCaptor<PipelineRun> captor = ArgumentCaptor.forClass(PipelineRun.class);
        verify(runRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("정상 사이클은 수집·뉴스·AI 결과와 함께 OK 로 저장된다")
    void normalCycleIsRecordedAsOk() {
        when(crawler.crawlAndSaveTop10()).thenReturn(List.of(stock1, stock2));
        stubNewsOk();
        when(gemini.analyzeAllStocks(any(), any()))
                .thenReturn(new AnalysisSummary("claude-haiku-5-5", 2, 2, 0, 0, 1000, 500, 21500L, 14, 3));

        scheduler.updateAllStocks();

        PipelineRun run = savedRun();
        assertThat(run.getNumbersTotal()).isEqualTo(14);
        assertThat(run.getNumbersUnverified()).isEqualTo(3);
        assertThat(run.getOutcome()).isEqualTo("OK");
        assertThat(run.getTrigger()).isEqualTo("SCHEDULED");
        assertThat(run.getCollectOk()).isTrue();
        assertThat(run.getStockCount()).isEqualTo(2);
        assertThat(run.getNewsSuccess()).isEqualTo(2);
        assertThat(run.getNewsFailed()).isZero();
        assertThat(run.getNewsArticles()).isEqualTo(3);
        assertThat(run.getAnalysisModel()).isEqualTo("claude-haiku-5-5");
        assertThat(run.getAnalysisRequested()).isEqualTo(2);
        assertThat(run.getAnalysisSuccess()).isEqualTo(2);
        assertThat(run.getAnalysisFailed()).isZero();
        assertThat(run.getAnalysisSkipped()).isZero();
        assertThat(run.getPromptTokens()).isEqualTo(1000);
        assertThat(run.getOutputTokens()).isEqualTo(500);
        assertThat(run.getAnalysisElapsedMs()).isEqualTo(21500L);
        assertThat(run.getStartedAt()).isNotNull();
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(run.getTotalElapsedMs()).isNotNull().isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("수치 근거의 미포착 개수와 상세 JSON 이 실행 기록에 저장된다")
    void numbersDetailIsRecorded() {
        when(crawler.crawlAndSaveTop10()).thenReturn(List.of(stock1, stock2));
        stubNewsOk();
        String detail = "[{\"stock\":\"A\",\"number\":\"7\",\"category\":\"NOT_FOUND\",\"sources\":[1],\"issue\":\"x\"}]";
        when(gemini.analyzeAllStocks(any(), any()))
                .thenReturn(new AnalysisSummary("claude-haiku-5-5", 2, 2, 0, 0, 1000, 500, 21500L, 14, 3, 1, detail));

        scheduler.updateAllStocks();

        PipelineRun run = savedRun();
        assertThat(run.getNumbersUnverified()).isEqualTo(3);
        assertThat(run.getNumbersNotInAnyArticle()).isEqualTo(1);
        assertThat(run.getNumbersUnverifiedDetail()).isEqualTo(detail);
    }

    @Test
    @DisplayName("종목 수집이 실패하면 COLLECT_FAILED 로 저장되고 뉴스·AI 는 호출되지 않는다")
    void collectFailureIsRecorded() {
        when(crawler.crawlAndSaveTop10()).thenReturn(List.of());

        scheduler.updateAllStocks();

        PipelineRun run = savedRun();
        assertThat(run.getOutcome()).isEqualTo("COLLECT_FAILED");
        assertThat(run.getCollectOk()).isFalse();
        assertThat(run.getAnalysisRequested()).isNull();
        verify(gemini, never()).analyzeAllStocks(any(), any());
    }

    @Test
    @DisplayName("AI 를 요청했는데 성공이 0건이면 AI_CALL_FAILED 로 저장된다")
    void allAiFailureIsRecorded() {
        when(crawler.crawlAndSaveTop10()).thenReturn(List.of(stock1, stock2));
        stubNewsOk();
        when(gemini.analyzeAllStocks(any(), any()))
                .thenReturn(new AnalysisSummary(null, 2, 0, 2, 0, null, null, 95000L));

        scheduler.updateAllStocks();

        PipelineRun run = savedRun();
        assertThat(run.getOutcome()).isEqualTo("AI_CALL_FAILED");
        assertThat(run.getCollectOk()).isTrue();
        assertThat(run.getAnalysisModel()).isNull();
        assertThat(run.getAnalysisRequested()).isEqualTo(2);
        assertThat(run.getAnalysisFailed()).isEqualTo(2);
        assertThat(run.getAnalysisElapsedMs()).isEqualTo(95000L);
    }

    @Test
    @DisplayName("일부 종목만 성공하면 PARTIAL 로 저장된다")
    void partialSuccessIsRecorded() {
        when(crawler.crawlAndSaveTop10()).thenReturn(List.of(stock1, stock2));
        stubNewsOk();
        when(gemini.analyzeAllStocks(any(), any()))
                .thenReturn(new AnalysisSummary("gemini-2.5-flash", 2, 1, 1, 0, 1000, 400, 20000L));

        scheduler.updateAllStocks();

        assertThat(savedRun().getOutcome()).isEqualTo("PARTIAL");
    }

    @Test
    @DisplayName("뉴스가 없어 건너뛴 종목은 실패로 세지 않고 건너뜀으로 기록된다")
    void skippedStocksAreNotFailures() {
        when(crawler.crawlAndSaveTop10()).thenReturn(List.of(stock1, stock2));
        stubNewsOk();
        when(gemini.analyzeAllStocks(any(), any()))
                .thenReturn(new AnalysisSummary("gemini-2.5-flash", 1, 1, 0, 1, 800, 300, 18000L));

        scheduler.updateAllStocks();

        PipelineRun run = savedRun();
        assertThat(run.getOutcome()).isEqualTo("OK");
        assertThat(run.getAnalysisSkipped()).isEqualTo(1);
        assertThat(run.getAnalysisFailed()).isZero();
    }

    @Test
    @DisplayName("수집 중 예외가 나도 ERROR 로 저장되고 예외는 밖으로 나가지 않는다")
    void exceptionIsRecordedAsError() {
        when(crawler.crawlAndSaveTop10()).thenThrow(new RuntimeException("secret-internal-detail"));

        assertThatCode(() -> scheduler.updateAllStocks()).doesNotThrowAnyException();

        PipelineRun run = savedRun();
        assertThat(run.getOutcome()).isEqualTo("ERROR");
        assertThat(run.getCollectOk()).isFalse();
        // 예외 메시지는 DB 에 들어가는 어떤 필드에도 없다
        assertThat(run.toString()).doesNotContain("secret-internal-detail");
    }

    @Test
    @DisplayName("AI 분석 단계에서 예외가 나면 수집은 성공으로 두고 ERROR 로 저장된다")
    void exceptionAfterCollectKeepsCollectOk() {
        when(crawler.crawlAndSaveTop10()).thenReturn(List.of(stock1, stock2));
        stubNewsOk();
        when(gemini.analyzeAllStocks(any(), any())).thenThrow(new IllegalStateException("boom"));

        scheduler.updateAllStocks();

        PipelineRun run = savedRun();
        assertThat(run.getOutcome()).isEqualTo("ERROR");
        assertThat(run.getCollectOk()).isTrue();
        assertThat(run.getNewsArticles()).isEqualTo(3);
    }

    @Test
    @DisplayName("뉴스 호출이 실패한 종목은 newsFailed 로 센다")
    void newsFailuresAreCounted() {
        when(crawler.crawlAndSaveTop10()).thenReturn(List.of(stock1, stock2));
        when(newsService.fetchAndSaveNews(stock1)).thenReturn(new NewsFetchResult(List.of(), false));
        when(newsService.fetchAndSaveNews(stock2)).thenThrow(new RuntimeException("boom"));

        scheduler.updateAllStocks();

        PipelineRun run = savedRun();
        assertThat(run.getNewsSuccess()).isZero();
        assertThat(run.getNewsFailed()).isEqualTo(2);
        assertThat(run.getNewsArticles()).isZero();
    }

    @Test
    @DisplayName("기록 저장이 실패해도 사이클은 예외 없이 끝난다")
    void saveFailureDoesNotBreakCycle() {
        when(crawler.crawlAndSaveTop10()).thenReturn(List.of());
        when(runRepository.save(any())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> scheduler.updateAllStocks()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("서버 시작으로 돌면 트리거가 STARTUP 으로 기록된다")
    void startupTrigger() {
        when(crawler.crawlAndSaveTop10()).thenReturn(List.of());

        scheduler.onApplicationReady();

        assertThat(savedRun().getTrigger()).isEqualTo("STARTUP");
    }

    @Test
    @DisplayName("수동 갱신으로 돌면 트리거가 MANUAL 로 기록된다")
    void manualTrigger() throws Exception {
        when(crawler.crawlAndSaveTop10()).thenReturn(List.of());

        assertThat(scheduler.startUpdateAsync()).isTrue();

        ArgumentCaptor<PipelineRun> captor = ArgumentCaptor.forClass(PipelineRun.class);
        verify(runRepository, org.mockito.Mockito.timeout(5000)).save(captor.capture());
        assertThat(captor.getValue().getTrigger()).isEqualTo("MANUAL");
    }
}
