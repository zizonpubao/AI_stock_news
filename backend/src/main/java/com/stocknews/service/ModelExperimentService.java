package com.stocknews.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.Stock;
import com.stocknews.repository.NewsRepository;
import com.stocknews.repository.StockRepository;
import com.stocknews.scheduler.StockScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 같은 입력(현재 DB 의 종목·뉴스)을 여러 모델에 보내 결과를 비교하는 관리자용 실험.
 *
 * 운영과 똑같은 프롬프트·요청 본문·검증 로직을 GeminiAnalysisService 에서 그대로 가져다 쓰고,
 * 결과는 DB 에 저장하지 않는다 (이 클래스는 저장소에 읽기 호출만 한다).
 * 모델마다 정확히 한 번씩 순서대로 호출한다 (폴백 없음).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelExperimentService {

    public static final int MAX_MODELS = 5;
    /** URL 경로에 들어가므로 영문·숫자·점·하이픈만 허용. 첫 글자는 영문·숫자 (".." 경로 조작 방지). */
    private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]{0,63}");

    private final StockRepository stockRepository;
    private final NewsRepository newsRepository;
    private final GeminiAnalysisService geminiAnalysisService;
    private final ObjectMapper objectMapper;
    private final StockScheduler stockScheduler;

    /** 실험이 동시에 두 번 돌면 유료 호출이 겹치므로 막는다 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 모델 이름 검증이 실패했을 때 던지는 예외. 메시지에 입력값을 싣지 않는다. */
    public static class InvalidModelException extends RuntimeException {
        public InvalidModelException(String message) { super(message); }
    }

    /** 이미 실험이 진행 중일 때 */
    public static class ExperimentBusyException extends RuntimeException {
        public ExperimentBusyException() { super("이미 모델 비교 실험이 진행 중입니다."); }
    }

    public record ModelResult(
            String model, boolean ok, String error,
            long elapsedMs, Integer promptTokens, Integer outputTokens, Integer thoughtsTokens,
            int stocksRequested, int stocksReturned, int stocksValid,
            int issuesTotal, int issuesWithValidSources, int invalidSourceRefs, int issuesDroppedNoSource,
            Double avgIssueChars) {}

    public record ExperimentResult(LocalDateTime ranAt, int stocksWithNews, int newsArticles,
                                   String message, List<ModelResult> results) {}

    /**
     * 요청 모델 목록을 검증해 돌려준다. 비어 있으면(null 포함) 설정된 모델 목록을 쓴다.
     * 중복은 한 번만 호출한다.
     */
    public List<String> resolveModels(List<String> requested) {
        List<String> source = (requested == null || requested.isEmpty())
                ? geminiAnalysisService.configuredModels() : requested;
        LinkedHashSet<String> models = new LinkedHashSet<>();
        for (int i = 0; i < source.size(); i++) {
            String m = source.get(i) == null ? "" : source.get(i).trim();
            if (!MODEL_NAME.matcher(m).matches() || m.contains("..")) {
                throw new InvalidModelException("모델 이름은 영문·숫자·'.'·'-' 만 사용할 수 있습니다 (" + (i + 1) + "번째 항목).");
            }
            models.add(m);
        }
        if (models.isEmpty()) throw new InvalidModelException("비교할 모델이 없습니다.");
        if (models.size() > MAX_MODELS) {
            throw new InvalidModelException("모델은 최대 " + MAX_MODELS + "개까지 비교할 수 있습니다.");
        }
        return new ArrayList<>(models);
    }

    /** 검증된 모델 이름 목록으로 실험을 실행한다 (resolveModels 를 먼저 거칠 것). */
    public ExperimentResult run(List<String> models) {
        if (!running.compareAndSet(false, true)) throw new ExperimentBusyException();
        try {
            return doRun(models);
        } finally {
            running.set(false);
        }
    }

    private ExperimentResult doRun(List<String> models) {
        LocalDateTime ranAt = LocalDateTime.now();

        // 운영(analyzeAllStocks)과 같은 방식으로 입력을 만든다: 뉴스가 있는 종목만, 기사는 id 오름차순
        List<Stock> targets = new ArrayList<>();
        List<List<NewsArticle>> targetNews = new ArrayList<>();
        int articleCount = 0;
        for (Stock stock : stockRepository.findAllByOrderByRankingAsc()) {
            List<NewsArticle> articles = newsRepository.findByStockOrderByIdAsc(stock);
            if (articles.isEmpty()) continue;
            targets.add(stock);
            targetNews.add(articles);
            articleCount += articles.size();
        }
        if (targets.isEmpty()) {
            log.info("모델 비교 실험: 뉴스가 있는 종목이 없어 모델을 호출하지 않음");
            return new ExperimentResult(ranAt, 0, 0, "뉴스가 있는 종목이 없어 모델을 호출하지 않았습니다.", List.of());
        }

        if (stockScheduler.isUpdateRunning()) {
            log.warn("모델 비교 실험이 운영 수집 사이클과 동시에 실행됨 - 외부 API 호출이 겹칠 수 있음");
        }

        String prompt = geminiAnalysisService.buildPrompt(targets, targetNews);
        int[] newsCounts = targetNews.stream().mapToInt(List::size).toArray();
        log.info("모델 비교 실험 시작 - 모델 {}개, 종목 {}개, 기사 {}건", models.size(), targets.size(), articleCount);

        List<ModelResult> results = new ArrayList<>();
        for (String model : models) {
            results.add(runOne(model, prompt, newsCounts));
        }
        log.info("모델 비교 실험 완료");
        return new ExperimentResult(ranAt, targets.size(), articleCount, null, results);
    }

    private ModelResult runOne(String model, String prompt, int[] newsCounts) {
        long started = System.currentTimeMillis();
        GeminiAnalysisService.ModelResponse response;
        try {
            response = geminiAnalysisService.callModel(model, geminiAnalysisService.buildRequestBody(model, prompt));
        } catch (Exception e) {
            String code = classifyCallError(e);
            log.warn("모델 비교 실험 - '{}' 호출 실패: {}", model, code);
            return failure(model, code, System.currentTimeMillis() - started, newsCounts.length, null);
        }

        GeminiAnalysisService.ValidationStats stats = new GeminiAnalysisService.ValidationStats();
        Map<Integer, GeminiAnalysisService.StockAnalysis> analyses;
        try {
            analyses = GeminiAnalysisService.parseAndValidate(objectMapper, response.text(), newsCounts, stats);
        } catch (IOException e) {
            log.warn("모델 비교 실험 - '{}' 응답 해석 실패", model);
            return failure(model, "INVALID_JSON", System.currentTimeMillis() - started, newsCounts.length, response);
        }
        long elapsed = System.currentTimeMillis() - started;
        return buildResult(model, elapsed, response, newsCounts.length, analyses, stats);
    }

    /** 검증 결과로 지표를 계산한다 (순수 함수 — 테스트에서 직접 부른다). */
    static ModelResult buildResult(String model, long elapsedMs, GeminiAnalysisService.ModelResponse response,
                                   int stocksRequested,
                                   Map<Integer, GeminiAnalysisService.StockAnalysis> analyses,
                                   GeminiAnalysisService.ValidationStats stats) {
        long chars = 0;
        int issues = 0;
        for (GeminiAnalysisService.StockAnalysis a : analyses.values()) {
            for (GeminiAnalysisService.Issue issue : a.issues()) {
                chars += issue.text().length();
                issues++;
            }
        }
        Double avg = issues == 0 ? null : Math.round(chars * 10.0 / issues) / 10.0;
        return new ModelResult(model, true, null, elapsedMs,
                response.promptTokens(), response.outputTokens(), response.thoughtTokens(),
                stocksRequested, stats.returnedIndexes.size(), analyses.size(),
                stats.issuesTotal, stats.issuesWithValidSources, stats.invalidSourceRefs, stats.issuesDroppedNoSource,
                avg);
    }

    private static ModelResult failure(String model, String error, long elapsedMs, int stocksRequested,
                                       GeminiAnalysisService.ModelResponse response) {
        return new ModelResult(model, false, error, elapsedMs,
                response == null ? null : response.promptTokens(),
                response == null ? null : response.outputTokens(),
                response == null ? null : response.thoughtTokens(),
                stocksRequested, 0, 0, 0, 0, 0, 0, null);
    }

    /**
     * 호출 예외를 짧은 코드로 바꾼다. 응답 본문·URL·헤더(키)는 예외 메시지에 들어 있을 수 있으므로
     * 메시지를 그대로 내보내지 않고 HTTP 상태 코드나 고정 코드만 쓴다.
     */
    static String classifyCallError(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof WebClientResponseException w) return "HTTP_" + w.getStatusCode().value();
            if (t instanceof TimeoutException) return "TIMEOUT";
            if (t instanceof WebClientRequestException) return "CONNECTION_ERROR";
            if (t instanceof JsonProcessingException) return "INVALID_JSON";
            String msg = t.getMessage();
            if (t instanceof IllegalStateException && msg != null && msg.contains("Timeout")) return "TIMEOUT";
            // GeminiAnalysisService.callModel 이 직접 던지는 메시지
            if (msg != null && msg.startsWith("빈 응답")) return "EMPTY_RESPONSE";
            if (msg != null && msg.startsWith("응답 파싱 실패")) return "INVALID_JSON";
        }
        return "CALL_FAILED";
    }
}
