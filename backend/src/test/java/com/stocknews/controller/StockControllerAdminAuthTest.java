package com.stocknews.controller;

import com.stocknews.config.AdminTokenInterceptor;
import com.stocknews.repository.NewsRepository;
import com.stocknews.repository.StockRepository;
import com.stocknews.scheduler.StockScheduler;
import com.stocknews.service.ArchiveService;
import com.stocknews.service.MarketIndexService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 관리용 API 에만 토큰 검사가 걸리고, 조회 API 는 공개 그대로인지 실제 라우팅으로 확인한다. */
@WebMvcTest(StockController.class)
@TestPropertySource(properties = "admin.token=test-token-value")
class StockControllerAdminAuthTest {

    @Autowired MockMvc mockMvc;

    @MockBean StockRepository stockRepository;
    @MockBean NewsRepository newsRepository;
    @MockBean StockScheduler stockScheduler;
    @MockBean MarketIndexService marketIndexService;
    @MockBean ArchiveService archiveService;

    @Test
    @DisplayName("토큰 없이 수동 갱신을 부르면 401 이고 갱신은 시작되지 않는다")
    void refreshWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/stocks/refresh")).andExpect(status().isUnauthorized());

        verify(stockScheduler, never()).startUpdateAsync();
    }

    @Test
    @DisplayName("토큰 없이 수동 아카이브를 부르면 401 이고 아카이브는 실행되지 않는다")
    void archiveRunWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/archive/run")).andExpect(status().isUnauthorized());

        verify(archiveService, never()).archiveToday();
    }

    @Test
    @DisplayName("올바른 토큰이면 수동 갱신이 시작된다 (202)")
    void refreshWithTokenStarts() throws Exception {
        when(stockScheduler.startUpdateAsync()).thenReturn(true);

        mockMvc.perform(post("/api/stocks/refresh").header(AdminTokenInterceptor.HEADER, "test-token-value"))
                .andExpect(status().isAccepted());

        verify(stockScheduler).startUpdateAsync();
    }

    @Test
    @DisplayName("이미 갱신 중이면 중복 실행하지 않고 409")
    void refreshWhileRunningIsConflict() throws Exception {
        when(stockScheduler.startUpdateAsync()).thenReturn(false);

        mockMvc.perform(post("/api/stocks/refresh").header(AdminTokenInterceptor.HEADER, "test-token-value"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("조회 API 는 토큰 없이도 열려 있다")
    void readApisStayPublic() throws Exception {
        when(stockRepository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        mockMvc.perform(get("/api/stocks/top10")).andExpect(status().isOk());
        mockMvc.perform(get("/api/archive/dates")).andExpect(status().isOk());
    }
}
