package com.stocknews.repository;

import com.stocknews.entity.PipelineRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface PipelineRunRepository extends JpaRepository<PipelineRun, Long> {
    /** 최신순 목록 (Pageable 로 개수 제한) */
    List<PipelineRun> findAllByOrderByStartedAtDescIdDesc(Pageable pageable);

    /** since 이후(포함)에 시작한 실행, 오래된 순 */
    List<PipelineRun> findByStartedAtGreaterThanEqualOrderByStartedAtAscIdAsc(LocalDateTime since);
}
