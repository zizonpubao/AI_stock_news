package com.stocknews.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.Stock;
import com.stocknews.repository.NewsRepository;
import com.stocknews.repository.StockRepository;
import com.stocknews.scheduler.StockScheduler;
import com.stocknews.service.ModelExperimentService.ExperimentResult;
import com.stocknews.service.ModelExperimentService.ModelResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** 네트워크 없이 callModel 을 가짜로 바꿔 실험 지표를 검증한다. */
class ModelExperimentServiceTest {

    StockRepository stockRepository = mock(StockRepository.class);
    NewsRepository newsRepository = mock(NewsRepository.class);
    /** 운영 분석 서비스가 쓰는 저장소: 실험은 여기에 아무 호출도 하면 안 된다 */
    StockRepository analysisStockRepository = mock(StockRepository.class);
    StockScheduler scheduler = mock(StockScheduler.class);

    List<String> calledModels = new ArrayList<>();
    List<Map<String, Object>> calledBodies = new ArrayList<>();

    Stock stock1 = stock(1L, "가나전자");
    Stock stock2 = stock(2L, "라마바");
    Stock stock3 = stock(3L, "뉴스없음");

    static Stock stock(Long id, String name) {
        Stock s = new Stock();
        s.setId(id);
        s.setName(name);
        s.setCode("00000" + id);
        return s;
    }

