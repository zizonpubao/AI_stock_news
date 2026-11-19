package com.stocknews.controller;

import com.stocknews.config.AdminTokenInterceptor;
import com.stocknews.scheduler.StockScheduler;
import com.stocknews.service.ModelCompareResult;
import com.stocknews.service.TimeRuleAbResult;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TimeRuleAbController.class)
@TestPropertySource(properties = "admin.token=test-token-value")
class TimeRuleAbControllerTest {

    static final String PATH = "/api/admin/experiments/time-rule-ab";
    static final String TOKEN = "test-token-value";

    @Autowired MockMvc mockMvc;
    @Autowired TimeRuleAbController controller;
    @MockBean StockScheduler stockScheduler;

    @BeforeEach
    void reset() {
        ReflectionTestUtils.setField(controller, "last", null);
    }

    private static TimeRuleAbResult result() {
        return TimeRuleAbResult.notRun(LocalDateTime.of(2026, 11, 19, 20, 0), 3,
                new TimeRuleAbResult.Input(1, 2, 1, 1000, 1200), "테스트");
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
    @DisplayName("runs 는 기본 3, 1~5 로 제한되고 includeInput 기본은 false")
    void runsClamped() throws Exception {
        when(stockScheduler.runTimeRuleAb(anyInt(), anyBoolean())).thenReturn(Optional.of(result()));
        for (String q : new String[]{"", "?runs=99", "?runs=0", "?runs=-3", "?runs=4&includeInput=true"}) {
            mockMvc.perform(post(PATH + q).header(AdminTokenInterceptor.HEADER, TOKEN)).andExpect(status().isOk());
        }
        verify(stockScheduler).runTimeRuleAb(3, false);
        verify(stockScheduler).runTimeRuleAb(5, false);
        verify(stockScheduler, times(2)).runTimeRuleAb(1, false);
        verify(stockScheduler).runTimeRuleAb(4, true);
    }

    @Test
    @DisplayName("진행 중이면 409 이고 마지막 결과는 바뀌지 않는다")
    void busyIsConflict() throws Exception {
        when(stockScheduler.runTimeRuleAb(anyInt(), anyBoolean())).thenReturn(Optional.empty());
        mockMvc.perform(post(PATH).header(AdminTokenInterceptor.HEADER, TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
        mockMvc.perform(get(PATH).header(AdminTokenInterceptor.HEADER, TOKEN)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET 은 결과가 없으면 404, 실행 뒤에는 기사 글 없는 마지막 결과를 돌려준다")
    void getReturnsLastWithoutTexts() throws Exception {
        mockMvc.perform(get(PATH).header(AdminTokenInterceptor.HEADER, TOKEN)).andExpect(status().isNotFound());

        TimeRuleAbResult withTexts = new TimeRuleAbResult(LocalDateTime.of(2026, 11, 19, 20, 0), "m", 3,
                new TimeRuleAbResult.Input(1, 2, 1, 1000, 1200,
                        List.of(new ModelCompareResult.Article("가나전자", 1, 1, "제목", "기사본문XYZ", true))),
                Map.of(), List.of(), null);
        when(stockScheduler.runTimeRuleAb(eq(3), eq(true))).thenReturn(Optional.of(withTexts));

        // 응답에는 기사 글이 실린다
        mockMvc.perform(post(PATH + "?includeInput=true").header(AdminTokenInterceptor.HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.input.articleTexts[0].text").value("기사본문XYZ"));

        // 보관본에는 없다
        mockMvc.perform(get(PATH).header(AdminTokenInterceptor.HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startedAt").value("2026-11-19T20:00:00"))
                .andExpect(jsonPath("$.input.withoutRulePromptCharsTotal").value(1000))
                .andExpect(jsonPath("$.input.articleTexts").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("기사본문XYZ"))));
    }
}
