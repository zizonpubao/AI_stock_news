package com.stocknews.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 직접 지어낸 robots.txt 문자열만 쓴다. */
class RobotsRulesTest {

    @Test
    @DisplayName("전체 금지 + 특정 봇만 허용: 이름이 맞는 그룹만 쓴다 (대소문자 무시)")
    void namedGroupOverridesStar() {
        RobotsRules r = RobotsRules.parse("""
                User-agent: *
                Disallow: /

                User-agent: NewsBodyFetcher
                Allow: /
                """);
        assertThat(r.isAllowed("newsbodyfetcher", "/a/1")).isTrue();
        assertThat(r.isAllowed("OtherBot", "/a/1")).isFalse();
    }

    @Test
    @DisplayName("AI 수집기만 금지하면 다른 에이전트는 허용")
    void onlyAiAgentsBlocked() {
        RobotsRules r = RobotsRules.parse("""
                User-agent: GPTBot
                User-agent: ClaudeBot
                Disallow: /

                User-agent: *
                Allow: /
                """);
        assertThat(r.isAllowed("GPTBot", "/x")).isFalse();
        assertThat(r.isAllowed("ClaudeBot", "/x")).isFalse();
        assertThat(r.isAllowed("NewsBodyFetcher", "/x")).isTrue();
    }

    @Test
    @DisplayName("내용이 비었거나 규칙이 없으면 전부 허용")
    void emptyAllowsAll() {
        assertThat(RobotsRules.parse("").isAllowed("X", "/a")).isTrue();
        assertThat(RobotsRules.parse(null).isAllowed("X", "/a")).isTrue();
        assertThat(RobotsRules.parse("# 주석만\n\n").isAllowed("X", "/a")).isTrue();
        assertThat(RobotsRules.parse("Sitemap: https://example.test/s.xml").isAllowed("X", "/a")).isTrue();
    }

    @Test
    @DisplayName("가장 긴 경로가 이기고 길이가 같으면 Allow 가 이긴다")
    void longestPathWins() {
        RobotsRules r = RobotsRules.parse("""
                User-agent: *
                Disallow: /news
                Allow: /news/open
                Disallow: /same
                Allow: /same
                """);
        assertThat(r.isAllowed("X", "/news/closed")).isFalse();
        assertThat(r.isAllowed("X", "/news/open/1")).isTrue();
        assertThat(r.isAllowed("X", "/same/1")).isTrue();
        assertThat(r.isAllowed("X", "/other")).isTrue();
    }

    @Test
    @DisplayName("* 와 끝의 $ 를 지원한다")
    void wildcardAndEndAnchor() {
        RobotsRules r = RobotsRules.parse("""
                User-agent: *
                Disallow: /*.pdf$
                Disallow: /private*/data
                """);
        assertThat(r.isAllowed("X", "/files/a.pdf")).isFalse();
        assertThat(r.isAllowed("X", "/files/a.pdf?x=1")).isTrue();
        assertThat(r.isAllowed("X", "/files/a.pdfx")).isTrue();
        assertThat(r.isAllowed("X", "/private-1/data")).isFalse();
        assertThat(r.isAllowed("X", "/public/data")).isTrue();
    }

    @Test
    @DisplayName("빈 Disallow 는 전체 허용")
    void emptyDisallowAllowsAll() {
        RobotsRules r = RobotsRules.parse("User-agent: *\nDisallow:\n");
        assertThat(r.isAllowed("X", "/anything")).isTrue();
    }

    @Test
    @DisplayName("주석과 줄 끝 주석을 무시하고 쿼리 문자열도 경로 규칙으로 비교한다")
    void commentsAndQuery() {
        RobotsRules r = RobotsRules.parse("""
                # 맨 위 주석
                User-agent: * # 모두
                Disallow: /view?id=7  # 특정 글
                """);
        assertThat(r.isAllowed("X", "/view?id=7")).isFalse();
        assertThat(r.isAllowed("X", "/view?id=8")).isTrue();
    }
}
