package com.stocknews.entity;

/**
 * 뉴스 수집 상태.
 * SUCCESS 는 API 호출이 성공했다는 뜻이다 (조건에 맞는 기사가 0건이어도 SUCCESS).
 * FAILED 는 API 호출 실패 또는 응답을 해석하지 못한 경우다.
 */
public enum NewsStatus {
    PENDING, SUCCESS, FAILED
}
