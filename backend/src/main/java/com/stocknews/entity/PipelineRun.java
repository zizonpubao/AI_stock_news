package com.stocknews.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 수집 사이클 한 번의 결과 기록. 사이클이 끝날 때마다 1행 저장한다.
 *
 * 문자열 값(trigger, outcome)은 enum 이 아닌 일반 String 컬럼이다.
 * 운영 PostgreSQL 에서 ddl-auto=update 는 enum CHECK 제약을 갱신하지 못해 값을 추가할 때 문제가 되기 때문이다.
 * 예외 메시지 전문은 저장하지 않는다 (내부 정보 노출 방지, 로그에만 남긴다).
 */
@Entity
@Table(name = "pipeline_run")
@Getter @Setter @NoArgsConstructor
public class PipelineRun {

    public static final String TRIGGER_STARTUP = "STARTUP";
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";
    public static final String TRIGGER_MANUAL = "MANUAL";

    public static final String OUTCOME_OK = "OK";
    public static final String OUTCOME_COLLECT_FAILED = "COLLECT_FAILED";
    public static final String OUTCOME_NEWS_FAILED = "NEWS_FAILED";       // 종목은 수집했지만 뉴스 호출이 전부 실패
    public static final String OUTCOME_AI_CALL_FAILED = "AI_CALL_FAILED";
    public static final String OUTCOME_PARTIAL = "PARTIAL";
    public static final String OUTCOME_ERROR = "ERROR";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long totalElapsedMs;

    /** STARTUP / SCHEDULED / MANUAL. trigger 는 SQL 예약어일 수 있어 컬럼 이름만 다르게 한다. */
    @Column(name = "run_trigger", length = 20)
    private String trigger;

    private Boolean collectOk;
    private Integer stockCount;

    private Integer newsSuccess;
    private Integer newsFailed;
    /** 저장된 기사 총수 */
    private Integer newsArticles;

    @Column(length = 100) private String analysisModel;
    private Integer analysisRequested;
    private Integer analysisSuccess;
    private Integer analysisFailed;
    /** 뉴스가 없어 건너뛴 종목 수 */
    private Integer analysisSkipped;
    private Integer promptTokens;
    private Integer outputTokens;
    private Long analysisElapsedMs;

    /** OK / COLLECT_FAILED / AI_CALL_FAILED / PARTIAL / ERROR */
    @Column(length = 30)
    private String outcome;
}
