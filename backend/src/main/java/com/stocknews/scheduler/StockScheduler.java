package com.stocknews.scheduler;

import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.PipelineRun;
import com.stocknews.entity.NewsStatus;
import com.stocknews.entity.Stock;
import com.stocknews.repository.PipelineRunRepository;
import com.stocknews.repository.StockRepository;
import com.stocknews.service.ArchiveService;
import com.stocknews.service.ArticleBodyFetcher;
import com.stocknews.service.GeminiAnalysisService;
import com.stocknews.service.MarketPriceService;
import com.stocknews.service.NewsService;
import com.stocknews.service.StockCrawlerService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final StockRepository stockRepository;
    private final PipelineRunRepository pipelineRunRepository;

    // 업데이트 사이클 중복 실행 방지 플래그
    private final AtomicBoolean running = new AtomicBoolean(false);

    // 가장 최근 수집 시도의 시각과 성공 여부 (서버 시작 후 첫 시도 전에는 null)
    @Getter private volatile LocalDateTime lastAttemptAt;
    @Getter private volatile Boolean lastAttemptOk;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("서버 시작 - 초기 데이터 수집 시작");
        updateAllStocks(PipelineRun.TRIGGER_STARTUP);
    }

    // zone 지정: EC2(UTC) 서버에서도 한국시간(KST) 기준 8~20시(프리장~애프터마켓)에 실행되도록
    @Scheduled(cron = "${scheduler.stock-update.cron}", zone = "Asia/Seoul")
    public void scheduledUpdate() {
        // 주말/공휴일/임시휴장 자동 감지 → 휴장일이면 스킵 (마지막 거래일 데이터 유지)
        if (!marketPriceService.isTradingToday()) {
            log.info("휴장일로 판단 - 업데이트 스킵");
            return;
        }
        log.info("스케줄러 실행 - 검색 상위 종목 업데이트");
        updateAllStocks(PipelineRun.TRIGGER_SCHEDULED);
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

    /**
     * 수집 → 뉴스 → AI 분석 한 사이클을 실행한다.
     * 스케줄러·서버 시작·수동 갱신이 겹쳐도 동시에 두 번 돌지 않도록 막는다.
     * (동시에 돌면 종목 테이블을 서로 지우고, AI 호출도 중복으로 나간다.)
     */
    public void updateAllStocks() {
        updateAllStocks(PipelineRun.TRIGGER_SCHEDULED);
    }

    private void updateAllStocks(String trigger) {
        if (!running.compareAndSet(false, true)) {
            log.warn("이미 업데이트가 진행 중 - 이번 실행은 건너뜀");
            return;
        }
        try {
            runUpdateCycle(trigger);
        } finally {
            running.set(false);
        }
    }

    /** 실행 중인지 (모델 비교 실험이 외부 호출 겹침을 로그로 남기는 데 쓴다) */
    public boolean isUpdateRunning() {
        return running.get();
    }

    /** 수동 갱신용: 별도 스레드에서 한 사이클 실행. 이미 진행 중이면 시작하지 않고 false. */
    public boolean startUpdateAsync() {
        if (!running.compareAndSet(false, true)) return false;
        Thread worker = new Thread(() -> {
            try {
                runUpdateCycle(PipelineRun.TRIGGER_MANUAL);
            } finally {
                running.set(false);
            }
        }, "manual-refresh");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    /** 상태 기록 실패가 사이클 전체(AI 분석 등)를 막지 않도록 여기서 삼키고 로그만 남긴다. */
    private void recordNewsStatus(Stock stock, NewsStatus status) {
        try {
            // save(merge) 대신 UPDATE 쿼리: detached Stock 의 orphanRemoval 로 방금 저장한 뉴스가 지워지는 것을 막는다
            stockRepository.updateNewsStatus(stock.getId(), status);
        } catch (Exception e) {
            log.error("뉴스 상태 기록 실패 ({}): {}", stock.getName(), e.getMessage());
        }
    }

    private void runUpdateCycle(String trigger) {
        long startNanos = System.nanoTime();
        PipelineRun run = new PipelineRun();
        run.setTrigger(trigger);
        run.setStartedAt(LocalDateTime.now());
        run.setCollectOk(false);
        String outcome = PipelineRun.OUTCOME_ERROR; // 아래에서 단계별로 확정. 예외로 빠져나가면 ERROR 로 남는다.
        lastAttemptAt = run.getStartedAt();
        boolean collected = false;
        try {
            // 1단계: 검색 상위 TOP 10 크롤링
            List<Stock> stocks = stockCrawlerService.crawlAndSaveTop10();
            if (stocks.isEmpty()) {
                // 수집 실패 시 임의 데이터를 만들지 않는다. 직전 수집 결과를 그대로 유지하고,
                // 실패했다는 사실은 /api/health 와 화면 안내로 드러낸다.
                lastAttemptOk = false;
                outcome = PipelineRun.OUTCOME_COLLECT_FAILED;
                log.error("종목 수집 실패 - 직전 데이터 유지, 이번 업데이트 중단");
                return;
            }
            collected = true;
            lastAttemptOk = true;
            run.setCollectOk(true);
            run.setStockCount(stocks.size());

            // 2단계: 각 종목 뉴스 수집 (개별 수집)
            Map<Long, List<NewsArticle>> newsMap = new HashMap<>();
            int newsSuccess = 0, newsFailed = 0, newsArticles = 0;
            for (Stock stock : stocks) {
                NewsStatus status;
                try {
                    NewsService.NewsFetchResult result = newsService.fetchAndSaveNews(stock);
                    newsMap.put(stock.getId(), result.articles());
                    newsArticles += result.articles().size();
                    // 호출이 성공했으면 기사가 0건이어도 SUCCESS (뉴스 없음), 실패해야만 FAILED
                    status = result.success() ? NewsStatus.SUCCESS : NewsStatus.FAILED;
                    log.debug("뉴스 수집 완료: {} → {}건 ({})", stock.getName(), result.articles().size(), status);
                } catch (Exception e) {
                    log.error("뉴스 수집 실패 ({}): {}", stock.getName(), e.getMessage());
                    newsMap.put(stock.getId(), List.of());
                    status = NewsStatus.FAILED;
                }
                if (status == NewsStatus.SUCCESS) newsSuccess++; else newsFailed++;
                run.setNewsSuccess(newsSuccess);
                run.setNewsFailed(newsFailed);
                run.setNewsArticles(newsArticles);
                recordNewsStatus(stock, status);
            }

            applyBodyCounts(run, newsMap);

            // 3단계: Gemini AI 분석 → 10개 종목 한 번에 요청 (API 1회 호출)
            GeminiAnalysisService.AnalysisSummary summary = geminiAnalysisService.analyzeAllStocks(stocks, newsMap);
            if (summary != null) log.info("AI 분석 요약: {}", summary);
            outcome = applyAnalysis(run, summary);
            // 종목은 수집했지만 뉴스 호출이 전부 실패한 사이클은 "정상"으로 기록하지 않는다
            // (뉴스가 없으면 AI 도 요청하지 않으므로, 이 검사가 없으면 OK 로 남는다)
            if (newsFailed > 0 && newsSuccess == 0) {
                outcome = PipelineRun.OUTCOME_NEWS_FAILED;
            }

            log.info("전체 업데이트 완료 - {}개 종목", stocks.size());

        } catch (Exception e) {
            // 수집 단계에서 예외가 난 경우만 수집 실패로 기록한다 (뉴스·AI 단계 실패는 종목별 상태로 남는다)
            if (!collected) lastAttemptOk = false;
            outcome = PipelineRun.OUTCOME_ERROR;
            // 예외 메시지는 로그에만 남기고 DB 에는 넣지 않는다
            log.error("업데이트 중 오류 발생: {}", e.getMessage());
        } finally {
            saveRun(run, outcome, startNanos);
        }
    }

    /** 기사별 본문 수집 상태를 세어 실행 기록에 옮긴다. 기능이 꺼져 있으면(DISABLED) 세 값 모두 0 이다. */
    static void applyBodyCounts(PipelineRun run, Map<Long, List<NewsArticle>> newsMap) {
        int used = 0, robotsExcluded = 0, failed = 0;
        for (List<NewsArticle> articles : newsMap.values()) {
            if (articles == null) continue;
            for (NewsArticle a : articles) {
                String st = a.getBodyStatus();
                if (st == null) continue;
                switch (st) {
                    case ArticleBodyFetcher.BODY -> used++;
                    case ArticleBodyFetcher.ROBOTS_DISALLOWED, ArticleBodyFetcher.AI_DISALLOWED,
                         ArticleBodyFetcher.ROBOTS_UNKNOWN -> robotsExcluded++;
                    case ArticleBodyFetcher.FETCH_FAILED, ArticleBodyFetcher.EXTRACT_FAILED,
                         ArticleBodyFetcher.BUDGET_EXCEEDED, ArticleBodyFetcher.INVALID_URL -> failed++;
                    default -> { }
                }
            }
        }
        run.setBodiesUsed(used);
        run.setBodiesRobotsExcluded(robotsExcluded);
        run.setBodiesFailed(failed);
        log.info("본문 수집 결과 - 사용 {}, robots 로 제외 {}, 실패 {}", used, robotsExcluded, failed);
    }

    /** AI 분석 요약을 실행 기록에 옮기고 사이클의 결론 코드를 돌려준다. */
    private static String applyAnalysis(PipelineRun run, GeminiAnalysisService.AnalysisSummary summary) {
        if (summary == null) return PipelineRun.OUTCOME_OK;
        run.setAnalysisModel(summary.model());
        run.setAnalysisRequested(summary.requested());
        run.setAnalysisSuccess(summary.success());
        run.setAnalysisFailed(summary.failed());
        run.setAnalysisSkipped(summary.skipped());
        run.setPromptTokens(summary.promptTokens());
        run.setOutputTokens(summary.outputTokens());
        run.setAnalysisElapsedMs(summary.elapsedMs());
        run.setNumbersTotal(summary.numbersTotal());
        run.setNumbersUnverified(summary.numbersUnverified());
        run.setNumbersNotInAnyArticle(summary.numbersNotInAnyArticle());
        run.setNumbersUnverifiedDetail(summary.numbersUnverifiedDetail());
        if (summary.requested() > 0 && summary.success() == 0) return PipelineRun.OUTCOME_AI_CALL_FAILED;
        if (summary.failed() > 0) return PipelineRun.OUTCOME_PARTIAL;
        return PipelineRun.OUTCOME_OK;
    }

    /** 기록 저장이 실패해도 사이클(과 호출한 스레드)을 깨뜨리지 않는다. */
    private void saveRun(PipelineRun run, String outcome, long startNanos) {
        try {
            run.setOutcome(outcome);
            run.setFinishedAt(LocalDateTime.now());
            run.setTotalElapsedMs((System.nanoTime() - startNanos) / 1_000_000);
            pipelineRunRepository.save(run);
            log.info("실행 기록 저장 - {} / {} / {}ms", run.getTrigger(), outcome, run.getTotalElapsedMs());
        } catch (Exception e) {
            log.error("실행 기록 저장 실패: {}", e.getMessage());
        }
    }
}
