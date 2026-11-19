package com.stocknews.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.Stock;
import com.stocknews.repository.StockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 기사 본문 입력 모델 비교 실험: 모델 목록 해석, 호출 순서와 집계, 키 없는 제공자 건너뜀, 기사 글 내보내기, OpenAI 요청·응답. 네트워크 호출 없음. */
class ModelCompareTest {

    StockRepository repo = mock(StockRepository.class);
    List<String> callLog = new ArrayList<>();

    static Stock stock() {
        Stock s = new Stock();
        s.setId(1L);
        s.setName("가나전자");
        s.setCode("000001");
        return s;
    }

    static List<NewsArticle> news() {
        NewsArticle a = new NewsArticle();
        a.setTitle("가나전자 영업이익 9.1조");
        a.setDescription("잘린 요약...");
        a.setBody("영업이익은 9.1조 원이다.");
        NewsArticle b = new NewsArticle();
        b.setTitle("다른 소식");
        b.setDescription("매출 5조");
        return List.of(a, b);
    }

    /** 이슈 1: 9.1 은 기사 1 에만 있는데 기사 2 를 인용 (OTHER_ARTICLE). 이슈 2: 숫자 없음. */
    static final String RESPONSE = """
            {"stocks":[{"stockIndex":1,"sentiment":"POSITIVE",
              "issues":[{"text":"영업이익 9.1조 기록","sources":[2]},
                        {"text":"신제품 공개","sources":[1]}],
              "positives":["수요"],"caution":"환율","risks":["원가"]}]}""";

    GeminiAnalysisService service(String openaiKey) {
        GeminiAnalysisService s = new GeminiAnalysisService(repo, new ObjectMapper()) {
            @Override
            ModelResponse callClaude(String model, String prompt) {
                callLog.add("claude:" + model);
                return new ModelResponse(RESPONSE, 1000, 500, null);
            }

            @Override
            ModelResponse callModel(String model, Map<String, Object> requestBody) {
                callLog.add("gemini:" + model);
                if (callLog.size() == 1) throw new ClaudeAnalysisClient.CallException("HTTP_429", null, null);
                return new ModelResponse(RESPONSE, 900, 300, 200);
            }

            @Override
            ModelResponse callOpenAi(String model, String prompt) {
                callLog.add("openai:" + model);
                return new ModelResponse(RESPONSE, 800, 400, null);
            }
        };
        ReflectionTestUtils.setField(s, "analysisModels", null);
        ReflectionTestUtils.setField(s, "anthropicApiKey", "claude-key");
        ReflectionTestUtils.setField(s, "geminiApiKey", "gemini-key");
        ReflectionTestUtils.setField(s, "openaiApiKey", openaiKey);
        return s;
    }

