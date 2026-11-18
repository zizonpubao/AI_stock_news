package com.stocknews.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocknews.entity.AnalysisStatus;
import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.Stock;
import com.stocknews.repository.StockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 제공자 체인(Claude → Gemini), 프롬프트 지침, 수치 근거 측정. 네트워크 호출 없이 callClaude·callModel 을 가짜로 바꿔 검증한다. */
class AnalysisChainTest {

    static final String CLAUDE_MODEL = "claude-haiku-5-5";
    static final String GEMINI_MODEL = "gemini-3.5-flash-lite";

    StockRepository repo = mock(StockRepository.class);
    List<String> calls = new ArrayList<>();

    static final String GOOD = """
            {"stocks":[{"stockIndex":1,"sentiment":"POSITIVE",
              "issues":[{"text":"영업이익 9.1조 기록","sources":[1]}],
              "positives":["수요 증가"],"caution":"환율 변동","risks":["원가 상승"]}]}""";

    /**
     * @param chain       analysis.models 값 (null 이면 설정하지 않은 것과 같다)
     * @param claudeKey   Anthropic 키 ("" 이면 없음)
     * @param geminiKey   Gemini 키
     * @param claude      Claude 호출 가짜 (모델 → 응답, 예외를 던지면 실패)
     * @param gemini      Gemini 호출 가짜
     */
    GeminiAnalysisService service(String chain, String claudeKey, String geminiKey,
                                  Function<String, GeminiAnalysisService.ModelResponse> claude,
                                  Function<String, GeminiAnalysisService.ModelResponse> gemini) {
        GeminiAnalysisService s = new GeminiAnalysisService(repo, new ObjectMapper()) {
            @Override
            ModelResponse callClaude(String model, String prompt) {
                calls.add("claude:" + model);
                return claude.apply(model);
            }

            @Override
            ModelResponse callModel(String model, Map<String, Object> requestBody) {
                calls.add("gemini:" + model);
                return gemini.apply(model);
            }
        };
        ReflectionTestUtils.setField(s, "analysisModels", chain);
        ReflectionTestUtils.setField(s, "anthropicApiKey", claudeKey);
        ReflectionTestUtils.setField(s, "geminiApiKey", geminiKey);
        return s;
    }

    static GeminiAnalysisService.ModelResponse ok(String json) {
        return new GeminiAnalysisService.ModelResponse(json, 1000, 500, null);
    }

    static Function<String, GeminiAnalysisService.ModelResponse> fail(String code) {
        return m -> { throw new ClaudeAnalysisClient.CallException(code, null, null); };
    }

    static Stock stock(Long id, String name) {
        Stock s = new Stock();
        s.setId(id);
        s.setName(name);
        s.setCode("00000" + id);
        return s;
    }

    static List<NewsArticle> news(String... titles) {
        List<NewsArticle> list = new ArrayList<>();
        for (String t : titles) {
            NewsArticle a = new NewsArticle();
            a.setTitle(t);
            a.setDescription("");
            list.add(a);
        }
        return list;
    }

    GeminiAnalysisService.AnalysisSummary run(GeminiAnalysisService s, Stock stock) {
        return s.analyzeAllStocks(List.of(stock), Map.of(stock.getId(), news("삼성전자 영업이익 9.1조", "기사 2")));
    }

    // ===== 체인 =====

    @Test
    @DisplayName("주 제공자가 성공하면 예비 제공자는 부르지 않고 주 모델 이름이 저장된다")
    void primarySuccessDoesNotCallFallback() {
        Stock st = stock(1L, "가나전자");
        var s = service(null, "claude-key", "gemini-key", m -> ok(GOOD), fail("UNUSED"));

        var summary = run(s, st);

        assertThat(calls).containsExactly("claude:" + CLAUDE_MODEL);
        assertThat(summary.model()).isEqualTo(CLAUDE_MODEL);
        assertThat(summary.success()).isEqualTo(1);
        assertThat(st.getAnalysisModel()).isEqualTo(CLAUDE_MODEL);
        verify(repo).updateAnalysis(eq(1L), notNull(), eq(AnalysisStatus.SUCCESS), eq(CLAUDE_MODEL), notNull(), eq("POSITIVE"), isNull());
    }

    @Test
    @DisplayName("주 제공자가 호출 오류·거절·잘림이면 예비로 넘어가고 저장되는 모델 이름은 예비 모델이다")
    void primaryCallFailureFallsBackToSecondary() {
        for (String code : List.of("HTTP_529", "REFUSAL", "MAX_TOKENS", "TIMEOUT")) {
            calls.clear();
            Stock st = stock(1L, "가나전자");
            var s = service(null, "claude-key", "gemini-key", fail(code), m -> ok(GOOD));

            var summary = run(s, st);

            assertThat(calls).as(code).containsExactly("claude:" + CLAUDE_MODEL, "gemini:" + GEMINI_MODEL);
            assertThat(summary.model()).as(code).isEqualTo(GEMINI_MODEL);
            assertThat(st.getAnalysisModel()).as(code).isEqualTo(GEMINI_MODEL);
            assertThat(st.getAnalysisStatus()).as(code).isEqualTo(AnalysisStatus.SUCCESS);
        }
    }

