package com.stocknews.entity;

/**
 * 분석이 FAILED 일 때의 사유. 컬럼에는 name() 을 일반 문자열로 저장한다.
 * (@Enumerated 로 매핑하지 않는다: 운영 DB 의 enum CHECK 제약 때문에 값 추가 시 저장이 실패할 수 있다.)
 */
public enum AnalysisFailReason {
    /** 근거로 쓸 뉴스가 없어 분석을 요청하지 않음 */
    NO_NEWS,
    /** 모든 모델 호출 실패 */
    AI_CALL_FAILED,
    /** 응답에서 이 종목의 유효한 분석을 얻지 못함 */
    INVALID_RESPONSE
}
