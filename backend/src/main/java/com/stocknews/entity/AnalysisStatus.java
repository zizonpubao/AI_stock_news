package com.stocknews.entity;

/**
 * AI 분석 결과의 상태.
 * 외부 AI 호출이 실패했을 때 그럴듯한 대체 문구를 저장하지 않고, 실패했다는 사실 자체를 저장하기 위한 값.
 */
public enum AnalysisStatus {
    /** 수집 직후, 아직 분석 결과가 없음 */
    PENDING,
    /** AI 가 실제로 생성한 분석이 저장됨 */
    SUCCESS,
    /** AI 호출 실패 또는 응답에서 해당 종목 분석을 찾지 못함 (aiAnalysis 는 null) */
    FAILED
}