    @Test
    @DisplayName("주 제공자 응답이 JSON 이 아니면 예비로 넘어간다")
    void primaryNonJsonFallsBack() {
        Stock st = stock(1L, "가나전자");
        var s = service(null, "claude-key", "gemini-key", m -> ok("죄송합니다, 분석할 수 없습니다."), m -> ok(GOOD));

        var summary = run(s, st);

        assertThat(calls).containsExactly("claude:" + CLAUDE_MODEL, "gemini:" + GEMINI_MODEL);
        assertThat(summary.model()).isEqualTo(GEMINI_MODEL);
    }

    @Test
    @DisplayName("체인의 모든 모델이 실패하면 AI_CALL_FAILED 로 기록하고 대체 문구를 저장하지 않는다")
    void allFailedMarksAiCallFailed() {
        Stock st = stock(1L, "가나전자");
        var s = service(null, "claude-key", "gemini-key", fail("HTTP_500"), fail("HTTP_503"));

        var summary = run(s, st);

        assertThat(calls).containsExactly("claude:" + CLAUDE_MODEL, "gemini:" + GEMINI_MODEL);
        assertThat(summary.model()).isNull();
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.numbersTotal()).isNull();
        assertThat(st.getAiAnalysis()).isNull();
        assertThat(st.getAnalysisFailReason()).isEqualTo("AI_CALL_FAILED");
        verify(repo).updateAnalysis(eq(1L), isNull(), eq(AnalysisStatus.FAILED), isNull(), isNull(), isNull(), eq("AI_CALL_FAILED"));
    }

    // ===== 키 없는 제공자 =====

    @Test
    @DisplayName("Claude 키가 없으면 Claude 는 건너뛰고 Gemini 만 호출한다")
    void missingClaudeKeySkipsClaude() {
        Stock st = stock(1L, "가나전자");
        var s = service(null, "", "gemini-key", fail("UNUSED"), m -> ok(GOOD));

        var summary = run(s, st);

        assertThat(calls).containsExactly("gemini:" + GEMINI_MODEL);
        assertThat(summary.model()).isEqualTo(GEMINI_MODEL);
        assertThat(summary.success()).isEqualTo(1);
    }

    @Test
    @DisplayName("두 제공자 모두 키가 없으면 호출 없이 AI_CALL_FAILED 로 기록한다")
    void noKeysAtAllFailsWithoutCalls() {
        Stock st = stock(1L, "가나전자");
        var s = service(null, null, "  ", fail("UNUSED"), fail("UNUSED"));

        var summary = run(s, st);

        assertThat(calls).isEmpty();
        assertThat(summary.model()).isNull();
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(st.getAnalysisFailReason()).isEqualTo("AI_CALL_FAILED");
        verify(repo).updateAnalysis(eq(1L), isNull(), eq(AnalysisStatus.FAILED), isNull(), isNull(), isNull(), eq("AI_CALL_FAILED"));
    }

    // ===== 체인 설정 =====

    @Test
    @DisplayName("analysis.models 가 빈 문자열이면 기본 체인을 쓴다")
    void blankChainUsesDefault() {
        for (String blank : new String[]{"", "   ", null}) {
            var s = service(blank, "k", "k", m -> ok(GOOD), m -> ok(GOOD));
            assertThat(s.resolveChain()).containsExactly(
                    new GeminiAnalysisService.ChainEntry("claude", CLAUDE_MODEL),
                    new GeminiAnalysisService.ChainEntry("gemini", GEMINI_MODEL));
        }
    }

    @Test
    @DisplayName("analysis.models 로 순서와 모델을 바꿀 수 있다 (대소문자·공백 허용)")
    void customChainIsParsed() {
        var s = service(" Gemini:gemini-x , claude:claude-y ", "k", "k", m -> ok(GOOD), m -> ok(GOOD));
        assertThat(s.resolveChain()).containsExactly(
                new GeminiAnalysisService.ChainEntry("gemini", "gemini-x"),
                new GeminiAnalysisService.ChainEntry("claude", "claude-y"));
    }

    @Test
    @DisplayName("analysis.models 형식이 잘못되면 시작을 막지 않고 기본 체인으로 대체한다")
    void invalidChainFallsBackToDefault() {
        for (String bad : List.of("claude-haiku-5-5", "openai:gpt-x", "claude:", "claude:bad model", ",,", "claude:ok,oops")) {
            var s = service(bad, "k", "k", m -> ok(GOOD), m -> ok(GOOD));
            assertThat(s.resolveChain()).as(bad).extracting(GeminiAnalysisService.ChainEntry::model)
                    .containsExactly(CLAUDE_MODEL, GEMINI_MODEL);
        }
        assertThatThrownBy(() -> GeminiAnalysisService.parseChain("claude-haiku-5-5"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ===== 프롬프트 =====

    @Test
    @DisplayName("운영 프롬프트에는 수치 인용 지침이 정확히 한 번, 데이터 블록 뒤에 들어 있다")
    void productionPromptHasGuidelineOnce() {
        var s = service(null, "k", "k", m -> ok(GOOD), m -> ok(GOOD));
        String prompt = s.buildPrompt(List.of(stock(1L, "가나전자")), List.of(news("기사")));

        assertThat(prompt).endsWith(GeminiAnalysisService.NUMBERS_GUIDELINE);
        assertThat(prompt.split("\\[추가 지침\\]", -1)).hasSize(2);
        assertThat(prompt.indexOf("[추가 지침]")).isGreaterThan(prompt.indexOf("<<<뉴스 데이터 끝>>>"));
    }

    @Test
    @DisplayName("지침 문구는 검증된 원문과 한 글자도 다르지 않다")
    void guidelineTextIsUnchanged() {
        assertThat(GeminiAnalysisService.NUMBERS_GUIDELINE).isEqualTo(
                "\n[추가 지침]\n주요 이슈를 쓸 때, 근거로 인용하는 기사에 수치(금액, 비율, 수량, 순위, 날짜 등)가 있으면 그 수치를 문장에 그대로 포함하세요. "
                        + "기사에 없는 수치는 절대 쓰지 마세요. 수치가 없는 기사라면 수치 없이 쓰세요.");
    }

    @Test
    @DisplayName("BASE 프롬프트에는 지침이 없고 NUMBERS 프롬프트는 운영 프롬프트와 같다")
    void baseHasNoGuidelineAndNumbersEqualsProduction() {
        var s = service(null, "k", "k", m -> ok(GOOD), m -> ok(GOOD));
        List<Stock> stocks = List.of(stock(1L, "가나전자"));
        List<List<NewsArticle>> newsLists = List.of(news("기사 1", "기사 2"));

        String production = s.buildPrompt(stocks, newsLists);
        String base = PromptVariant.BASE.apply(s.buildPrompt(stocks, newsLists, false));
        String numbers = PromptVariant.NUMBERS.apply(s.buildPrompt(stocks, newsLists, false));

        assertThat(base).doesNotContain("[추가 지침]");
        assertThat(numbers).isEqualTo(production);
        assertThat(numbers.split("\\[추가 지침\\]", -1)).hasSize(2);
    }

    // ===== 수치 근거 측정 =====

    @Test
    @DisplayName("이슈 문장의 숫자 총수와 확인되지 않은 숫자 수가 요약에 담기고 문장은 바뀌지 않는다")
    void numbersAreMeasuredAndTextIsUntouched() {
        // 문장 숫자: 9.1(기사 1에 있음), 15%(기사에 없음) → 총 2, 미확인 1
        String json = """
                {"stocks":[{"stockIndex":1,"sentiment":"POSITIVE",
                  "issues":[{"text":"영업이익 9.1조, 증가율 15%","sources":[1]}],
                  "positives":[],"caution":"","risks":[]}]}""";
        Stock st = stock(1L, "가나전자");
        var s = service(null, "k", "k", m -> ok(json), fail("UNUSED"));

        var summary = run(s, st);

        assertThat(summary.numbersTotal()).isEqualTo(2);
        assertThat(summary.numbersUnverified()).isEqualTo(1);
        // 확인되지 않은 숫자가 있어도 문장을 걸러내거나 고치지 않는다
        assertThat(st.getAiAnalysis()).contains("- 영업이익 9.1조, 증가율 15% [1]");
        assertThat(summary.success()).isEqualTo(1);
    }

    @Test
    @DisplayName("숫자가 없는 분석은 총수 0, 미확인 0 으로 기록한다")
    void noNumbersMeansZero() {
        Stock st = stock(1L, "가나전자");
        String json = """
                {"stocks":[{"stockIndex":1,"sentiment":"NEUTRAL",
                  "issues":[{"text":"신제품 공개","sources":[1]}],
                  "positives":[],"caution":"","risks":[]}]}""";
        var summary = run(service(null, "k", "k", m -> ok(json), fail("UNUSED")), st);

        assertThat(summary.numbersTotal()).isZero();
        assertThat(summary.numbersUnverified()).isZero();
    }

    @Test
    @DisplayName("분석을 호출하지 않으면 수치 측정값은 null 이다")
    void noCallMeansNullNumbers() {
        var s = service(null, "k", "k", fail("UNUSED"), fail("UNUSED"));
        var summary = s.analyzeAllStocks(List.of(stock(1L, "가나전자")), Map.of());

        assertThat(calls).isEmpty();
        assertThat(summary.numbersTotal()).isNull();
        assertThat(summary.numbersUnverified()).isNull();
        verify(repo, never()).updateAnalysis(any(), any(), eq(AnalysisStatus.SUCCESS), any(), any(), any(), any());
    }
}
