package com.stocknews.service;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 시점 표현 지침(TIME_RULE) 유무 비교 실험의 결과 (관리자용, DB 에 저장하지 않는다).
 * 두 변형 모두 종목마다 따로 호출하는 구조이고 프롬프트만 다르다: WITHOUT_TIME_RULE(지침 없는 이전 프롬프트) / WITH_TIME_RULE(현재 운영 프롬프트).
 * 숫자 측정은 StructureAbResult 와 같다(모든 대상 종목을 한꺼번에 놓고, 모델 원본 기준, 가드 미적용).
 * yearsUnverified 는 확인되지 않은 숫자(details) 중 네 자리 연도(19xx, 20xx)인 것의 수.
 */
public record TimeRuleAbResult(LocalDateTime startedAt, String model, int runs, Input input,
                               Map<String, VariantTotals> variants, List<Repetition> repetitions, String message) {

    public static final String WITHOUT_TIME_RULE = "WITHOUT_TIME_RULE";
    public static final String WITH_TIME_RULE = "WITH_TIME_RULE";

    private static final Pattern YEAR = Pattern.compile("(19|20)\\d{2}");

    /** 입력 크기. articleTexts 는 includeInput=true 일 때만 채운다 (기사 글 포함이라 마지막 결과로 보관하지 않는다). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Input(int stocks, int articles, int articlesWithBody, int withoutRulePromptCharsTotal,
                        int withRulePromptCharsTotal, List<ModelCompareResult.Article> articleTexts) {
        public Input(int stocks, int articles, int articlesWithBody, int withoutRulePromptCharsTotal,
                     int withRulePromptCharsTotal) {
            this(stocks, articles, articlesWithBody, withoutRulePromptCharsTotal, withRulePromptCharsTotal, null);
        }

        Input withoutTexts() {
            return new Input(stocks, articles, articlesWithBody, withoutRulePromptCharsTotal, withRulePromptCharsTotal,
                    null);
        }
    }

    /** 변형 한 번(반복 한 회). 실패면 ok=false 와 첫 오류 코드 (응답 본문·예외 메시지는 담지 않는다). */
    public record Repetition(String variant, int iteration, boolean ok, String errorCode, int apiCalls,
                             int stocksValidated, int stocksFailed, long elapsedMs, Integer promptTokens,
                             Integer outputTokens, int issueCount, int citationCount, int issuesWithNumbers,
                             int numbersTotal, int numbersUnverified, int otherArticle, int otherStock, int notFound,
                             int yearsUnverified, List<CitationAbResult.Detail> details,
                             List<ModelCompareResult.Output> outputs) {}

    /** 변형별 합계 (성공한 반복만 더한다) */
    public record VariantTotals(int repetitionsOk, int repetitionsFailed, int apiCalls, int stocksFailed, int issues,
                                int issuesWithNumbers, int citations, int numbersTotal, int unverified,
                                int otherArticle, int otherStock, int notFound, int yearsUnverified,
                                Long medianElapsedMs, Double meanPromptTokens, Double meanOutputTokens) {}

    /** 기사 글을 뺀 사본 (마지막 결과로 보관할 때 쓴다) */
    public TimeRuleAbResult withoutArticleTexts() {
        return new TimeRuleAbResult(startedAt, model, runs, input == null ? null : input.withoutTexts(), variants,
                repetitions, message);
    }

    /** details 중 숫자가 네 자리 연도(19xx, 20xx)인 항목 수 */
    static int countYears(List<CitationAbResult.Detail> details) {
        int n = 0;
        for (CitationAbResult.Detail d : details) {
            if (d.number() != null && YEAR.matcher(d.number()).matches()) n++;
        }
        return n;
    }

    /** 종목별 호출 구조 비교의 반복 결과를 이 실험의 변형 이름과 연도 수를 붙여 옮긴다. */
    static Repetition from(String variant, StructureAbResult.Repetition r) {
        return new Repetition(variant, r.iteration(), r.ok(), r.errorCode(), r.apiCalls(), r.stocksValidated(),
                r.stocksFailed(), r.elapsedMs(), r.promptTokens(), r.outputTokens(), r.issueCount(), r.citationCount(),
                r.issuesWithNumbers(), r.numbersTotal(), r.numbersUnverified(), r.otherArticle(), r.otherStock(),
                r.notFound(), countYears(r.details()), r.details(), r.outputs());
    }

    static VariantTotals totals(List<Repetition> reps, String variant) {
        int ok = 0, failed = 0, calls = 0, stocksFailed = 0, issues = 0, withNum = 0, citations = 0, total = 0,
                unverified = 0, otherArticle = 0, otherStock = 0, notFound = 0, years = 0;
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
            years += r.yearsUnverified();
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
                otherArticle, otherStock, notFound, years, median,
                promptN == 0 ? null : round1((double) promptSum / promptN),
                outputN == 0 ? null : round1((double) outputSum / outputN));
    }

    static Map<String, VariantTotals> totalsByVariant(List<Repetition> reps) {
        Map<String, VariantTotals> m = new LinkedHashMap<>();
        m.put(WITHOUT_TIME_RULE, totals(reps, WITHOUT_TIME_RULE));
        m.put(WITH_TIME_RULE, totals(reps, WITH_TIME_RULE));
        return m;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** 호출하지 않았을 때(입력 없음, 키 없음, 사이클이 분석 단계에 못 간 경우)의 결과 */
    public static TimeRuleAbResult notRun(LocalDateTime startedAt, int runs, Input input, String message) {
        return new TimeRuleAbResult(startedAt, null, runs, input, totalsByVariant(new ArrayList<>()), List.of(), message);
    }
}
