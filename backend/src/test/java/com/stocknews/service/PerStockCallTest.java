package com.stocknews.service;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 종목별 호출 모드: 종목마다 따로 모델을 불러 다른 종목 기사가 프롬프트에 섞이지 않게 한다. */
class PerStockCallTest {

    static final String CLAUDE = "claude-haiku-5-5";
    static final String GEMINI = "gemini-3.5-flash-lite";

    StockRepository repo = mock(StockRepository.class);
    List<String> prompts = new ArrayList<>();
    List<String> calls = new ArrayList<>();

    /** respond: (모델 이름, 프롬프트) -> 응답. 예외를 던지면 그 모델 호출 실패. */
    GeminiAnalysisService service(boolean perStock, BiFunction<String, String, String> respond) {
        GeminiAnalysisService s = new GeminiAnalysisService(repo, new ObjectMapper()) {
            @Override
            ModelResponse callClaude(String model, String prompt) {
                prompts.add(prompt);
                calls.add("claude:" + model);
                return new ModelResponse(respond.apply("claude", prompt), 100, 50, null);
            }

            @Override
            ModelResponse callModel(String model, Map<String, Object> requestBody) {
                prompts.add(String.valueOf(requestBody));
                calls.add("gemini:" + model);
                return new ModelResponse(respond.apply("gemini", String.valueOf(requestBody)), 100, 50, null);
            }
        };
        ReflectionTestUtils.setField(s, "anthropicApiKey", "k");
        ReflectionTestUtils.setField(s, "geminiApiKey", "g");
        s.setDropUnverifiedIssues(true);
        s.setPerStock(perStock);
        return s;
    }

    static Stock stock(Long id, String name) {
        Stock s = new Stock();
        s.setId(id);
        s.setName(name);
        s.setCode("00000" + id);
        return s;
    }

    static List<NewsArticle> news(String title, String description) {
        NewsArticle a = new NewsArticle();
        a.setTitle(title);
        a.setDescription(description);
        return List.of(a);
    }

    Stock s1 = stock(1L, "가나전자");
    Stock s2 = stock(2L, "다라화학");
    Stock s3 = stock(3L, "마바건설");
    Stock s4 = stock(4L, "사아제약");

    Map<Long, List<NewsArticle>> newsMap() {
        return Map.of(
                1L, news("가나전자기사제목", "영업이익 1조 기록"),
                2L, news("다라화학기사제목", "영업이익 2조 기록"),
                3L, news("마바건설기사제목", "영업이익 3조 기록"));
    }

    /** 프롬프트 안에 든 종목에 맞춰, 그 종목 기사의 숫자를 쓰는 이슈 하나(그리고 확인 안 되는 이슈 하나)를 낸다. */
    static String answer(String prompt) {
        String n = prompt.contains("가나전자기사제목") ? "1" : prompt.contains("다라화학기사제목") ? "2" : "3";
        return "{\"stocks\":[{\"stockIndex\":1,\"sentiment\":\"POSITIVE\",\"issues\":["
                + "{\"text\":\"영업이익 " + n + "조 기록\",\"sources\":[1]},"
                + "{\"text\":\"매출 99조 전망\",\"sources\":[1]}],"
                + "\"positives\":[],\"caution\":\"\",\"risks\":[]}]}";
    }

    @Test
    @DisplayName("종목마다 한 번씩만 부르고, 프롬프트에는 그 종목의 기사만 들어가며, 뉴스 없는 종목은 호출 없이 건너뛴다")
    void oneCallPerStockWithOwnNewsOnly() {
        var svc = service(true, (p, prompt) -> answer(prompt));

        var summary = svc.analyzeAllStocks(List.of(s1, s2, s3, s4), newsMap());

        assertThat(calls).hasSize(3);
        assertThat(prompts.get(0)).contains("가나전자기사제목").doesNotContain("다라화학기사제목").doesNotContain("마바건설기사제목");
        assertThat(prompts.get(1)).contains("다라화학기사제목").doesNotContain("가나전자기사제목").doesNotContain("마바건설기사제목");
        assertThat(prompts.get(2)).contains("마바건설기사제목").doesNotContain("가나전자기사제목").doesNotContain("다라화학기사제목");
        assertThat(summary.requested()).isEqualTo(3);
        assertThat(summary.success()).isEqualTo(3);
        assertThat(summary.failed()).isZero();
        assertThat(summary.skipped()).isEqualTo(1);
        verify(repo).updateAnalysis(eq(4L), isNull(), eq(AnalysisStatus.FAILED), isNull(), isNull(), isNull(), eq("NO_NEWS"));
    }

