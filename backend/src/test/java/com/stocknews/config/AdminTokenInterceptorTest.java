package com.stocknews.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AdminTokenInterceptorTest {

    private static final String TOKEN = "test-token-value";

    private static MockHttpServletRequest post(String headerValue) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/stocks/refresh");
        if (headerValue != null) req.addHeader(AdminTokenInterceptor.HEADER, headerValue);
        return req;
    }

    @Test
    @DisplayName("올바른 토큰이면 통과")
    void validTokenPasses() {
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean allowed = new AdminTokenInterceptor(TOKEN).preHandle(post(TOKEN), res, new Object());

        assertThat(allowed).isTrue();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("토큰 헤더가 없으면 401")
    void missingTokenRejected() {
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean allowed = new AdminTokenInterceptor(TOKEN).preHandle(post(null), res, new Object());

        assertThat(allowed).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("토큰이 틀리면 401")
    void wrongTokenRejected() {
        MockHttpServletResponse res = new MockHttpServletResponse();

        boolean allowed = new AdminTokenInterceptor(TOKEN).preHandle(post("wrong"), res, new Object());

        assertThat(allowed).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("서버에 토큰이 설정되지 않았으면 어떤 값을 보내도 403 (설정 누락 시 닫힌 상태가 기본)")
    void unconfiguredTokenClosesApi() {
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        AdminTokenInterceptor interceptor = new AdminTokenInterceptor("");

        assertThat(interceptor.preHandle(post(""), res1, new Object())).isFalse();
        assertThat(interceptor.preHandle(post("anything"), res2, new Object())).isFalse();
        assertThat(res1.getStatus()).isEqualTo(403);
        assertThat(res2.getStatus()).isEqualTo(403);
    }
}
