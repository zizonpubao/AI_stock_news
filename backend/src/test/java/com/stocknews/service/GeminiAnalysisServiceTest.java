package com.stocknews.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocknews.entity.AnalysisStatus;
import com.stocknews.entity.Stock;
import com.stocknews.repository.StockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class GeminiAnalysisServiceTest {

    @Test
    @DisplayName("응답을 [종목N] 태그 기준으로 종목별 분석으로 나눈다")
    void splitsByTags() {
        String response = "[종목1]\n📈 주요 이슈\n- A\n\n[종목2]\n📈 주요 이슈\n- B\n\n[종목3]\n📈 주요 이슈\n- C";

        Map<Integer, String> result = GeminiAnalysisService.splitByTags(response, 3);

        assertThat(result).hasSize(3);
        assertThat(result.get(0)).isEqualTo("📈 주요 이슈\n- A");
        assertThat(result.get(1)).isEqualTo("📈 주요 이슈\n- B");
        assertThat(result.get(2)).isEqualTo("📈 주요 이슈\n- C");
    }

    @Test
    @DisplayName("[종목1] 과 [종목10] 을 혼동하지 않는다")
    void doesNotConfuseTag1WithTag10() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 10; i++) sb.append("[종목").append(i).append("]\n분석").append(i).append("\n");

        Map<Integer, String> result = GeminiAnalysisService.splitByTags(sb.toString(), 10);

        assertThat(result.get(0)).isEqualTo("분석1");
        assertThat(result.get(9)).isEqualTo("분석10");
    }

    @Test
    @DisplayName("태그가 빠진 종목은 결과에 넣지 않고, 앞 종목 분석은 다음에 존재하는 태그 직전까지만 자른다")
    void missingTagIsOmitted() {
        String response = "[종목1]\n분석1\n[종목3]\n분석3";

        Map<Integer, String> result = GeminiAnalysisService.splitByTags(response, 3);

        assertThat(result).containsOnlyKeys(0, 2);
        assertThat(result.get(0)).isEqualTo("분석1");
        assertThat(result.get(2)).isEqualTo("분석3");
    }

    @Test
    @DisplayName("태그만 있고 내용이 비어 있으면 분석이 없는 것으로 본다")
    void emptyBodyIsOmitted() {
        Map<Integer, String> result = GeminiAnalysisService.splitByTags("[종목1]\n\n[종목2]\n분석2", 2);

        assertThat(result).containsOnlyKeys(1);
    }

    @Test
    @DisplayName("응답이 null 이거나 태그가 하나도 없으면 빈 결과")
    void noTagsAtAll() {
        assertThat(GeminiAnalysisService.splitByTags(null, 3)).isEmpty();
        assertThat(GeminiAnalysisService.splitByTags("죄송합니다. 분석할 수 없습니다.", 3)).isEmpty();
    }

    @Test
    @DisplayName("AI 호출이 모두 실패하면 대체 문구를 저장하지 않고 FAILED 상태만 기록한다")
    void allModelsFailedMarksFailedWithoutFakeText() {
        StockRepository repo = mock(StockRepository.class);
        GeminiAnalysisService service = new GeminiAnalysisService(repo, new ObjectMapper());
        // 호출할 모델이 없는 상태 = 모든 모델이 실패한 것과 같은 경로 (네트워크 호출 없음)
        ReflectionTestUtils.setField(service, "geminiModels", new String[0]);
        ReflectionTestUtils.setField(service, "geminiApiKey", "unused");

        Stock s1 = stock(1L, "가나다");
        Stock s2 = stock(2L, "라마바");

        service.analyzeAllStocks(List.of(s1, s2), Map.of());

        verify(repo).updateAnalysis(1L, null, AnalysisStatus.FAILED);
        verify(repo).updateAnalysis(2L, null, AnalysisStatus.FAILED);
        verify(repo, never()).updateAnalysis(anyLong(), notNull(), any());
        assertThat(s1.getAiAnalysis()).isNull();
        assertThat(s1.getAnalysisStatus()).isEqualTo(AnalysisStatus.FAILED);
    }

    private static Stock stock(Long id, String name) {
        Stock s = new Stock();
        s.setId(id);
        s.setName(name);
        s.setCode("000000");
        return s;
    }
}
