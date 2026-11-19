package com.stocknews.controller;

import com.stocknews.config.AdminTokenInterceptor;
import com.stocknews.scheduler.StockScheduler;
import com.stocknews.service.CitationAbResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CitationAbController.class)
@TestPropertySource(properties = "admin.token=test-token-value")
class CitationAbControllerTest {

    static final String PATH = "/api/admin/experiments/citation-ab";

    @Autowired MockMvc mockMvc;
    @Autowired CitationAbController controller;
    @MockBean StockScheduler stockScheduler;

    @BeforeEach
    void reset() {
        ReflectionTestUtils.setField(controller, "last", null);
    }

    private static CitationAbResult result() {
        return CitationAbResult.notRun(LocalDateTime.of(2026, 11, 19, 20, 0), 5,
                new CitationAbResult.Input(1, 2, 1), "테스트");
    }

    @Test
    @DisplayName("토큰이 없거나 틀리면 401 이고 실험은 시작되지 않는다 (POST·GET 모두)")
    void requiresToken() throws Exception {
        mockMvc.perform(post(PATH)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(PATH).header(AdminTokenInterceptor.HEADER, "wrong")).andExpect(status().isUnauthorized());
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        verifyNoInteractions(stockScheduler);
    }

    @Test
    @DisplayName("runs 는 기본 5, 1~10 으로 제한된다")
    void runsClamped() throws Exception {
        when(stockScheduler.runCitationAb(anyInt())).thenReturn(Optional.of(result()));
        for (String q : new String[]{"", "?runs=99", "?runs=0", "?runs=-3", "?runs=7"}) {
            mockMvc.perform(post(PATH + q).header(AdminTokenInterceptor.HEADER, "test-token-value"))
                    .andExpect(status().isOk());
        }
        verify(stockScheduler).runCitationAb(5);
        verify(stockScheduler).runCitationAb(10);
        verify(stockScheduler, times(2)).runCitationAb(1);
        verify(stockScheduler).runCitationAb(7);
    }

    @Test
    @DisplayName("진행 중이면 409 이고 마지막 결과는 바뀌지 않는다")
    void busyIsConflict() throws Exception {
        when(stockScheduler.runCitationAb(anyInt())).thenReturn(Optional.empty());
        mockMvc.perform(post(PATH).header(AdminTokenInterceptor.HEADER, "test-token-value"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
        mockMvc.perform(get(PATH).header(AdminTokenInterceptor.HEADER, "test-token-value"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET 은 결과가 없으면 404, 실행 뒤에는 마지막 결과를 돌려준다")
    void getReturnsLast() throws Exception {
        mockMvc.perform(get(PATH).header(AdminTokenInterceptor.HEADER, "test-token-value"))
                .andExpect(status().isNotFound());

        when(stockScheduler.runCitationAb(anyInt())).thenReturn(Optional.of(result()));
        mockMvc.perform(post(PATH + "?runs=3").header(AdminTokenInterceptor.HEADER, "test-token-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runsPerVariant").value(5))
                .andExpect(jsonPath("$.input.articlesWithBody").value(1))
                .andExpect(jsonPath("$.variants.WITH_RULE.callsOk").value(0));

        mockMvc.perform(get(PATH).header(AdminTokenInterceptor.HEADER, "test-token-value"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startedAt").value("2026-11-19T20:00:00"))
                .andExpect(jsonPath("$.message").value("테스트"));
    }
}
