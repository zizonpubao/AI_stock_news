package com.stocknews.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocknews.service.ExperimentConfig.ModelSpec;
import com.stocknews.service.ExperimentStats.RunRecord;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** experiment 소스 세트의 순수 함수 테스트 (네트워크·키 없음). */
class ModelExperimentToolsTest {

    // ===== 수치 근거 일치율 =====

    @Test
    void extractNumbers_정수_소수_퍼센트_쉼표() {
        assertEquals(List.of("1234", "3.5%", "20"), NumericGrounding.extractNumbers("매출 1,234억, 증가율 3.5%, 20 건"));
        assertEquals(List.of("5%"), NumericGrounding.extractNumbers("5 % 올랐다"));
        assertEquals(List.of("12"), NumericGrounding.extractNumbers("12. 끝")); // 문장 끝 점은 소수가 아님
        assertEquals(List.of("7"), NumericGrounding.extractNumbers("7 7")); // 중복 제거
        assertTrue(NumericGrounding.extractNumbers("숫자 없음").isEmpty());
        assertTrue(NumericGrounding.extractNumbers(null).isEmpty());
    }

    @Test
    void evaluate_인용_기사에_있는_숫자만_확인됨() {
        List<String> articles = List.of(
                NumericGrounding.articleText("삼성전자 영업이익 9.1조", "전년 대비 12% 증가, 주가 195,100원", 300),
                NumericGrounding.articleText("다른 기사", "수치 99", 300));
        var ok = new GeminiAnalysisService.Issue("영업이익 9.1조, 12% 증가", List.of(1));
        var comma = new GeminiAnalysisService.Issue("주가 195100원", List.of(1)); // 쉼표 제거 후 일치
        var wrongSource = new GeminiAnalysisService.Issue("숫자 99 언급", List.of(1)); // 99 는 2번 기사에만 있음
        var partial = new GeminiAnalysisService.Issue("영업이익 9.1조, 증가율 15%", List.of(1));
        var noNumber = new GeminiAnalysisService.Issue("노조 출범", List.of(1));

        NumericGrounding.Result r = NumericGrounding.evaluate(List.of(
                new NumericGrounding.StockInput("삼성전자", articles, List.of(ok, comma, wrongSource, partial, noNumber))));

        assertEquals(4, r.issuesWithNumbers());
        assertEquals(2, r.issuesAllConfirmed());
        assertEquals(2, r.unverifiedNumbers()); // 99, 15%
        assertEquals(List.of("99", "15%"), r.examples().stream().map(NumericGrounding.Unverified::number).toList());
        assertEquals("삼성전자", r.examples().get(0).stock());
    }

    @Test
    void evaluate_여러_기사를_인용하면_합쳐서_확인하고_예시는_최대_5개() {
        List<String> articles = List.of("가 10", "나 20");
        var both = new GeminiAnalysisService.Issue("10 과 20", List.of(1, 2));
        assertEquals(1, NumericGrounding.evaluate(List.of(new NumericGrounding.StockInput("A", articles, List.of(both)))).issuesAllConfirmed());

        var many = new GeminiAnalysisService.Issue("1 2 3 4 5 6 7", List.of(1));
        NumericGrounding.Result r = NumericGrounding.evaluate(List.of(new NumericGrounding.StockInput("A", articles, List.of(many))));
        assertEquals(0, r.issuesAllConfirmed());
        assertEquals(7, r.unverifiedNumbers());
        assertEquals(NumericGrounding.MAX_EXAMPLES, r.examples().size());
    }

    @Test
    void evaluate_표기가_다른_같은_값은_불일치_한계() {
        List<String> articles = List.of("영업이익 2조 원");
        var issue = new GeminiAnalysisService.Issue("영업이익 20,000억 원", List.of(1));
        NumericGrounding.Result r = NumericGrounding.evaluate(List.of(new NumericGrounding.StockInput("A", articles, List.of(issue))));
        assertEquals(1, r.issuesWithNumbers());
        assertEquals(0, r.issuesAllConfirmed());
    }

    @Test
    void articleText_요약은_프롬프트_길이까지만() {
        String desc = "a".repeat(10) + " 777";
        assertFalse(NumericGrounding.extractNumbers(NumericGrounding.articleText("t", desc, 10)).contains("777"));
        assertTrue(NumericGrounding.extractNumbers(NumericGrounding.articleText("t", desc, 300)).contains("777"));
    }