    @Test
    @DisplayName("합친 요약: 합계, 이어붙인 상세 JSON(종목 순서), 쓴 모델 목록")
    void mergedSummary() throws Exception {
        // 두 번째 종목은 Claude 가 실패해 Gemini 로 넘어간다
        var svc = service(true, (provider, prompt) -> {
            if (provider.equals("claude") && prompt.contains("다라화학기사제목")) {
                throw new ClaudeAnalysisClient.CallException("HTTP_529", null, null);
            }
            return answer(prompt);
        });

        var summary = svc.analyzeAllStocks(List.of(s1, s2, s3, s4), newsMap());

        assertThat(summary.model()).isEqualTo(CLAUDE + "," + GEMINI);
        // 호출: 1 claude, 2 claude(실패) + gemini, 3 claude = 4번, 토큰은 성공한 호출만 보고된다
        assertThat(calls).containsExactly("claude:" + CLAUDE, "claude:" + CLAUDE, "gemini:" + GEMINI, "claude:" + CLAUDE);
        assertThat(summary.promptTokens()).isEqualTo(300);
        assertThat(summary.outputTokens()).isEqualTo(150);
        // 종목마다 숫자 2개(영업이익 n, 99) 중 99 가 미확인
        assertThat(summary.numbersTotal()).isEqualTo(6);
        assertThat(summary.numbersUnverified()).isEqualTo(3);
        assertThat(summary.numbersNotInAnyArticle()).isEqualTo(3);
        assertThat(summary.issuesDropped()).isEqualTo(3);
        JsonNode details = new ObjectMapper().readTree(summary.numbersUnverifiedDetail());
        assertThat(details.isArray()).isTrue();
        assertThat(details).hasSize(3);
        assertThat(details.get(0).get("stock").asText()).isEqualTo("가나전자");
        assertThat(details.get(1).get("stock").asText()).isEqualTo("다라화학");
        assertThat(details.get(2).get("stock").asText()).isEqualTo("마바건설");
        assertThat(summary.elapsedMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("한 종목의 호출이 모든 체인에서 실패해도 그 종목만 FAILED 이고 나머지는 계속 분석해 저장한다")
    void oneStockFailingDoesNotStopTheLoop() {
        var svc = service(true, (provider, prompt) -> {
            if (prompt.contains("다라화학기사제목")) throw new ClaudeAnalysisClient.CallException("HTTP_529", null, null);
            return answer(prompt);
        });

        var summary = svc.analyzeAllStocks(List.of(s1, s2, s3), newsMap());

        assertThat(summary.success()).isEqualTo(2);
        assertThat(summary.failed()).isEqualTo(1);
        verify(repo).updateAnalysis(eq(2L), isNull(), eq(AnalysisStatus.FAILED), isNull(), isNull(), isNull(), eq("AI_CALL_FAILED"));
        verify(repo).updateAnalysis(eq(1L), notNull(), eq(AnalysisStatus.SUCCESS), eq(CLAUDE), notNull(), any(), isNull());
        verify(repo).updateAnalysis(eq(3L), notNull(), eq(AnalysisStatus.SUCCESS), eq(CLAUDE), notNull(), any(), isNull());
        assertThat(summary.model()).isEqualTo(CLAUDE);
    }

    @Test
    @DisplayName("종목별 모드를 끄면 모든 종목이 든 프롬프트 하나로 한 번만 부른다")
    void perStockOffIsSingleBatchCall() {
        String batch = "{\"stocks\":[{\"stockIndex\":1,\"sentiment\":\"POSITIVE\",\"issues\":[{\"text\":\"영업이익 1조 기록\",\"sources\":[1]}],"
                + "\"positives\":[],\"caution\":\"\",\"risks\":[]}]}";
        var svc = service(false, (p, prompt) -> batch);

        var summary = svc.analyzeAllStocks(List.of(s1, s2, s3, s4), newsMap());

        assertThat(calls).hasSize(1);
        assertThat(prompts.get(0)).contains("가나전자기사제목", "다라화학기사제목", "마바건설기사제목");
        assertThat(summary.requested()).isEqualTo(3);
        assertThat(summary.skipped()).isEqualTo(1);
    }
}
