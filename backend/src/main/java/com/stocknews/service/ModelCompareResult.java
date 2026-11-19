package com.stocknews.service;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 기사 본문 입력으로 여러 모델을 비교하는 관리자 실험의 결과 (DB 에 저장하지 않는다).
 * models 의 키는 "제공자:모델" 이고 요청한 순서를 유지한다. 키가 없어 부르지 않은 모델은 skipped=true.
 */
public record ModelCompareResult(LocalDateTime startedAt, int runsPerModel, int pauseSeconds, Input input,
                                 Map<String, ModelTotals> models, List<Call> calls, String message) {

    public static final List<String> DEFAULT_MODELS = List.of("gemini:gemini-2.5-flash", "gemini:gemini-3.5-flash-lite",
            "claude:claude-haiku-5-5", "openai:gpt-6-luna");
    public static final int MAX_MODELS = 5;
    public static final int MAX_PAUSE_SECONDS = 120;
    private static final List<String> PROVIDERS = List.of("gemini", "claude", "openai");
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    /**
     * 입력 크기: 뉴스가 있는 종목 수, 기사 수, 그중 본문이 들어간 기사 수, 프롬프트 글자 수.
     * articles 는 includeInput=true 일 때만 채운다 (기사 글 포함이라 마지막 결과로 보관하지 않는다).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Input(int stocks, int articles, int articlesWithBody, int promptChars, List<Article> articleTexts) {
        public Input(int stocks, int articles, int articlesWithBody, int promptChars) {
            this(stocks, articles, articlesWithBody, promptChars, null);
        }

        Input withoutTexts() {
            return new Input(stocks, articles, articlesWithBody, promptChars, null);
        }
    }

    /** 모델에게 보여 준 기사 한 건. newsIndex 는 근거 번호(1부터), text 는 shownText 와 같은 글. */
    public record Article(String stock, int stockIndex, int newsIndex, String title, String text, boolean hasBody) {}

    /** 모델이 낸 이슈 문장 하나 (검증을 통과한 것) */
    public record Output(String stock, int stockIndex, String issue, List<Integer> sources) {}

    /** 모델이 낸 종목별 나머지 항목 */
    public record StockOutput(String stock, int stockIndex, String sentiment, List<String> positives,
                              String caution, List<String> risks) {}

    /** 모델 호출 한 번. 실패면 ok=false 와 짧은 errorCode (응답 본문·예외 메시지는 담지 않는다). */
    public record Call(String model, int iteration, boolean ok, String errorCode, long elapsedMs,
                       Integer promptTokens, Integer outputTokens, int stocksValidated, int issueCount,
                       int citationCount, int issuesWithNumbers, int numbersTotal, int numbersUnverified,
                       int otherArticle, int numbersNotInAnyArticle, List<CitationAbResult.Detail> details,
                       List<Output> outputs, List<StockOutput> stockOutputs) {}

    /** 모델별 합계 (성공한 호출만 더한다). 호출하지 않은 모델은 skipped=true 이고 나머지는 0/null. */
    public record ModelTotals(boolean skipped, int callsOk, int callsFailed, int issues, int issuesWithNumbers,
                              int citations, int numbersTotal, int unverified, int otherArticle, int notInAny,
                              Long medianElapsedMs, Double meanPromptTokens, Double meanOutputTokens) {}

    /** 기사 글을 뺀 사본 (마지막 결과로 보관할 때 쓴다) */
    public ModelCompareResult withoutArticleTexts() {
        return new ModelCompareResult(startedAt, runsPerModel, pauseSeconds, input == null ? null : input.withoutTexts(),
                models, calls, message);
    }

    /**
     * "제공자:모델" 쉼표 목록 해석. 비어 있으면 기본 목록. 제공자는 gemini·claude·openai, 최대 5개, 중복 불가.
     * 잘못되면 IllegalArgumentException (메시지에 입력값을 싣지 않는다).
     */
    public static List<String> parseModels(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_MODELS;
        List<String> keys = new ArrayList<>();
        for (String item : raw.split(",")) {
            String t = item.trim();
            if (t.isEmpty()) continue;
            int colon = t.indexOf(':');
            String provider = colon < 0 ? "" : t.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String model = colon < 0 ? "" : t.substring(colon + 1).trim();
            if (!PROVIDERS.contains(provider)) throw new IllegalArgumentException("제공자는 gemini, claude, openai 만 쓸 수 있습니다");
            if (!MODEL_ID.matcher(model).matches()) throw new IllegalArgumentException("모델 이름이 올바르지 않습니다");
            String key = provider + ":" + model;
            if (keys.contains(key)) throw new IllegalArgumentException("같은 모델이 중복되었습니다");
            keys.add(key);
        }
        if (keys.isEmpty()) throw new IllegalArgumentException("모델 목록이 비어 있습니다");
        if (keys.size() > MAX_MODELS) throw new IllegalArgumentException("모델은 최대 " + MAX_MODELS + "개까지입니다");
        return keys;
    }

    static ModelTotals totals(List<Call> calls, String model) {
        int ok = 0, failed = 0, issues = 0, withNum = 0, citations = 0, total = 0, unverified = 0, other = 0, notInAny = 0;
        List<Long> elapsed = new ArrayList<>();
        long promptSum = 0, outputSum = 0;
        int promptN = 0, outputN = 0;
        for (Call c : calls) {
            if (!model.equals(c.model())) continue;
            if (!c.ok()) { failed++; continue; }
            ok++;
            issues += c.issueCount();
            withNum += c.issuesWithNumbers();
            citations += c.citationCount();
            total += c.numbersTotal();
            unverified += c.numbersUnverified();
            other += c.otherArticle();
            notInAny += c.numbersNotInAnyArticle();
            elapsed.add(c.elapsedMs());
            if (c.promptTokens() != null) { promptSum += c.promptTokens(); promptN++; }
            if (c.outputTokens() != null) { outputSum += c.outputTokens(); outputN++; }
        }
        Long median = null;
        if (!elapsed.isEmpty()) {
            elapsed.sort(Long::compare);
            int n = elapsed.size();
            median = n % 2 == 1 ? elapsed.get(n / 2) : (elapsed.get(n / 2 - 1) + elapsed.get(n / 2)) / 2;
        }
        return new ModelTotals(false, ok, failed, issues, withNum, citations, total, unverified, other, notInAny, median,
                promptN == 0 ? null : round1((double) promptSum / promptN),
                outputN == 0 ? null : round1((double) outputSum / outputN));
    }

    static ModelTotals skippedTotals() {
        return new ModelTotals(true, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null, null);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** 호출하지 않았을 때(입력 없음, 사이클이 분석 단계에 못 간 경우)의 결과 */
    public static ModelCompareResult notRun(LocalDateTime startedAt, int runs, int pauseSeconds, Input input,
                                            List<String> models, String message) {
        Map<String, ModelTotals> m = new LinkedHashMap<>();
        for (String k : models) m.put(k, totals(List.of(), k));
        return new ModelCompareResult(startedAt, runs, pauseSeconds, input, m, List.of(), message);
    }
}
