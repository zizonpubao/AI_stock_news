package com.stocknews.controller;

import com.stocknews.scheduler.StockScheduler;
import com.stocknews.service.TimeRuleAbResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * 시점 표현 지침 비교 실험: 종목별 호출 두 변형, 프롬프트에 시점 표현 지침(TIME_RULE)이 없는 것 vs 있는 것
 * (관리용, X-Admin-Token 필요 — /api/admin/** 에 AdminTokenInterceptor 적용).
 * 수집 사이클 한 번과 모델 호출 runs x 2 x 종목 수번, 호출 간격을 끝까지 기다리므로 응답까지 십수 분 걸릴 수 있다.
 * 마지막 결과는 메모리에만 두고 GET 으로 다시 읽을 수 있다. 기사 글(includeInput)은 이 보관본에서 뺀다.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/experiments/time-rule-ab")
@RequiredArgsConstructor
public class TimeRuleAbController {

    static final int DEFAULT_RUNS = 3;
    static final int MIN_RUNS = 1;
    static final int MAX_RUNS = 5;

    private final StockScheduler stockScheduler;

    private volatile TimeRuleAbResult last;

    @PostMapping
    public ResponseEntity<?> run(@RequestParam(name = "runs", defaultValue = "" + DEFAULT_RUNS) int runs,
                                 @RequestParam(name = "includeInput", defaultValue = "false") boolean includeInput) {
        int clamped = Math.max(MIN_RUNS, Math.min(MAX_RUNS, runs));
        log.info("시점 표현 지침 비교 실험 요청 - 변형당 {}회, 기사 글 포함 {}", clamped, includeInput);
        Optional<TimeRuleAbResult> result = stockScheduler.runTimeRuleAb(clamped, includeInput);
        if (result.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "이미 수집 사이클 또는 비교 실험이 진행 중입니다."));
        }
        last = result.get().withoutArticleTexts();
        return ResponseEntity.ok(result.get());
    }

    @GetMapping
    public ResponseEntity<?> lastResult() {
        TimeRuleAbResult r = last;
        if (r == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "아직 실행한 비교 실험이 없습니다."));
        }
        return ResponseEntity.ok(r);
    }
}
