package com.stocknews.controller;

import com.stocknews.dto.NewsDto;
import com.stocknews.dto.StockDto;
import com.stocknews.repository.NewsRepository;
import com.stocknews.repository.StockRepository;
import com.stocknews.scheduler.StockScheduler;
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
@CrossOrigin(origins = {"http://localhost:3000", "http://localhost:5173"})
public class StockController {

    private final StockRepository stockRepository;
    private final NewsRepository newsRepository;
    private final StockScheduler stockScheduler;

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

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "stockCount", stockRepository.count(),
                "newsCount", newsRepository.count()
        ));
    }
}
