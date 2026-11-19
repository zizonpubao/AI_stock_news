package com.stocknews.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 근거 번호 지침(CITATION_RULE) 유무 비교 실험의 결과 (관리자용, DB 에 저장하지 않는다).
 * variant 는 WITHOUT_RULE(지침 없는 이전 프롬프트) / WITH_RULE(현재 운영 프롬프트).
 */
public record CitationAbResult(LocalDateTime startedAt, String model, int runsPerVariant, Input input,
                               Map<String, VariantTotals> variants, List<Call> calls, String message) {

    public static final String WITHOUT_RULE = "WITHOUT_RULE";
    public static final String WITH_RULE = "WITH_RULE";

    /** 입력 크기: 뉴스가 있는 종목 수, 기사 수, 그중 본문이 들어간 기사 수 */
    public record Input(int stocks, int articles, int articlesWithBody) {}

    /** 확인되지 않은 숫자 하나. category: OTHER_ARTICLE / NOT_FOUND. */
    public record Detail(String stock, String number, String category, List<Integer> sources, String issue) {}

    /** 모델 호출 한 번. 실패면 ok=false 와 짧은 errorCode (응답 본문·예외 메시지는 담지 않는다). */
    public record Call(String variant, int iteration, boolean ok, String model, String errorCode, long elapsedMs,
                       Integer promptTokens, Integer outputTokens, int stocksValidated, int issueCount,
                       int citationCount, int numbersTotal, int numbersUnverified, int numbersNotInAnyArticle,
                       int otherArticle, List<Detail> details) {}

    /** 변형별 합계 (성공한 호출만 더한다) */
    public record VariantTotals(int callsOk, int callsFailed, int issues, int citations, int numbersTotal,
                                int unverified, int otherArticle, int notInAny) {}

    static VariantTotals totals(List<Call> calls, String variant) {
        int ok = 0, failed = 0, issues = 0, citations = 0, total = 0, unverified = 0, other = 0, notInAny = 0;
        for (Call c : calls) {
            if (!variant.equals(c.variant())) continue;
            if (!c.ok()) { failed++; continue; }
            ok++;
            issues += c.issueCount();
            citations += c.citationCount();
            total += c.numbersTotal();
            unverified += c.numbersUnverified();
            other += c.otherArticle();
            notInAny += c.numbersNotInAnyArticle();
        }
        return new VariantTotals(ok, failed, issues, citations, total, unverified, other, notInAny);
    }

    static Map<String, VariantTotals> totalsByVariant(List<Call> calls) {
        Map<String, VariantTotals> m = new LinkedHashMap<>();
        m.put(WITHOUT_RULE, totals(calls, WITHOUT_RULE));
        m.put(WITH_RULE, totals(calls, WITH_RULE));
        return m;
    }

    /** 호출하지 않았을 때(입력 없음, 키 없음, 사이클이 분석 단계에 못 간 경우)의 결과 */
    public static CitationAbResult notRun(LocalDateTime startedAt, int runsPerVariant, Input input, String message) {
        return new CitationAbResult(startedAt, null, runsPerVariant, input, totalsByVariant(new ArrayList<>()),
                List.of(), message);
    }
}
