package com.stocknews.controller;

import com.stocknews.dto.MarketIndexDto;
import com.stocknews.dto.NewsDto;
import com.stocknews.dto.StockDto;
import com.stocknews.repository.NewsRepository;
import com.stocknews.repository.StockRepository;
import com.stocknews.scheduler.StockScheduler;
import com.stocknews.service.ArchiveService;
import com.stocknews.service.MarketIndexService;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
// CORS 는 CorsConfig(글로벌) 에서 처리 — localhost + *.vercel.app 허용.
// 여기 @CrossOrigin 을 두면 글로벌 설정을 덮어써 Vercel 이 차단되므로 제거함.
public class StockController {

    private final StockRepository stockRepository;
    private final NewsRepository newsRepository;
    private final StockScheduler stockScheduler;
    private final MarketIndexService marketIndexService;
    private final ArchiveService archiveService;

    @GetMapping("/stocks/top10")
    public ResponseEntity<List<StockDto>> getTop10() {
        List<StockDto> stocks = stockRepository.findAllByOrderByRankingAsc()
                .stream().map(StockDto::from).collect(Collectors.toList());
        return ResponseEntity.ok(stocks);
    }

    @GetMapping("/stocks/{id}/news")
    public ResponseEntity<?> getStockNews(@PathVariable Long id) {
        return stockRepository.findById(id)
                .map(stock -> {
                    List<NewsDto> news = newsRepository
                            .findByStockOrderByCollectedAtDesc(stock)
                            .stream().map(NewsDto::from).collect(Collectors.toList());
                    return ResponseEntity.ok(Map.of(
                            "stock", StockDto.from(stock),
                            "news", news
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/stocks/refresh")
    public ResponseEntity<Map<String, String>> refresh() {
        log.info("수동 갱신 요청");
        new Thread(stockScheduler::updateAllStocks).start();
        return ResponseEntity.ok(Map.of("message", "갱신 시작됨. 잠시 후 다시 조회해주세요."));
    }

    @GetMapping("/market/indices")
    public ResponseEntity<List<MarketIndexDto>> getIndices() {
        return ResponseEntity.ok(marketIndexService.getIndices());
    }

    // ===== 날짜별 아카이브 =====

    /** 아카이브가 존재하는 날짜 목록 (최신순) — 날짜 선택기용 */
    @GetMapping("/archive/dates")
    public ResponseEntity<List<LocalDate>> getArchiveDates() {
        return ResponseEntity.ok(archiveService.getDates());
    }

    /** 특정 날짜의 TOP10 (실시간과 동일한 형태) */
    @GetMapping("/archive/{date}/stocks")
    public ResponseEntity<List<StockDto>> getArchiveStocks(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(archiveService.getStocksByDate(date));
    }

    /** 아카이브 종목의 뉴스 + AI분석 */
    @GetMapping("/archive/stocks/{id}/news")
    public ResponseEntity<?> getArchiveStockNews(@PathVariable Long id) {
        return archiveService.getStockWithNews(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** 오늘 데이터 수동 아카이브 (테스트/관리용) */
    @PostMapping("/archive/run")
    public ResponseEntity<Map<String, Object>> runArchive() {
        int count = archiveService.archiveToday();
        return ResponseEntity.ok(Map.of("archived", count));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "stockCount", stockRepository.count(),
                "newsCount", newsRepository.count()
        ));
    }
}