    static List<NewsArticle> news(int count) {
        List<NewsArticle> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            NewsArticle a = new NewsArticle();
            a.setTitle("기사 제목 " + i);
            a.setDescription("기사 요약 " + i);
            list.add(a);
        }
        return list;
    }

    static GeminiAnalysisService.ModelResponse ok(String json) {
        return new GeminiAnalysisService.ModelResponse(json, 1200, 600, 80);
    }

    @BeforeEach
    void setUp() {
        when(stockRepository.findAllByOrderByRankingAsc()).thenReturn(List.of(stock1, stock2, stock3));
        when(newsRepository.findByStockOrderByIdAsc(stock1)).thenReturn(news(3));
        when(newsRepository.findByStockOrderByIdAsc(stock2)).thenReturn(news(2));
        when(newsRepository.findByStockOrderByIdAsc(stock3)).thenReturn(List.of());
    }

    GeminiAnalysisService gemini(String[] configured, Function<String, GeminiAnalysisService.ModelResponse> fake) {
        GeminiAnalysisService s = new GeminiAnalysisService(analysisStockRepository, new ObjectMapper()) {
            @Override
            ModelResponse callModel(String model, Map<String, Object> requestBody) {
                calledModels.add(model);
                calledBodies.add(requestBody);
                return fake.apply(model);
            }
        };
        ReflectionTestUtils.setField(s, "geminiModels", configured);
        ReflectionTestUtils.setField(s, "geminiApiKey", "unused");
        return s;
    }

    ModelExperimentService experiment(GeminiAnalysisService gemini) {
        return new ModelExperimentService(stockRepository, newsRepository, gemini, new ObjectMapper(), scheduler);
    }

    // 종목1(뉴스 3건)·종목2(뉴스 2건) 모두 정상
    static final String GOOD = """
            {"stocks":[
             {"stockIndex":1,"sentiment":"POSITIVE","issues":[{"text":"신제품 공개","sources":[1,2]},{"text":"실적 개선 전망","sources":[3]}],
              "positives":["수요"],"caution":"환율","risks":["원가"]},
             {"stockIndex":2,"sentiment":"NEUTRAL","issues":[{"text":"공급 계약 체결","sources":[1]}],
              "positives":[],"caution":"","risks":[]}]}""";

    // 종목1: 근거 번호 9 는 범위 밖(뉴스 3건), 이슈 하나는 유효한 근거가 하나도 없음. 종목2: 이슈 전부 근거 없음 → 무효
    static final String BAD_SOURCES = """
            {"stocks":[
             {"stockIndex":1,"sentiment":"POSITIVE","issues":[{"text":"유효 이슈","sources":[1,9]},{"text":"근거 없는 이슈","sources":[7]}],
              "positives":[],"caution":"","risks":[]},
             {"stockIndex":2,"sentiment":"NEUTRAL","issues":[{"text":"근거 비어 있음","sources":[]}],
              "positives":[],"caution":"","risks":[]}]}""";

    @Test
    @DisplayName("정상 응답이면 모델별 지표가 맞게 계산된다")
    void metricsForGoodResponse() {
        var service = experiment(gemini(new String[]{"m1"}, m -> ok(GOOD)));

        ExperimentResult result = service.run(List.of("m1"));

        assertThat(result.stocksWithNews()).isEqualTo(2);
        assertThat(result.newsArticles()).isEqualTo(5);
        assertThat(result.ranAt()).isNotNull();
        assertThat(result.message()).isNull();
        ModelResult r = result.results().get(0);
        assertThat(r.model()).isEqualTo("m1");
        assertThat(r.ok()).isTrue();
        assertThat(r.error()).isNull();
        assertThat(r.promptTokens()).isEqualTo(1200);
        assertThat(r.outputTokens()).isEqualTo(600);
        assertThat(r.thoughtsTokens()).isEqualTo(80);
        assertThat(r.stocksRequested()).isEqualTo(2);
        assertThat(r.stocksReturned()).isEqualTo(2);
        assertThat(r.stocksValid()).isEqualTo(2);
        assertThat(r.issuesTotal()).isEqualTo(3);
        assertThat(r.issuesWithValidSources()).isEqualTo(3);
        assertThat(r.invalidSourceRefs()).isZero();
        assertThat(r.issuesDroppedNoSource()).isZero();
        // "신제품 공개"(6) + "실적 개선 전망"(8) + "공급 계약 체결"(8) = 22 / 3
        assertThat(r.avgIssueChars()).isEqualTo(7.3);
        assertThat(r.elapsedMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("범위 밖 근거 번호는 제거되어 세어지고, 근거 없는 이슈와 종목은 유효로 인정되지 않는다")
    void metricsForOutOfRangeSources() {
        var service = experiment(gemini(new String[]{"m1"}, m -> ok(BAD_SOURCES)));

        ModelResult r = service.run(List.of("m1")).results().get(0);

        assertThat(r.ok()).isTrue();
        assertThat(r.stocksRequested()).isEqualTo(2);
        assertThat(r.stocksReturned()).isEqualTo(2);
        assertThat(r.stocksValid()).isEqualTo(1);         // 종목 2 는 남은 이슈가 없어 무효
        assertThat(r.issuesTotal()).isEqualTo(3);
        assertThat(r.issuesWithValidSources()).isEqualTo(1);
        assertThat(r.invalidSourceRefs()).isEqualTo(2);   // 9, 7
        assertThat(r.issuesDroppedNoSource()).isEqualTo(2); // [7] 과 []
        assertThat(r.avgIssueChars()).isEqualTo(5.0);     // "유효 이슈"
    }

    @Test
    @DisplayName("JSON 이 아닌 응답은 ok=false, INVALID_JSON 으로 기록되고 응답 본문은 담기지 않는다")
    void invalidJsonResponse() {
        var service = experiment(gemini(new String[]{"m1"}, m -> ok("죄송합니다. 분석할 수 없습니다. SECRET-BODY")));

        ModelResult r = service.run(List.of("m1")).results().get(0);

        assertThat(r.ok()).isFalse();
        assertThat(r.error()).isEqualTo("INVALID_JSON");
        assertThat(r.stocksReturned()).isZero();
        assertThat(r.stocksValid()).isZero();
        assertThat(r.avgIssueChars()).isNull();
        assertThat(r.toString()).doesNotContain("SECRET-BODY");
    }

    @Test
    @DisplayName("한 모델이 실패해도 나머지 모델은 순서대로 한 번씩 호출된다")
    void eachModelCalledOnceInOrder() {
        var service = experiment(gemini(new String[]{"a"}, m -> {
            if (m.equals("m2")) {
                throw WebClientResponseException.create(503, "Service Unavailable", null,
                        "body with key=AIza-secret".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            }
            return ok(GOOD);
        }));

        ExperimentResult result = service.run(List.of("m1", "m2", "m3"));

        assertThat(calledModels).containsExactly("m1", "m2", "m3");
        assertThat(result.results()).extracting(ModelResult::ok).containsExactly(true, false, true);
        assertThat(result.results().get(1).error()).isEqualTo("HTTP_503");
        assertThat(result.results().toString()).doesNotContain("AIza-secret");
    }

    @Test
    @DisplayName("운영과 같은 프롬프트와 요청 본문을 보낸다")
    void sendsSameRequestAsProduction() {
        var gemini = gemini(new String[]{"m1"}, m -> ok(GOOD));
        var service = experiment(gemini);

        service.run(List.of("gemini-2.5-flash"));

        String expectedPrompt = gemini.buildPrompt(List.of(stock1, stock2), List.of(news(3), news(2)));
        assertThat(calledBodies.get(0)).isEqualTo(gemini.buildRequestBody("gemini-2.5-flash", expectedPrompt));
        // 뉴스가 없는 종목은 요청에 들어가지 않는다
        assertThat(expectedPrompt).doesNotContain("뉴스없음");
    }

    @Test
    @DisplayName("실험은 분석 결과를 저장하지 않고 저장소에는 읽기 호출만 한다")
    void doesNotPersistAnything() {
        var service = experiment(gemini(new String[]{"m1"}, m -> ok(GOOD)));

        service.run(List.of("m1", "m2"));

        verifyNoInteractions(analysisStockRepository);
        verify(stockRepository).findAllByOrderByRankingAsc();
        verifyNoMoreInteractions(stockRepository);
        verify(newsRepository).findByStockOrderByIdAsc(stock1);
        verify(newsRepository).findByStockOrderByIdAsc(stock2);
        verify(newsRepository).findByStockOrderByIdAsc(stock3);
        verifyNoMoreInteractions(newsRepository);
        // 입력 객체도 바뀌지 않는다
        assertThat(stock1.getAiAnalysis()).isNull();
        assertThat(stock1.getAnalysisModel()).isNull();
    }

    @Test
    @DisplayName("뉴스가 있는 종목이 하나도 없으면 호출하지 않고 그 사실을 응답에 적는다")
    void noNewsMeansNoCalls() {
        when(newsRepository.findByStockOrderByIdAsc(stock1)).thenReturn(List.of());
        when(newsRepository.findByStockOrderByIdAsc(stock2)).thenReturn(List.of());
        var service = experiment(gemini(new String[]{"m1"}, m -> ok(GOOD)));

        ExperimentResult result = service.run(List.of("m1"));

        assertThat(calledModels).isEmpty();
        assertThat(result.stocksWithNews()).isZero();
        assertThat(result.newsArticles()).isZero();
        assertThat(result.results()).isEmpty();
        assertThat(result.message()).contains("뉴스가 있는 종목이 없");
    }

    @Test
    @DisplayName("운영 사이클이 돌고 있어도 실험은 실행된다 (겹침은 로그로만 남김)")
    void runsWhileCycleIsRunning() {
        when(scheduler.isUpdateRunning()).thenReturn(true);
        var service = experiment(gemini(new String[]{"m1"}, m -> ok(GOOD)));

        assertThat(service.run(List.of("m1")).results()).hasSize(1);
    }

    // ===== 모델 이름 검증 =====

    @Test
    @DisplayName("모델을 주지 않으면 설정된 모델 목록을 쓴다")
    void defaultsToConfiguredModels() {
        var service = experiment(gemini(new String[]{"gemini-2.5-flash", " gemini-3.8-flash "}, m -> ok(GOOD)));

        assertThat(service.resolveModels(null)).containsExactly("gemini-2.5-flash", "gemini-3.8-flash");
        assertThat(service.resolveModels(List.of())).containsExactly("gemini-2.5-flash", "gemini-3.8-flash");
    }

    @Test
    @DisplayName("허용되지 않는 문자가 든 모델 이름은 거부한다")
    void invalidModelNamesAreRejected() {
        var service = experiment(gemini(new String[]{"m1"}, m -> ok(GOOD)));

        for (String bad : List.of("gemini/../x", "model name", "m1?key=abc", "m1:generateContent", "한글모델",
                "..", "-lead", ".lead", "a..b", "")) {
            assertThatThrownBy(() -> service.resolveModels(List.of(bad)))
                    .as("거부되어야 함: %s", bad)
                    .isInstanceOf(ModelExperimentService.InvalidModelException.class);
        }
        assertThatThrownBy(() -> service.resolveModels(java.util.Arrays.asList("ok-model", null)))
                .isInstanceOf(ModelExperimentService.InvalidModelException.class);
    }

    @Test
    @DisplayName("모델은 최대 5개까지만 받고 중복은 한 번만 부른다")
    void limitsModelCount() {
        var service = experiment(gemini(new String[]{"m1"}, m -> ok(GOOD)));

        assertThat(service.resolveModels(List.of("a", "b", "c", "d", "e"))).hasSize(5);
        assertThatThrownBy(() -> service.resolveModels(List.of("a", "b", "c", "d", "e", "f")))
                .isInstanceOf(ModelExperimentService.InvalidModelException.class);
        assertThat(service.resolveModels(List.of("a", "a", "b"))).containsExactly("a", "b");
    }

    // ===== 오류 분류 =====

    @Test
    @DisplayName("호출 예외는 HTTP 상태·TIMEOUT 같은 짧은 코드로만 바뀐다")
    void errorClassification() {
        var http429 = WebClientResponseException.create(429, "Too Many", null, new byte[0], StandardCharsets.UTF_8);
        assertThat(ModelExperimentService.classifyCallError(http429)).isEqualTo("HTTP_429");
        // 재시도 소진 예외처럼 원인에 감싸인 경우
        assertThat(ModelExperimentService.classifyCallError(new RuntimeException("retries exhausted", http429)))
                .isEqualTo("HTTP_429");
        assertThat(ModelExperimentService.classifyCallError(new IllegalStateException("Timeout on blocking read for 90000 MILLISECONDS")))
                .isEqualTo("TIMEOUT");
        assertThat(ModelExperimentService.classifyCallError(new RuntimeException(new TimeoutException())))
                .isEqualTo("TIMEOUT");
        assertThat(ModelExperimentService.classifyCallError(new RuntimeException("빈 응답 (finishReason=SAFETY)")))
                .isEqualTo("EMPTY_RESPONSE");
        assertThat(ModelExperimentService.classifyCallError(new RuntimeException("응답 파싱 실패: x")))
                .isEqualTo("INVALID_JSON");
        assertThat(ModelExperimentService.classifyCallError(new RuntimeException("url=...?key=AIza-secret")))
                .isEqualTo("CALL_FAILED");
        assertThat(HttpStatus.valueOf(503).value()).isEqualTo(503);
    }
}
