package com.stocknews.controller;

import com.stocknews.config.AdminTokenInterceptor;
import com.stocknews.entity.PipelineRun;
import com.stocknews.repository.NewsRepository;
import com.stocknews.repository.PipelineRunRepository;
import com.stocknews.repository.StockRepository;
import com.stocknews.scheduler.StockScheduler;
import com.stocknews.service.GeminiAnalysisService;
import com.stocknews.service.ModelExperimentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실행 이력 조회는 공개, 모델 비교 실험은 관리자 토큰 필요 — 실제 라우팅으로 확인한다. */
@WebMvcTest({PipelineRunController.class, ModelExperimentController.class})
@Import(ModelExperimentService.class)
@TestPropertySource(properties = "admin.token=test-token-value")
class RunAndExperimentApiTest {

    @Autowired MockMvc mockMvc;

    @MockBean PipelineRunRepository pipelineRunRepository;
    @MockBean StockRepository stockRepository;
    @MockBean NewsRepository newsRepository;
    @MockBean GeminiAnalysisService geminiAnalysisService;
    @MockBean StockScheduler stockScheduler;

    @Test
    @DisplayName("토큰 없이 모델 비교 실험을 부르면 401 이고 아무것도 읽거나 호출하지 않는다")
    void experimentWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/admin/experiments/models")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/experiments/models").header(AdminTokenInterceptor.HEADER, "wrong")
                .contentType(APPLICATION_JSON).content("{\"models\":[\"gemini-2.5-flash\"]}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(stockRepository, newsRepository, geminiAnalysisService);
    }

    @Test
    @DisplayName("허용되지 않는 문자가 든 모델 이름은 400 이고 호출은 일어나지 않는다")
    void invalidModelNameIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/admin/experiments/models").header(AdminTokenInterceptor.HEADER, "test-token-value")
                .contentType(APPLICATION_JSON).content("{\"models\":[\"gemini-2.5-flash\",\"../evil?key=1\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        verifyNoInteractions(stockRepository, newsRepository);
    }

    @Test
    @DisplayName("모델이 5개를 넘으면 400")
    void tooManyModelsIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/admin/experiments/models").header(AdminTokenInterceptor.HEADER, "test-token-value")
                .contentType(APPLICATION_JSON).content("{\"models\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("올바른 토큰이면 실험이 실행되고, 뉴스 있는 종목이 없으면 그 사실이 응답에 적힌다")
    void experimentWithTokenRuns() throws Exception {
        when(stockRepository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        mockMvc.perform(post("/api/admin/experiments/models").header(AdminTokenInterceptor.HEADER, "test-token-value")
                .contentType(APPLICATION_JSON).content("{\"models\":[\"gemini-2.5-flash\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stocksWithNews").value(0))
                .andExpect(jsonPath("$.newsArticles").value(0))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.results").isEmpty());
    }

    @Test
    @DisplayName("실행 이력 목록과 요약은 토큰 없이도 열려 있다")
    void runsApisArePublic() throws Exception {
        when(pipelineRunRepository.findAllByOrderByStartedAtDescIdDesc(any())).thenReturn(List.of());
        when(pipelineRunRepository.findByStartedAtGreaterThanEqualOrderByStartedAtAscIdAsc(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/runs")).andExpect(status().isOk());
        mockMvc.perform(get("/api/runs/summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.runs").value(0))
                .andExpect(jsonPath("$.analysisRequested").value(0))
                .andExpect(jsonPath("$.avgAnalysisElapsedMs").isEmpty())
                .andExpect(jsonPath("$.lastRunAt").isEmpty())
                .andExpect(jsonPath("$.lastModel").isEmpty());
    }

    @Test
    @DisplayName("limit 는 1~200 으로 제한된다")
    void limitIsClamped() throws Exception {
        when(pipelineRunRepository.findAllByOrderByStartedAtDescIdDesc(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/runs?limit=100000")).andExpect(status().isOk());
        mockMvc.perform(get("/api/runs?limit=0")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(pipelineRunRepository, org.mockito.Mockito.times(2)).findAllByOrderByStartedAtDescIdDesc(captor.capture());
        assertThat(captor.getAllValues()).extracting(Pageable::getPageSize).containsExactly(200, 1);
    }

    @Test
    @DisplayName("hours 는 1~720 으로 제한되고, 요약은 합의된 필드 이름으로 나간다")
    void summaryShapeAndHoursClamp() throws Exception {
        PipelineRun r = new PipelineRun();
        r.setStartedAt(LocalDateTime.of(2026, 11, 19, 15, 0, 0));
        r.setFinishedAt(LocalDateTime.of(2026, 11, 19, 15, 0, 41));
        r.setCollectOk(true);
        r.setAnalysisModel("gemini-2.5-flash");
        r.setAnalysisRequested(10);
        r.setAnalysisSuccess(9);
        r.setAnalysisFailed(1);
        r.setAnalysisSkipped(0);
        r.setAnalysisElapsedMs(21500L);
        when(pipelineRunRepository.findByStartedAtGreaterThanEqualOrderByStartedAtAscIdAsc(any())).thenReturn(List.of(r));

        mockMvc.perform(get("/api/runs/summary?hours=999999")).andExpect(status().isOk())
                .andExpect(jsonPath("$.runs").value(1))
                .andExpect(jsonPath("$.collectSuccess").value(1))
                .andExpect(jsonPath("$.analysisRequested").value(10))
                .andExpect(jsonPath("$.analysisSuccess").value(9))
                .andExpect(jsonPath("$.analysisFailed").value(1))
                .andExpect(jsonPath("$.analysisSkippedNoNews").value(0))
                .andExpect(jsonPath("$.avgAnalysisElapsedMs").value(21500))
                .andExpect(jsonPath("$.lastRunAt").value("2026-11-19T15:00:41"))
                .andExpect(jsonPath("$.lastModel").value("gemini-2.5-flash"))
                .andExpect(jsonPath("$.since").exists());

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(pipelineRunRepository).findByStartedAtGreaterThanEqualOrderByStartedAtAscIdAsc(captor.capture());
        // 720시간(30일) 상한: since 가 그보다 더 과거이면 안 된다
        assertThat(captor.getValue()).isAfter(LocalDateTime.now().minusHours(721));
    }
}
