package com.stocknews.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 반복별 원자료 → 모델별 집계, 가격표 해석, 비용 계산 (순수 함수). */
final class ExperimentStats {

    private ExperimentStats() {}

    /** 한 번의 호출(반복 1회)에서 얻은 값 전부. 호출이 실패하면 지표 필드는 0/null. */
    public record RunRecord(
            int run, boolean callOk, String errorCode, long elapsedMs,
            Integer inputTokens, Integer outputTokens, Integer reasoningTokens, Double costUsd,
            boolean jsonParsed,
            int stocksRequested, int stocksReturned, int stocksValid,
            int issuesTotal, int issuesWithValidSources, int invalidSourceRefs, int issuesDroppedNoSource,
            Double avgIssueChars,
            int issuesWithNumbers, int issuesAllNumbersConfirmed, int unverifiedNumbers,
            List<NumericGrounding.Unverified> unverifiedExamples) {}

    public record Aggregate(
            int runs, int callOk, int jsonParsed,
            Long medianMs, Long minMs, Long maxMs,
            Double avgInputTokens, Double avgOutputTokens, Double avgReasoningTokens, Double avgCostUsd,
            Double stockValidRate, Double sourceValidRate, Double numericMatchRate,
            int stocksRequested, int stocksReturned, int stocksValid,
            int issuesTotal, int issuesWithValidSources, int invalidSourceRefs, int issuesDroppedNoSource,
            int issuesWithNumbers, int issuesAllNumbersConfirmed) {}

    public record Price(double inputPerMTok, double outputPerMTok, String source, String checkedAt, String note) {}

    /**
     * 모델별 집계. 소요 시간·토큰·비용은 호출이 성공한 반복만, 형식·근거 지표는 JSON 해석에 성공한 반복만 센다.
     * 분모가 0 이면 null.
     */
    static Aggregate aggregate(List<RunRecord> runs) {
        List<RunRecord> ok = new ArrayList<>();
        List<RunRecord> parsed = new ArrayList<>();
        for (RunRecord r : runs) {
            if (r.callOk()) ok.add(r);
            if (r.jsonParsed()) parsed.add(r);
        }
        List<Long> times = new ArrayList<>();
        for (RunRecord r : ok) times.add(r.elapsedMs());

        int stocksRequested = sum(parsed, RunRecord::stocksRequested);
        int stocksValid = sum(parsed, RunRecord::stocksValid);
        int issuesTotal = sum(parsed, RunRecord::issuesTotal);
        int issuesValid = sum(parsed, RunRecord::issuesWithValidSources);
        int withNumbers = sum(parsed, RunRecord::issuesWithNumbers);
        int allConfirmed = sum(parsed, RunRecord::issuesAllNumbersConfirmed);

        return new Aggregate(runs.size(), ok.size(), parsed.size(),
                median(times), times.isEmpty() ? null : Collections.min(times), times.isEmpty() ? null : Collections.max(times),
                avg(ok, RunRecord::inputTokens), avg(ok, RunRecord::outputTokens),
                avg(ok, RunRecord::reasoningTokens), avgD(ok, RunRecord::costUsd),
                rate(stocksValid, stocksRequested), rate(issuesValid, issuesTotal), rate(allConfirmed, withNumbers),
                stocksRequested, sum(parsed, RunRecord::stocksReturned), stocksValid,
                issuesTotal, issuesValid, sum(parsed, RunRecord::invalidSourceRefs), sum(parsed, RunRecord::issuesDroppedNoSource),
                withNumbers, allConfirmed);
    }

    static Long median(List<Long> values) {
        if (values.isEmpty()) return null;
        List<Long> s = new ArrayList<>(values);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : Math.round((s.get(n / 2 - 1) + s.get(n / 2)) / 2.0);
    }

    private static int sum(List<RunRecord> list, Function<RunRecord, Integer> f) {
        int t = 0;
        for (RunRecord r : list) t += f.apply(r);
        return t;
    }

    private static Double avg(List<RunRecord> list, Function<RunRecord, Integer> f) {
        long t = 0;
        int n = 0;
        for (RunRecord r : list) {
            Integer v = f.apply(r);
            if (v != null) { t += v; n++; }
        }
        return n == 0 ? null : Math.round(t * 10.0 / n) / 10.0;
    }

    private static Double avgD(List<RunRecord> list, Function<RunRecord, Double> f) {
        double t = 0;
        int n = 0;
        for (RunRecord r : list) {
            Double v = f.apply(r);
            if (v != null) { t += v; n++; }
        }
        return n == 0 ? null : t / n;
    }

    /** 비율(0~1, 소수 넷째 자리). 분모가 0 이면 null. */
    static Double rate(int numerator, int denominator) {
        return denominator == 0 ? null : Math.round(numerator * 10000.0 / denominator) / 10000.0;
    }

    // ===== 가격표 =====

    /** model-prices.json 해석. 키는 "제공자:모델ID". 단가 필드가 숫자가 아닌 항목은 건너뛴다. */
    static Map<String, Price> parsePrices(ObjectMapper mapper, InputStream in) throws IOException {
        Map<String, Price> prices = new LinkedHashMap<>();
        if (in == null) return prices;
        JsonNode root = mapper.readTree(in);
        if (root == null || !root.isObject()) return prices;
        root.fields().forEachRemaining(e -> {
            JsonNode v = e.getValue();
            if (v.path("inputPerMTok").isNumber() && v.path("outputPerMTok").isNumber()) {
                prices.put(e.getKey(), new Price(v.path("inputPerMTok").asDouble(), v.path("outputPerMTok").asDouble(),
                        v.path("source").asText(""), v.path("checkedAt").asText(""), v.path("note").asText("")));
            }
        });
        return prices;
    }

    /** 1회 비용(USD). 가격이나 토큰 수를 모르면 null. */
    static Double cost(Price price, Integer inputTokens, Integer outputTokens) {
        if (price == null || inputTokens == null || outputTokens == null) return null;
        return inputTokens * price.inputPerMTok() / 1_000_000.0 + outputTokens * price.outputPerMTok() / 1_000_000.0;
    }
}
