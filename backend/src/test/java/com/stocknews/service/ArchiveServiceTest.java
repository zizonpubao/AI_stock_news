package com.stocknews.service;

import com.stocknews.entity.DailyArchiveStock;
import com.stocknews.entity.Stock;
import com.stocknews.repository.DailyArchiveNewsRepository;
import com.stocknews.repository.DailyArchiveStockRepository;
import com.stocknews.repository.NewsRepository;
import com.stocknews.repository.StockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArchiveServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final StockRepository stockRepository = mock(StockRepository.class);
    private final NewsRepository newsRepository = mock(NewsRepository.class);
    private final DailyArchiveStockRepository archiveStockRepository = mock(DailyArchiveStockRepository.class);
    private final DailyArchiveNewsRepository archiveNewsRepository = mock(DailyArchiveNewsRepository.class);
    private final ArchiveService service = new ArchiveService(
            stockRepository, newsRepository, archiveStockRepository, archiveNewsRepository);

    private static Stock stockUpdatedAt(LocalDateTime updatedAt) {
        Stock s = new Stock();
        s.setCode("111111");
        s.setName("가나전자");
        s.setRanking(1);
        s.setUpdatedAt(updatedAt);
        return s;
    }

    @Test
    @DisplayName("오늘 수집된 데이터가 없으면(며칠 전 데이터만 남아 있으면) 아카이브하지 않고 기존 아카이브도 지우지 않는다")
    void skipsWhenNothingCollectedToday() {
        LocalDateTime threeDaysAgo = LocalDate.now(KST).minusDays(3).atTime(15, 0);
        when(stockRepository.findAllByOrderByRankingAsc()).thenReturn(List.of(stockUpdatedAt(threeDaysAgo)));

        int saved = service.archiveToday();

        assertThat(saved).isZero();
        verify(archiveStockRepository, never()).save(any());
        verify(archiveStockRepository, never()).deleteAll(anyList());
    }

    @Test
    @DisplayName("종목이 하나도 없어도 아카이브하지 않는다")
    void skipsWhenNoStocks() {
        when(stockRepository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        assertThat(service.archiveToday()).isZero();
        verify(archiveStockRepository, never()).save(any());
    }

    @Test
    @DisplayName("오늘 수집된 데이터는 아카이브한다")
    void archivesTodaysData() {
        LocalDateTime today = LocalDate.now(KST).atTime(15, 0);
        Stock stock = stockUpdatedAt(today);
        when(stockRepository.findAllByOrderByRankingAsc()).thenReturn(List.of(stock));
        when(archiveStockRepository.findBySnapshotDateOrderByRankingAsc(any())).thenReturn(List.of());
        when(newsRepository.findByStockOrderByIdAsc(stock)).thenReturn(List.of());

        int saved = service.archiveToday();

        assertThat(saved).isEqualTo(1);
        verify(archiveStockRepository).save(any(DailyArchiveStock.class));
    }
}
