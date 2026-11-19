package com.stocknews.service;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 호출 구조 비교 실험의 결과 (관리자용, DB 에 저장하지 않는다).
 * variant 는 BATCH(모든 종목을 한 프롬프트로 한 번 호출) / PER_STOCK(종목마다 따로 호출).
 * 숫자 측정은 두 변형 모두 모든 대상 종목을 한꺼번에 놓고 하므로 OTHER_STOCK 이 양쪽에서 잡힌다. 모델 원본 기준이며 가드는 적용하지 않는다.
 */
public record StructureAbResult(LocalDateTime startedAt, String model, int runs, Input input,
                                Map<String, VariantTotals> variants, List<Repetition> repetitions, String message) {

    public static final String BATCH = "BATCH";
    public static final String PER_STOCK = "PER_STOCK";

    /** 입력 크기. articleTexts 는 includeInput=true 일 때만 채운다 (기사 글 포함이라 마지막 결과로 보관하지 않는다). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Input(int stocks, int articles, int articlesWithBody, int batchPromptChars,
                        int perStockPromptCharsTotal, List<ModelCompareResult.Article> articleTexts) {
        public Input(int stocks, int articles, int articlesWithBody, int batchPromptChars, int perStockPromptCharsTotal) {
            this(stocks, articles, articlesWithBody, batchPromptChars, perStockPromptCharsTotal, null);
        }

        Input withoutTexts() {
            return new Input(stocks, articles, articlesWithBody, batchPromptChars, perStockPromptCharsTotal, null);
        }
    }

    /** 변형 한 번(반복 한 회). 실패면 ok=false 와 첫 오류 코드 (응답 본문·예외 메시지는 담지 않는다). */
    public record Repetition(String variant, int iteration, boolean ok, String errorCode, int apiCalls,
                             int stocksValidated, int stocksFailed, long elapsedMs, Integer promptTokens,
                             Integer outputTokens, int issueCount, int citationCount, int issuesWithNumbers,
                             int numbersTotal, int numbersUnverified, int otherArticle, int otherStock, int notFound,
                             List<CitationAbResult.Detail> details, List<ModelCompareResult.Output> outputs) {}

    /** 변형별 합계 (성공한 반복만 더한다) */
    public record VariantTotals(int repetitionsOk, int repetitionsFailed, int apiCalls, int stocksFailed, int issues,
                                int issuesWithNumbers, int citations, int numbersTotal, int unverified,
                                int otherArticle, int otherStock, int notFound, Long medianElapsedMs,
                                Double meanPromptTokens, Double meanOutputTokens) {}

    /** 기사 글을 뺀 사본 (마지막 결과로 보관할 때 쓴다) */
    public StructureAbResult withoutArticleTexts() {
        return new StructureAbResult(startedAt, model, runs, input == null ? null : input.withoutTexts(), variants,
                repetitions, message);
    }

    static VariantTotals totals(List<Repetition> reps, String variant) {
        int ok = 0, failed = 0, calls = 0, stocksFailed = 0, issues = 0, withNum = 0, citations = 0, total = 0,
                unverified = 0, otherArticle = 0, otherStock = 0, notFound = 0;
        List<Long> elapsed = new ArrayList<>();
        long promptSum = 0, outputSum = 0;
        int promptN = 0, outputN = 0;
        for (Repetition r : reps) {
            if (!variant.equals(r.variant())) continue;
            if (!r.ok()) { failed++; continue; }
            ok++;
            calls += r.apiCalls();
            stocksFailed += r.stocksFailed();
            issues += r.issueCount();
            withNum += r.issuesWithNumbers();
            citations += r.citationCount();
            total += r.numbersTotal();
            unverified += r.numbersUnverified();
            otherArticle += r.otherArticle();
            otherStock += r.otherStock();
            notFound += r.notFound();
            elapsed.add(r.elapsedMs());
            if (r.promptTokens() != null) { promptSum += r.promptTokens(); promptN++; }
            if (r.outputTokens() != null) { outputSum += r.outputTokens(); outputN++; }
        }
        Long median = null;
        if (!elapsed.isEmpty()) {
            elapsed.sort(Long::compare);
            int n = elapsed.size();
            median = n % 2 == 1 ? elapsed.get(n / 2) : (elapsed.get(n / 2 - 1) + elapsed.get(n / 2)) / 2;
        }
        return new VariantTotals(ok, failed, calls, stocksFailed, issues, withNum, citations, total, unverified,
                otherArticle, otherStock, notFound, median,
                promptN == 0 ? null : round1((double) promptSum / promptN),
                outputN == 0 ? null : round1((double) outputSum / outputN));
    }

    static Map<String, VariantTotals> totalsByVariant(List<Repetition> reps) {
        Map<String, VariantTotals> m = new LinkedHashMap<>();
        m.put(BATCH, totals(reps, BATCH));
        m.put(PER_STOCK, totals(reps, PER_STOCK));
        return m;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** 호출하지 않았을 때(입력 없음, 키 없음, 사이클이 분석 단계에 못 간 경우)의 결과 */
    public static StructureAbResult notRun(LocalDateTime startedAt, int runs, Input input, String message) {
        return new StructureAbResult(startedAt, null, runs, input, totalsByVariant(new ArrayList<>()), List.of(), message);
    }
}