    @Test
    @DisplayName("모델 목록: 비우면 기본 4개, 형식·제공자·개수·중복이 틀리면 거부")
    void parseModels() {
        assertThat(ModelCompareResult.parseModels(null)).isEqualTo(ModelCompareResult.DEFAULT_MODELS);
        assertThat(ModelCompareResult.parseModels("  ")).hasSize(4);
        assertThat(ModelCompareResult.parseModels(" Claude:claude-haiku-5-5 , gemini:gemini-3.5-flash-lite"))
                .containsExactly("claude:claude-haiku-5-5", "gemini:gemini-3.5-flash-lite");
        assertThatThrownBy(() -> ModelCompareResult.parseModels("claude-haiku-5-5")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelCompareResult.parseModels("upstage:solar")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelCompareResult.parseModels("gemini:../x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelCompareResult.parseModels("gemini:a,gemini:a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelCompareResult.parseModels("gemini:a,gemini:b,gemini:c,gemini:d,gemini:e,gemini:f"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("회차마다 목록 순서로 부르고, 실패 호출은 실패로 남기며, 모델별로 집계하고, 저장하지 않는다")
    void orderTotalsAndNoSave() {
        List<String> models = List.of("gemini:gemini-2.5-flash", "claude:claude-haiku-5-5", "openai:gpt-6-luna");

        ModelCompareResult r = service("openai-key").compareModels(List.of(stock()), Map.of(1L, news()), 2, models, 0, false);

        assertThat(callLog).containsExactly("gemini:gemini-2.5-flash", "claude:claude-haiku-5-5", "openai:gpt-6-luna",
                "gemini:gemini-2.5-flash", "claude:claude-haiku-5-5", "openai:gpt-6-luna");
        assertThat(r.calls()).hasSize(6);
        assertThat(r.calls().get(0).ok()).isFalse();
        assertThat(r.calls().get(0).errorCode()).isEqualTo("HTTP_429");

        ModelCompareResult.ModelTotals gemini = r.models().get("gemini:gemini-2.5-flash");
        assertThat(gemini.callsOk()).isEqualTo(1);
        assertThat(gemini.callsFailed()).isEqualTo(1);
        assertThat(gemini.meanOutputTokens()).isEqualTo(500.0); // 응답 300 + 생각 200

        ModelCompareResult.ModelTotals claude = r.models().get("claude:claude-haiku-5-5");
        assertThat(claude.skipped()).isFalse();
        assertThat(claude.callsOk()).isEqualTo(2);
        assertThat(claude.issues()).isEqualTo(4);
        assertThat(claude.issuesWithNumbers()).isEqualTo(2);
        assertThat(claude.citations()).isEqualTo(4);
        assertThat(claude.numbersTotal()).isEqualTo(2);
        assertThat(claude.unverified()).isEqualTo(2);
        assertThat(claude.otherArticle()).isEqualTo(2);
        assertThat(claude.notInAny()).isZero();

        ModelCompareResult.Call ok = r.calls().get(1);
        assertThat(ok.outputs()).extracting(ModelCompareResult.Output::issue).containsExactly("영업이익 9.1조 기록", "신제품 공개");
        assertThat(ok.outputs().get(0).sources()).containsExactly(2);
        assertThat(ok.details()).hasSize(1);
        assertThat(ok.details().get(0).category()).isEqualTo("OTHER_ARTICLE");

        assertThat(r.input().stocks()).isEqualTo(1);
        assertThat(r.input().articles()).isEqualTo(2);
        assertThat(r.input().articlesWithBody()).isEqualTo(1);
        assertThat(r.input().articleTexts()).isNull();
        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("키가 없는 제공자의 모델은 부르지 않고 건너뜀으로 표시한다")
    void providerWithoutKeyIsSkipped() {
        ModelCompareResult r = service("").compareModels(List.of(stock()), Map.of(1L, news()), 1,
                List.of("claude:claude-haiku-5-5", "openai:gpt-6-luna"), 0, false);

        assertThat(callLog).containsExactly("claude:claude-haiku-5-5");
        assertThat(r.models().get("openai:gpt-6-luna").skipped()).isTrue();
        assertThat(r.models().get("claude:claude-haiku-5-5").skipped()).isFalse();
        assertThat(r.calls()).hasSize(1);
    }

    @Test
    @DisplayName("기사 글은 includeInput 일 때만 결과에 실리고, 모델이 본 글(본문 또는 요약)과 같으며, 보관용 사본에서는 빠진다")
    void includeInputCarriesShownText() {
        List<NewsArticle> news = news();
        ModelCompareResult r = service("").compareModels(List.of(stock()), Map.of(1L, news), 1,
                List.of("claude:claude-haiku-5-5"), 0, true);

        List<ModelCompareResult.Article> texts = r.input().articleTexts();
        assertThat(texts).hasSize(2);
        assertThat(texts.get(0).newsIndex()).isEqualTo(1);
        assertThat(texts.get(0).hasBody()).isTrue();
        assertThat(texts.get(0).text()).isEqualTo(GeminiAnalysisService.shownText(news.get(0))).isEqualTo("영업이익은 9.1조 원이다.");
        assertThat(texts.get(1).hasBody()).isFalse();
        assertThat(texts.get(1).text()).isEqualTo("매출 5조");
        assertThat(r.withoutArticleTexts().input().articleTexts()).isNull();
        assertThat(r.withoutArticleTexts().calls()).isEqualTo(r.calls());
    }

    @Test
    @DisplayName("OpenAI 요청: strict json_schema, 모든 object 에 additionalProperties=false, 샘플링 파라미터 없음")
    @SuppressWarnings("unchecked")
    void openAiRequestBody() {
        Map<String, Object> body = OpenAiAnalysisClient.buildBody("gpt-6-luna", "프롬프트");

        assertThat(body).containsOnlyKeys("model", "messages", "response_format", "max_completion_tokens");
        assertThat(body.get("model")).isEqualTo("gpt-6-luna");
        assertThat(body.get("messages")).isEqualTo(List.of(Map.of("role", "user", "content", "프롬프트")));
        Map<String, Object> format = (Map<String, Object>) body.get("response_format");
        assertThat(format.get("type")).isEqualTo("json_schema");
        Map<String, Object> js = (Map<String, Object>) format.get("json_schema");
        assertThat(js.get("strict")).isEqualTo(true);
        assertNoAdditional(js.get("schema"));
    }

    @SuppressWarnings("unchecked")
    private static void assertNoAdditional(Object node) {
        if (node instanceof Map<?, ?> m) {
            if ("object".equals(m.get("type"))) assertThat(m.get("additionalProperties")).isEqualTo(false);
            for (Object v : ((Map<String, Object>) m).values()) assertNoAdditional(v);
        } else if (node instanceof List<?> l) {
            for (Object v : l) assertNoAdditional(v);
        }
    }

    @Test
    @DisplayName("OpenAI 응답: 내용과 토큰을 읽고, 거절·잘림·빈 내용은 실패 코드로 던진다")
    void openAiResponseParsing() {
        ObjectMapper m = new ObjectMapper();
        GeminiAnalysisService.ModelResponse ok = OpenAiAnalysisClient.parse(m,
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{}\"}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}");
        assertThat(ok.text()).isEqualTo("{}");
        assertThat(ok.promptTokens()).isEqualTo(10);
        assertThat(ok.outputTokens()).isEqualTo(20);

        assertThatThrownBy(() -> OpenAiAnalysisClient.parse(m,
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{\"}}]}"))
                .isInstanceOf(ClaudeAnalysisClient.CallException.class);
        assertThatThrownBy(() -> OpenAiAnalysisClient.parse(m,
                "{\"choices\":[{\"message\":{\"refusal\":\"no\",\"content\":null}}]}"))
                .isInstanceOf(ClaudeAnalysisClient.CallException.class);
        assertThatThrownBy(() -> OpenAiAnalysisClient.parse(m, "{\"choices\":[]}"))
                .isInstanceOf(ClaudeAnalysisClient.CallException.class);
        assertThatThrownBy(() -> OpenAiAnalysisClient.parse(m, "not json"))
                .isInstanceOf(ClaudeAnalysisClient.CallException.class);
    }
}
