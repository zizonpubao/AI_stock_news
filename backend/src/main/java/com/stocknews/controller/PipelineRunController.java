package com.stocknews.controller;

import com.stocknews.dto.RunSummary;
import com.stocknews.entity.PipelineRun;
import com.stocknews.repository.PipelineRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/** 수집 사이클 실행 이력 조회 (공개, 읽기 전용) */
@RestController
@RequestMapping("/api/runs")
@RequiredArgsConstructor
public class PipelineRunController {

    static final int MAX_LIMIT = 200;
    static final int MAX_HOURS = 720;

    private final PipelineRunRepository pipelineRunRepository;

    /** 최신순 목록. limit 는 1~200 으로 제한한다. */
    @GetMapping
    public ResponseEntity<List<PipelineRun>> list(@RequestParam(defaultValue = "50") int limit) {
        int size = Math.max(1, Math.min(limit, MAX_LIMIT));
        return ResponseEntity.ok(pipelineRunRepository.findAllByOrderByStartedAtDescIdDesc(PageRequest.of(0, size)));
    }

    /** 최근 N시간 요약. hours 는 1~720 으로 제한한다. */
    @GetMapping("/summary")
    public ResponseEntity<RunSummary> summary(@RequestParam(defaultValue = "24") int hours) {
        int h = Math.max(1, Math.min(hours, MAX_HOURS));
        LocalDateTime since = LocalDateTime.now().minusHours(h).withNano(0);
        List<PipelineRun> runs = pipelineRunRepository.findByStartedAtGreaterThanEqualOrderByStartedAtAscIdAsc(since);
        return ResponseEntity.ok(RunSummary.of(runs, since));
    }
}
