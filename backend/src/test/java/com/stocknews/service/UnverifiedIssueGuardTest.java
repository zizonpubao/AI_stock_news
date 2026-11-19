package com.stocknews.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocknews.entity.AnalysisStatus;
import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.Stock;
import com.stocknews.repository.StockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 인용 기사에서 숫자를 확인하지 못한 이슈 문장을 저장하지 않는 가드. 네트워크 호출 없이 callClaude 를 가짜로 바꿔 검증한다. */
class UnverifiedIssueGuardTest {

    static final String MODEL = "claude-haiku-5-5";

    StockRepository repo = mock(StockRepository.class);

    GeminiAnalysisService service(boolean guardOn, String json) {
        GeminiAnalysisService s = new GeminiAnalysisService(repo, new ObjectMapper()) {
            @Override
            ModelResponse callClaude(String model, String prompt) {
                return new ModelResponse(json, 1000, 500, null);
            }
        };
        ReflectionTestUtils.setField(s, "anthropicApiKey", "k");
        ReflectionTestUtils.setField(s, "geminiApiKey", "");
        s.setDropUnverifiedIssues(guardOn);
        return s;
    }

    static Stock stock(Long id, String name) {
        Stock s = new Stock();
        s.setId(id);
        s.setName(name);
        s.setCode("00000" + id);
        return s;
    }

    static NewsArticle article(String title, String description) {
        NewsArticle a = new NewsArticle();
        a.setTitle(title);
        a.setDescription(description);
        return a;
    }

    Stock a = stock(1L, "가나전자");
    Stock b = stock(2L, "다라화학");

    Map<Long, List<NewsArticle>> news() {
        return Map.of(
                1L, List.of(article("가나전자 실적", "영업이익 9.1조 기록, 9천810억 투자"), article("가나전자 전망", "매출 30조 전망")),
                2L, List.of(article("다라화학 실적", "영업이익 5조 기록")));
    }

    static String issue(String text, int source) {
        return "{\"text\":\"" + text + "\",\"sources\":[" + source + "]}";
    }

    static String stockJson(int index, String... issues) {
        return "{\"stockIndex\":" + index + ",\"sentiment\":\"POSITIVE\",\"issues\":[" + String.join(",", issues)
                + "],\"positives\":[\"수요 증가\"],\"caution\":\"환율 변동\",\"risks\":[\"원가 상승\"]}";
    }

    static String response(String... stocks) {
        return "{\"stocks\":[" + String.join(",", stocks) + "]}";
    }

    String savedText(long id) {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(repo).updateAnalysis(eq(id), text.capture(), eq(AnalysisStatus.SUCCESS), eq(MODEL), notNull(), any(), isNull());
        return text.getValue();
    }

    // 가나전자: 확인됨 / 같은 종목 다른 기사에만 있는 숫자 / 다른 종목 기사에만 있는 숫자 / 숫자 없음 / 천 표기 차이
    String mixed() {
        return response(
                stockJson(1,
                        issue("영업이익 9.1조 기록", 1),
                        issue("매출 30조 전망", 1),
                        issue("영업이익 5조 기록", 1),
                        issue("신사업 추진 소식", 2),
                        issue("투자 9810억 집행", 1)),
                stockJson(2, issue("영업이익 5조 기록", 1)));
    }

    @Test
    @DisplayName("가드를 켜면 인용 기사에서 숫자를 확인하지 못한 이슈만 빠지고 나머지는 순서대로 저장된다")
    void dropsOnlyUnverifiedIssuesAndKeepsOrder() {
        var summary = service(true, mixed()).analyzeAllStocks(List.of(a, b), news());

        assertThat(summary.success()).isEqualTo(2);
        assertThat(summary.failed()).isZero();
        assertThat(summary.issuesDropped()).isEqualTo(2);

        String text = savedText(1L);
        assertThat(text).contains("영업이익 9.1조 기록", "신사업 추진 소식", "투자 9810억 집행");
        assertThat(text).doesNotContain("매출 30조 전망").doesNotContain("영업이익 5조 기록");
        assertThat(text.indexOf("영업이익 9.1조 기록")).isLessThan(text.indexOf("신사업 추진 소식"));
        assertThat(text.indexOf("신사업 추진 소식")).isLessThan(text.indexOf("투자 9810억 집행"));
        // 다른 종목은 그대로 저장
        assertThat(savedText(2L)).contains("영업이익 5조 기록");
    }

    @Test
    @DisplayName("측정값은 걸러내기 전 모델 원본 기준이다")
    void measurementStaysOnRawOutput() {
        var summary = service(true, mixed()).analyzeAllStocks(List.of(a, b), news());

        // 문장 속 숫자: 9.1 / 30 / 5 / (없음) / 9810 / 5 -> 미확인은 30(다른 기사)과 5(다른 종목) 2개
        assertThat(summary.numbersTotal()).isEqualTo(5);
        assertThat(summary.numbersUnverified()).isEqualTo(2);
        assertThat(summary.issuesDropped()).isEqualTo(2);
    }

    @Test
    @DisplayName("한 종목의 이슈가 전부 빠지면 그 종목은 INVALID_RESPONSE 로 실패 처리하고 가짜 분석을 저장하지 않으며 다른 종목은 정상 저장한다")
    void stockWithAllIssuesDroppedFails() {
        String json = response(
                stockJson(1, issue("매출 30조 전망", 1), issue("영업이익 5조 기록", 1)),
                stockJson(2, issue("영업이익 5조 기록", 1)));

        var summary = service(true, json).analyzeAllStocks(List.of(a, b), news());

        assertThat(summary.success()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.issuesDropped()).isEqualTo(2);
        verify(repo).updateAnalysis(eq(1L), isNull(), eq(AnalysisStatus.FAILED), isNull(), isNull(), isNull(), eq("INVALID_RESPONSE"));
        verify(repo, never()).updateAnalysis(eq(1L), any(), eq(AnalysisStatus.SUCCESS), any(), any(), any(), any());
        assertThat(a.getAiAnalysis()).isNull();
        assertThat(savedText(2L)).contains("영업이익 5조 기록");
    }

    @Test
    @DisplayName("가드를 끄면 아무것도 빼지 않고 issuesDropped 는 null 이다")
    void guardOffKeepsEverything() {
        var summary = service(false, mixed()).analyzeAllStocks(List.of(a, b), news());

        assertThat(summary.issuesDropped()).isNull();
        assertThat(summary.success()).isEqualTo(2);
        assertThat(savedText(1L)).contains("매출 30조 전망", "영업이익 5조 기록");
        assertThat(summary.numbersUnverified()).isEqualTo(2);
    }

    @Test
    @DisplayName("기사에는 9천810억, 문장에는 9810억 인 표기 차이는 빼지 않는다")
    void cheonNotationIsNotDropped() {
        String json = response(stockJson(1, issue("투자 9810억 집행", 1)));

        var summary = service(true, json).analyzeAllStocks(List.of(a), news());

        assertThat(summary.issuesDropped()).isZero();
        assertThat(summary.success()).isEqualTo(1);
        assertThat(savedText(1L)).contains("투자 9810억 집행");
    }
}
