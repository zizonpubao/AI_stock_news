package com.stocknews.controller;

import com.stocknews.scheduler.StockScheduler;
import com.stocknews.service.CitationAbResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * 근거 번호 지침 유무 비교 실험 (관리용, X-Admin-Token 필요 — /api/admin/** 에 AdminTokenInterceptor 적용).
 * 수집 사이클 한 번과 모델 호출 2*runs 번을 끝까지 기다리므로 응답까지 수 분 걸릴 수 있다.
 * 마지막 결과는 메모리에만 두고 GET 으로 다시 읽을 수 있다 (서버를 다시 시작하면 사라진다).
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/experiments/citation-ab")
@RequiredArgsConstructor
public class CitationAbController {

    static final int DEFAULT_RUNS = 5;
    static final int MIN_RUNS = 1;
    static final int MAX_RUNS = 10;

    private final StockScheduler stockScheduler;

    private volatile CitationAbResult last;

    @PostMapping
    public ResponseEntity<?> run(@RequestParam(name = "runs", defaultValue = "" + DEFAULT_RUNS) int runs) {
        int clamped = Math.max(MIN_RUNS, Math.min(MAX_RUNS, runs));
        log.info("근거 번호 지침 비교 실험 요청 - 변형당 {}회", clamped);
        Optional<CitationAbResult> result = stockScheduler.runCitationAb(clamped);
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "이미 수집 사이클 또는 비교 실험이 진행 중입니다."));
        }
        last = result.get();
        return ResponseEntity.ok(last);
    }

    @GetMapping
    public ResponseEntity<?> lastResult() {
        CitationAbResult r = last;
        if (r == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "아직 실행한 비교 실험이 없습니다."));
        }
        return ResponseEntity.ok(r);
    }
}