    // ===== 설정 해석 =====

    @Test
    void parseModels_기본값과_공백_중복() {
        // 기본 대상: 기준선(현재 운영 모델), 자동 전환 대상, 가격대가 같은 3사 최저가 모델
        assertEquals(List.of(
                        new ModelSpec("gemini", "gemini-2.5-flash"),
                        new ModelSpec("gemini", "gemini-3.5-flash-lite"),
                        new ModelSpec("gemini", "gemini-2.5-flash-lite"),
                        new ModelSpec("claude", "claude-haiku-5-5"),
                        new ModelSpec("openai", "gpt-6-luna")),
                ExperimentConfig.parseModels(""));
        assertEquals(List.of(new ModelSpec("openai", "m_1.2-x")),
                ExperimentConfig.parseModels(" OpenAI : m_1.2-x , openai:m_1.2-x "));
        assertEquals("claude:claude-haiku-5-5", ExperimentConfig.parseModels("claude:claude-haiku-5-5").get(0).key());
    }

    @Test
    void parseModels_잘못된_입력은_예외() {
        assertThrows(IllegalArgumentException.class, () -> ExperimentConfig.parseModels("gemini-2.5-flash"));
        assertThrows(IllegalArgumentException.class, () -> ExperimentConfig.parseModels("foo:bar"));
        assertThrows(IllegalArgumentException.class, () -> ExperimentConfig.parseModels("gemini:../x"));
        assertThrows(IllegalArgumentException.class, () -> ExperimentConfig.parseModels("gemini:a b"));
        assertThrows(IllegalArgumentException.class, () -> ExperimentConfig.parseModels("gemini:a/b"));
        assertThrows(IllegalArgumentException.class, () -> ExperimentConfig.parseModels("gemini:"));
        assertThrows(IllegalArgumentException.class, () -> ExperimentConfig.parseModels(",,"));
    }

    @Test
    void parseRuns_기본_상한_하한() {
        assertEquals(3, ExperimentConfig.parseRuns(null));
        assertEquals(3, ExperimentConfig.parseRuns("  "));
        assertEquals(5, ExperimentConfig.parseRuns("9"));
        assertEquals(1, ExperimentConfig.parseRuns("0"));
        assertEquals(4, ExperimentConfig.parseRuns("4"));
        assertThrows(IllegalArgumentException.class, () -> ExperimentConfig.parseRuns("abc"));
    }

    // ===== 스키마 변환 =====

