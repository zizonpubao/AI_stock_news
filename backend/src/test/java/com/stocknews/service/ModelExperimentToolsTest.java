package com.stocknews.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocknews.service.ExperimentConfig.ModelSpec;
import com.stocknews.service.ExperimentStats.RunRecord;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;
import com.stocknews.service.ProviderClient.ProviderResult;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
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
                total - validSrc, null, withNum, allNum, withNum - allNum, List.of(),
                "SCHEMA", List.of("SCHEMA:OK"), false);
    }

    @Test
    void aggregate_중앙값_평균_비율() {
        List<RunRecord> runs = List.of(
                ok(1, 1000, 100, 50, null, 10, 10, 20, 18, 5, 4),
                ok(2, 3000, 200, 150, null, 10, 8, 10, 10, 5, 5),
                ok(3, 2000, 300, 100, null, 10, 9, 10, 5, 0, 0),
                new RunRecord(4, false, "HTTP_503", 500, null, null, null, null, false, 10, 0, 0, 0, 0, 0, 0, null, 0, 0, 0, List.of(), "SCHEMA", List.of("SCHEMA:HTTP_503"), false));

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
                new RunRecord(1, false, "HTTP_401", 10, null, null, null, null, false, 10, 0, 0, 0, 0, 0, 0, null, 0, 0, 0, List.of(), "SCHEMA", List.of("SCHEMA:HTTP_503"), false)));
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

    // ===== 모델 ID 검증 =====

    @Test
    void 모델ID_슬래시는_요청_본문에_넣는_제공자만_허용() {
        assertTrue(ExperimentConfig.isValidModelId("groq", "openai/gpt-oss-20b"));
        assertTrue(ExperimentConfig.isValidModelId("openai", "a/b"));
        assertTrue(ExperimentConfig.isValidModelId("upstage", "solar-mini4"));
        assertTrue(ExperimentConfig.isValidModelId("clova", "HCX-007"));
        assertTrue(ExperimentConfig.isValidModelId("gemini", "gemma-4-26b-a4b-it"));
        assertFalse(ExperimentConfig.isValidModelId("gemini", "a/b")); // URL 경로에 들어간다
        assertFalse(ExperimentConfig.isValidModelId("groq", "/openai/x"));
        assertFalse(ExperimentConfig.isValidModelId("groq", "openai/x/"));
        assertFalse(ExperimentConfig.isValidModelId("groq", "openai/../x"));
        assertFalse(ExperimentConfig.isValidModelId("groq", "a..b"));
        assertFalse(ExperimentConfig.isValidModelId("groq", "a b"));
        assertFalse(ExperimentConfig.isValidModelId("groq", "a?x=1"));
        assertFalse(ExperimentConfig.isValidModelId("groq", "a%2fb"));
        assertFalse(ExperimentConfig.isValidModelId("groq", ""));
        assertFalse(ExperimentConfig.isValidModelId("groq", "x".repeat(65)));
    }

    @Test
    void parseModels_새_제공자와_슬래시_모델() {
        List<ModelSpec> specs = ExperimentConfig.parseModels(
                "gemini:gemma-4-31b-it,upstage:solar-mini4,clova:HCX-007,groq:openai/gpt-oss-20b,deepseek:deepseek-flash,claude:claude-haiku-5-5,openai:gpt-6-luna,gemini:gemini-2.5-flash");
        assertEquals(8, specs.size());
        assertEquals("groq:openai/gpt-oss-20b", specs.get(3).key());
        assertThrows(IllegalArgumentException.class, () -> ExperimentConfig.parseModels("gemini:openai/x"));
        assertThrows(IllegalArgumentException.class, () -> ExperimentConfig.parseModels("groq:openai/../x"));
        assertThrows(IllegalArgumentException.class, () -> ExperimentConfig.parseModels("groq:/x"));
    }

    // ===== 형식 강제 내려가기 =====

    private static ProviderResult okResult() { return new ProviderResult("{}", 1, 1, null, null); }

    private static final List<FormatLevel> FULL = List.of(FormatLevel.SCHEMA_STRICT, FormatLevel.SCHEMA, FormatLevel.JSON_MODE, FormatLevel.PROMPT_ONLY);

    @Test
    void 내려가기_판단은_400_계열만() {
        assertTrue(FormatLevel.isFormatRejection("HTTP_400"));
        assertTrue(FormatLevel.isFormatRejection("HTTP_422"));
        for (String code : Arrays.asList("HTTP_401", "HTTP_403", "HTTP_404", "HTTP_429", "RATE_LIMIT", "HTTP_500", "HTTP_503",
                "TIMEOUT", "CONNECTION_ERROR", "HTTP_400_AUTH", "EMPTY_RESPONSE", "MAX_TOKENS", "REFUSAL", "INVALID_JSON", null)) {
            assertFalse(FormatLevel.isFormatRejection(code), String.valueOf(code));
        }
    }

    @Test
    void 내려가기는_한_단계씩_이력을_남긴다() {
        List<FormatLevel> tried = new ArrayList<>();
        FormatControl.Outcome o = FormatControl.execute(FULL, level -> {
            tried.add(level);
            return level == FormatLevel.JSON_MODE ? okResult() : ProviderResult.failure("HTTP_400");
        });
        assertEquals(List.of(FormatLevel.SCHEMA_STRICT, FormatLevel.SCHEMA, FormatLevel.JSON_MODE), tried);
        assertEquals(FormatLevel.JSON_MODE, o.level());
        assertEquals(List.of("SCHEMA_STRICT:HTTP_400", "SCHEMA:HTTP_400", "JSON_MODE:OK"), o.attempts());
        assertNull(o.result().errorCode());
    }

    @Test
    void 내려가기_인증_404_429_5xx_타임아웃은_그대로_끝() {
        for (String code : List.of("HTTP_401", "HTTP_403", "HTTP_404", "HTTP_429", "HTTP_500", "HTTP_503", "TIMEOUT", "HTTP_400_AUTH")) {
            int[] calls = {0};
            FormatControl.Outcome o = FormatControl.execute(FULL, level -> { calls[0]++; return ProviderResult.failure(code); });
            assertEquals(1, calls[0], code);
            assertEquals(List.of("SCHEMA_STRICT:" + code), o.attempts());
            assertEquals(code, o.result().errorCode());
        }
    }

    @Test
    void 내려가기_마지막_수준도_거부하면_거부_결과를_그대로() {
        FormatControl.Outcome o = FormatControl.execute(List.of(FormatLevel.JSON_MODE, FormatLevel.PROMPT_ONLY),
                level -> ProviderResult.failure("HTTP_400"));
        assertEquals(FormatLevel.PROMPT_ONLY, o.level());
        assertEquals(List.of("JSON_MODE:HTTP_400", "PROMPT_ONLY:HTTP_400"), o.attempts());
        assertEquals("HTTP_400", o.result().errorCode());
        // 한 수준뿐이면 내려가지 않는다
        int[] calls = {0};
        FormatControl.execute(List.of(FormatLevel.SCHEMA), level -> { calls[0]++; return ProviderResult.failure("HTTP_400"); });
        assertEquals(1, calls[0]);
    }

    @Test
    void 수준별_프롬프트_덧붙임과_코드펜스_제거_대상() {
        assertFalse(FormatLevel.SCHEMA_STRICT.appendsSchemaNote());
        assertFalse(FormatLevel.SCHEMA.appendsSchemaNote());
        assertTrue(FormatLevel.JSON_MODE.appendsSchemaNote());
        assertTrue(FormatLevel.PROMPT_ONLY.appendsSchemaNote());
        assertFalse(FormatLevel.SCHEMA.unwrapsResponse());
        assertTrue(FormatLevel.JSON_MODE.unwrapsResponse());
        assertTrue(FormatLevel.PROMPT_ONLY.unwrapsResponse());
    }

    @Test
    void 스키마_요약_문구는_고정되고_JSON_단어를_포함() {
        String a = FormatControl.schemaNote(GeminiAnalysisService.responseSchema());
        String b = FormatControl.schemaNote(GeminiAnalysisService.responseSchema());
        assertEquals(a, b);
        assertTrue(a.contains("JSON"));
        assertTrue(a.contains("\"stockIndex\"") && a.contains("\"sources\""));
    }

    // ===== 응답에서 JSON 뽑기 =====

    @Test
    void extractJson_이미_JSON_이면_그대로() {
        var r = FormatControl.extractJson("  {\"a\": [1, 2]}\n");
        assertEquals("{\"a\": [1, 2]}", r.json());
        assertFalse(r.unwrapped());
        assertFalse(FormatControl.extractJson("[1,2]").unwrapped());
    }

    @Test
    void extractJson_코드펜스와_앞뒤_문장을_걷어낸다() {
        var fenced = FormatControl.extractJson("```json\n{\"stocks\": []}\n```");
        assertEquals("{\"stocks\": []}", fenced.json());
        assertTrue(fenced.unwrapped());

        var prose = FormatControl.extractJson("결과입니다.\n```\n{\"a\":1}\n```\n이상입니다.");
        assertEquals("{\"a\":1}", prose.json());
        assertTrue(prose.unwrapped());

        var plain = FormatControl.extractJson("다음과 같습니다: {\"a\":{\"b\":[1]}} 끝.");
        assertEquals("{\"a\":{\"b\":[1]}}", plain.json());
        assertTrue(plain.unwrapped());

        var unclosed = FormatControl.extractJson("```json\n{\"a\":1}"); // 닫는 펜스 없음
        assertEquals("{\"a\":1}", unclosed.json());
        assertTrue(unclosed.unwrapped());
    }

    @Test
    void extractJson_문자열_안의_괄호와_이스케이프를_무시한다() {
        var r = FormatControl.extractJson("앞 {\"t\": \"} ] \\\" {\", \"n\": 1} 뒤");
        assertEquals("{\"t\": \"} ] \\\" {\", \"n\": 1}", r.json());
        assertTrue(r.unwrapped());
    }

    @Test
    void extractJson_JSON_이_아닌_대괄호는_건너뛰고_첫_유효_JSON_을_고른다() {
        var r = FormatControl.extractJson("[참고] 아래입니다 {\"a\":1}");
        assertEquals("{\"a\":1}", r.json());
        assertTrue(r.unwrapped());
    }

    @Test
    void extractJson_뽑을_수_없으면_원문_그대로() {
        assertEquals("그냥 문장", FormatControl.extractJson("그냥 문장").json());
        assertFalse(FormatControl.extractJson("그냥 문장").unwrapped());
        assertEquals("{\"a\": ", FormatControl.extractJson("{\"a\": ").json()); // 짝이 안 맞음 → 호출 측 검증이 실패하게 둔다
        assertNull(FormatControl.extractJson(null).json());
    }

    // ===== Gemini 요청 본문 조정 =====

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cfgOf(Map<String, Object> body) {
        return (Map<String, Object>) body.get("generationConfig");
    }

    @Test
    void Gemini_수준별_본문_조정과_Gemma_temperature_제외() {
        GeminiAnalysisService svc = new GeminiAnalysisService(null, new ObjectMapper());
        Map<String, Object> prod = svc.buildRequestBody("gemini-2.5-flash", "p");
        assertTrue(cfgOf(prod).containsKey("temperature"));

        Map<String, Object> schema = GeminiProviderClient.adjustBody(prod, "gemini-2.5-flash", FormatLevel.SCHEMA);
        assertTrue(cfgOf(schema).containsKey("responseJsonSchema") && cfgOf(schema).containsKey("responseMimeType"));
        assertTrue(cfgOf(schema).containsKey("temperature")); // Gemini 는 운영 그대로

        Map<String, Object> json = GeminiProviderClient.adjustBody(prod, "gemini-2.5-flash", FormatLevel.JSON_MODE);
        assertFalse(cfgOf(json).containsKey("responseJsonSchema"));
        assertEquals("application/json", cfgOf(json).get("responseMimeType"));

        Map<String, Object> prompt = GeminiProviderClient.adjustBody(prod, "gemini-2.5-flash", FormatLevel.PROMPT_ONLY);
        assertFalse(cfgOf(prompt).containsKey("responseJsonSchema"));
        assertFalse(cfgOf(prompt).containsKey("responseMimeType"));
        assertEquals(16000, cfgOf(prompt).get("maxOutputTokens"));

        // 운영 본문(원본)은 바뀌지 않는다
        assertTrue(cfgOf(prod).containsKey("responseJsonSchema"));

        Map<String, Object> gemmaProd = svc.buildRequestBody("gemma-4-31b-it", "p");
        assertTrue(cfgOf(gemmaProd).containsKey("temperature")); // 운영 규칙은 gemini-3·4 가 아니면 0.3 을 보낸다
        assertFalse(cfgOf(GeminiProviderClient.adjustBody(gemmaProd, "gemma-4-31b-it", FormatLevel.SCHEMA)).containsKey("temperature"));
        assertTrue(cfgOf(gemmaProd).containsKey("temperature")); // 원본 유지
    }

    @Test
    void Gemini_잘못된_키_400_은_형식_거부로_보지_않는다() {
        var invalidKey = WebClientResponseException.create(400, "Bad Request", new HttpHeaders(),
                "{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertEquals("HTTP_400_AUTH", GeminiProviderClient.classify(new RuntimeException(invalidKey)));
        var other = WebClientResponseException.create(400, "Bad Request", new HttpHeaders(),
                "{\"error\":\"JSON mode is not enabled\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertEquals("HTTP_400", GeminiProviderClient.classify(other));
        assertTrue(FormatLevel.isFormatRejection(GeminiProviderClient.classify(other)));
        assertFalse(FormatLevel.isFormatRejection(GeminiProviderClient.classify(invalidKey)));
    }

    // ===== OpenAI 호환 호출 (로컬 가짜 서버 — 외부로 나가지 않는다) =====

    /**
     * openai-java 는 experimentImplementation 이라 기본 test 클래스패스에 없다. 없으면 이 로컬 서버 테스트는 건너뛴다
     * (build.gradle 에 testRuntimeOnly sourceSets.experiment.runtimeClasspath 를 더하면 실행된다).
     */
    private static void assumeSdkOnTestClasspath() {
        boolean present;
        try {
            Class.forName("com.openai.client.okhttp.OpenAIOkHttpClient");
            present = true;
        } catch (ClassNotFoundException e) {
            present = false;
        }
        Assumptions.assumeTrue(present, "openai-java 가 test 클래스패스에 없음");
    }

    /** 요청 하나의 기록 */
    private record Seen(String path, String auth, String body) {}

    private static HttpServer fakeServer(List<Seen> seen, Function<String, Integer> statusFor, String okContent) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(new Seen(ex.getRequestURI().getPath(), ex.getRequestHeaders().getFirst("Authorization"), body));
            int status = statusFor.apply(body);
            String resp = status == 200
                    ? "{\"id\":\"x\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
                      + "\"message\":{\"role\":\"assistant\",\"content\":" + new ObjectMapper().writeValueAsString(okContent) + "}}],"
                      + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}"
                    : "{\"error\":{\"message\":\"x\"}}";
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
        return server;
    }

    private static OpenAiProviderClient clientFor(HttpServer server, String basePath, List<FormatLevel> ladder) {
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + basePath;
        return new OpenAiProviderClient(new CompatProviders.Def("test", base, "TEST_KEY", ladder, ""), "test-token-value");
    }

    @Test
    void 호환_제공자는_baseUrl_로_호출하고_수준별_response_format_을_보낸다() throws Exception {
        assumeSdkOnTestClasspath();
        List<Seen> seen = new CopyOnWriteArrayList<>();
        HttpServer server = fakeServer(seen, b -> 200, "{\"stocks\":[]}");
        try {
            // 키가 없으면 호출하지 않는다
            assertFalse(new OpenAiProviderClient(CompatProviders.COMPATIBLE.get(0), null).isConfigured());
            assertFalse(new OpenAiProviderClient(CompatProviders.COMPATIBLE.get(0), " ").isConfigured());
            // 끝에 '/' 가 있는 base URL (clova 형태)
            OpenAiProviderClient c = clientFor(server, "/v1/openai/", FULL);
            Map<String, Object> schema = GeminiAnalysisService.responseSchema();
            for (FormatLevel level : FULL) {
                ProviderResult r = c.call("m", "프롬프트", schema, level);
                assertNull(r.errorCode(), level.name());
                assertEquals("{\"stocks\":[]}", r.text());
                assertEquals(10, r.inputTokens());
            }
            assertEquals(4, seen.size());
            for (Seen s : seen) {
                assertEquals("/v1/openai/chat/completions", s.path());
                assertEquals("Bearer test-token-value", s.auth());
            }
            ObjectMapper m = new ObjectMapper();
            var strict = m.readTree(seen.get(0).body());
            assertEquals("json_schema", strict.at("/response_format/type").asText());
            assertTrue(strict.at("/response_format/json_schema/strict").asBoolean());
            assertEquals("stock_analysis", strict.at("/response_format/json_schema/name").asText());
            var schemaOnly = m.readTree(seen.get(1).body());
            assertEquals("json_schema", schemaOnly.at("/response_format/type").asText());
            assertTrue(schemaOnly.at("/response_format/json_schema/strict").isMissingNode());
            assertFalse(schemaOnly.at("/response_format/json_schema/name").isMissingNode()); // SDK 가 name 을 요구해 보낸다
            assertEquals("json_object", m.readTree(seen.get(2).body()).at("/response_format/type").asText());
            assertTrue(m.readTree(seen.get(3).body()).at("/response_format").isMissingNode());
            assertEquals("프롬프트", m.readTree(seen.get(3).body()).at("/messages/0/content").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 호환_제공자가_json_schema_를_400_으로_거부하면_JSON_모드까지_내려가고_펜스를_걷어낸다() throws Exception {
        assumeSdkOnTestClasspath();
        List<Seen> seen = new CopyOnWriteArrayList<>();
        HttpServer server = fakeServer(seen, b -> b.contains("json_schema") ? 400 : 200, "```json\n{\"stocks\":[]}\n```");
        try {
            OpenAiProviderClient c = clientFor(server, "/v1", FULL);
            FormatControl.Outcome o = FormatControl.execute(c.formatLadder("m"),
                    level -> c.call("m", "p", GeminiAnalysisService.responseSchema(), level));
            assertEquals(List.of("SCHEMA_STRICT:HTTP_400", "SCHEMA:HTTP_400", "JSON_MODE:OK"), o.attempts());
            assertEquals(FormatLevel.JSON_MODE, o.level());
            assertEquals(3, seen.size());
            var ex = FormatControl.extractJson(o.result().text());
            assertEquals("{\"stocks\":[]}", ex.json());
            assertTrue(ex.unwrapped());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 호환_제공자의_401_은_내려가지_않고_HTTP_401_로_기록된다() throws Exception {
        assumeSdkOnTestClasspath();
        List<Seen> seen = new CopyOnWriteArrayList<>();
        HttpServer server = fakeServer(seen, b -> 401, "");
        try {
            OpenAiProviderClient c = clientFor(server, "/v1", FULL);
            FormatControl.Outcome o = FormatControl.execute(c.formatLadder("m"),
                    level -> c.call("m", "p", GeminiAnalysisService.responseSchema(), level));
            assertEquals(List.of("SCHEMA_STRICT:HTTP_401"), o.attempts());
            assertEquals(1, seen.size());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 호환_제공자_표는_문서에서_확인된_값과_첫_수준을_가진다() {
        Map<String, CompatProviders.Def> byName = new LinkedHashMap<>();
        for (var d : CompatProviders.COMPATIBLE) byName.put(d.name(), d);
        assertEquals(List.of("upstage", "clova", "groq", "deepseek"), List.copyOf(byName.keySet()));
        assertEquals("https://api.upstage.ai/v1", byName.get("upstage").baseUrl());
        assertEquals("https://clovastudio.stream.ntruss.com/v1/openai/", byName.get("clova").baseUrl());
        assertEquals("https://api.groq.com/openai/v1", byName.get("groq").baseUrl());
        assertEquals("https://api.deepseek.com", byName.get("deepseek").baseUrl());
        assertEquals("UPSTAGE_API_KEY", byName.get("upstage").keyEnv());
        assertEquals("CLOVA_API_KEY", byName.get("clova").keyEnv());
        assertEquals("GROQ_API_KEY", byName.get("groq").keyEnv());
        assertEquals("DEEPSEEK_API_KEY", byName.get("deepseek").keyEnv());
        assertEquals(FormatLevel.SCHEMA_STRICT, byName.get("upstage").ladder().get(0));
        assertEquals(FormatLevel.SCHEMA, byName.get("clova").ladder().get(0));
        assertEquals(FormatLevel.SCHEMA_STRICT, byName.get("groq").ladder().get(0));
        assertEquals(FormatLevel.JSON_MODE, byName.get("deepseek").ladder().get(0));
        assertEquals(FormatLevel.PROMPT_ONLY, byName.get("deepseek").ladder().get(byName.get("deepseek").ladder().size() - 1));
    }

    @Test
    void 집계는_형식_강제_수준과_코드펜스_제거_횟수를_센다() {
        RunRecord a = new RunRecord(1, true, null, 10, 1, 1, null, null, true, 1, 1, 1, 1, 1, 0, 0, null, 0, 0, 0, List.of(),
                "JSON_MODE", List.of("SCHEMA_STRICT:HTTP_400", "JSON_MODE:OK"), true);
        RunRecord b = new RunRecord(2, true, null, 10, 1, 1, null, null, true, 1, 1, 1, 1, 1, 0, 0, null, 0, 0, 0, List.of(),
                "JSON_MODE", List.of("SCHEMA_STRICT:HTTP_400", "JSON_MODE:OK"), false);
        RunRecord c = new RunRecord(3, true, null, 10, 1, 1, null, null, true, 1, 1, 1, 1, 1, 0, 0, null, 0, 0, 0, List.of(),
                "SCHEMA_STRICT", List.of("SCHEMA_STRICT:OK"), false);
        ExperimentStats.Aggregate agg = ExperimentStats.aggregate(List.of(a, b, c));
        assertEquals(Map.of("JSON_MODE", 2, "SCHEMA_STRICT", 1), agg.formatLevels());
        assertEquals(1, agg.unwrappedRuns());
        assertEquals(2, agg.formatTrails().get("SCHEMA_STRICT:HTTP_400 > JSON_MODE:OK"));
        assertEquals(1, agg.formatTrails().get("SCHEMA_STRICT:OK"));
    }
}
