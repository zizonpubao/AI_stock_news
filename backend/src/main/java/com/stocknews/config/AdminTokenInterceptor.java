package com.stocknews.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 관리용 API(수동 갱신, 수동 아카이브) 보호.
 *
 * 이 API 들은 호출할 때마다 크롤링과 유료 AI 호출을 일으키므로 누구나 부를 수 있으면 안 된다.
 * 요청 헤더 X-Admin-Token 값이 서버 환경변수 ADMIN_TOKEN 과 일치할 때만 통과시킨다.
 *  - ADMIN_TOKEN 미설정: 관리용 API 자체를 닫는다(403). 설정을 빠뜨렸을 때 열려 있는 것보다 닫혀 있는 편이 안전하다.
 *  - 토큰 없음/불일치: 401.
 */
@Slf4j
@Component
public class AdminTokenInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Admin-Token";

    private final String adminToken;

    public AdminTokenInterceptor(@Value("${admin.token:}") String adminToken) {
        this.adminToken = adminToken == null ? "" : adminToken.trim();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // CORS 사전 요청(OPTIONS)에는 사용자 정의 헤더가 실리지 않으므로 통과시킨다
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;

        if (adminToken.isEmpty()) {
            log.warn("관리용 API 차단: ADMIN_TOKEN 미설정 ({} {})", request.getMethod(), request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }

        String provided = request.getHeader(HEADER);
        if (provided == null || !constantTimeEquals(provided, adminToken)) {
            log.warn("관리용 API 인증 실패 ({} {}, from {})",
                    request.getMethod(), request.getRequestURI(), request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        return true;
    }

    /** 문자열 비교 시간 차이로 토큰을 추측하지 못하도록 상수 시간 비교를 쓴다. */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