    @Test
    void 스키마_변환은_모든_object_에_additionalProperties_false_를_더하고_원본은_그대로() {
        Map<String, Object> original = GeminiAnalysisService.responseSchema();
        Map<String, Object> converted = ExperimentSchemas.withNoAdditionalProperties(original);

        assertFalse(original.containsKey("additionalProperties"));
        assertEquals(Boolean.FALSE, converted.get("additionalProperties"));
        @SuppressWarnings("unchecked")
        Map<String, Object> stocks = (Map<String, Object>) ((Map<String, Object>) converted.get("properties")).get("stocks");
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) stocks.get("items");
        assertEquals(Boolean.FALSE, item.get("additionalProperties"));
        // 이슈 객체(더 깊은 곳)
        @SuppressWarnings("unchecked")
        Map<String, Object> issue = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) item.get("properties")).get("issues")).get("items");
        assertEquals(Boolean.FALSE, issue.get("additionalProperties"));
        // 필드 구성(required)은 같다
        assertEquals(original.get("required"), converted.get("required"));
        assertEquals(item.get("required"), ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) original.get("properties")).get("stocks")).get("items")).get("required"));
    }

    // ===== 집계·가격 =====

    private static RunRecord ok(int run, long ms, int in, int out, Integer reasoning, int req, int valid, int total, int validSrc,
                                int withNum, int allNum) {
        return new RunRecord(run, true, null, ms, in, out, reasoning, null, true, req, valid, valid, total, validSrc, total - validSrc,
                total - validSrc, null, withNum, allNum, withNum - allNum, List.of());
    }

    @Test
    void aggregate_중앙값_평균_비율() {
        List<RunRecord> runs = List.of(
                ok(1, 1000, 100, 50, null, 10, 10, 20, 18, 5, 4),
                ok(2, 3000, 200, 150, null, 10, 8, 10, 10, 5, 5),
                ok(3, 2000, 300, 100, null, 10, 9, 10, 5, 0, 0),
                new RunRecord(4, false, "HTTP_503", 500, null, null, null, null, false, 10, 0, 0, 0, 0, 0, 0, null, 0, 0, 0, List.of()));

        ExperimentStats.Aggregate a = ExperimentStats.aggregate(runs);
        assertEquals(4, a.runs());
        assertEquals(3, a.callOk());
        assertEquals(3, a.jsonParsed());
        assertEquals(2000L, a.medianMs());
        assertEquals(1000L, a.minMs());
        assertEquals(3000L, a.maxMs());
        assertEquals(200.0, a.avgInputTokens());
        assertEquals(100.0, a.avgOutputTokens());
        assertNull(a.avgReasoningTokens());
        assertNull(a.avgCostUsd());
        assertEquals(40, a.issuesTotal());
        assertEquals(33, a.issuesWithValidSources());
        assertEquals(0.825, a.sourceValidRate());
        assertEquals(30, a.stocksRequested());
        assertEquals(27, a.stocksValid());
        assertEquals(10, a.issuesWithNumbers());
        assertEquals(9, a.issuesAllNumbersConfirmed());
        assertEquals(0.9, a.numericMatchRate());
    }

    @Test
    void aggregate_성공이_없으면_null() {
        ExperimentStats.Aggregate a = ExperimentStats.aggregate(List.of(
                new RunRecord(1, false, "HTTP_401", 10, null, null, null, null, false, 10, 0, 0, 0, 0, 0, 0, null, 0, 0, 0, List.of())));
        assertEquals(1, a.runs());
        assertEquals(0, a.callOk());
        assertNull(a.medianMs());
        assertNull(a.sourceValidRate());
        assertNull(a.numericMatchRate());
    }

    @Test
    void median_짝수_개() {
        assertEquals(1500L, ExperimentStats.median(List.of(1000L, 2000L)));
        assertNull(ExperimentStats.median(List.of()));
    }

    @Test
    void 가격표_해석과_비용_계산() throws Exception {
        String json = "{\"claude:claude-haiku-5-5\":{\"inputPerMTok\":0.10,\"outputPerMTok\":0.50,\"source\":\"https://x\",\"checkedAt\":\"2026-11-18\"},"
                + "\"bad:model\":{\"inputPerMTok\":\"?\"}}";
        Map<String, ExperimentStats.Price> prices = ExperimentStats.parsePrices(new ObjectMapper(),
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, prices.size());
        ExperimentStats.Price p = prices.get("claude:claude-haiku-5-5");
        // 100,000 입력 * 0.10 / 1M + 10,000 출력 * 0.50 / 1M = 0.01 + 0.005
        assertEquals(0.015, ExperimentStats.cost(p, 100_000, 10_000), 1e-9);
        assertNull(ExperimentStats.cost(null, 1, 1));
        assertNull(ExperimentStats.cost(p, null, 1));
        assertTrue(ExperimentStats.parsePrices(new ObjectMapper(), null).isEmpty());
    }

    @Test
    void 번들된_가격표는_기본_실험_대상의_단가를_모두_담고_해석된다() throws Exception {
        try (var in = getClass().getResourceAsStream("/model-prices.json")) {
            Map<String, ExperimentStats.Price> prices = ExperimentStats.parsePrices(new ObjectMapper(), in);
            // 기본 실험 대상은 모두 가격이 있어야 비용이 계산된다
            for (ModelSpec spec : ExperimentConfig.parseModels("")) {
                assertTrue(prices.containsKey(spec.key()), "가격 누락: " + spec.key());
            }
            assertEquals(0.10, prices.get("claude:claude-haiku-5-5").inputPerMTok());
            assertEquals(0.50, prices.get("claude:claude-haiku-5-5").outputPerMTok());
            assertEquals(0.10, prices.get("openai:gpt-6-luna").inputPerMTok());
            assertEquals(0.40, prices.get("gemini:gemini-2.5-flash-lite").outputPerMTok());
        }
    }
}
