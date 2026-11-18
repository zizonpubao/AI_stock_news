package com.stocknews.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * robots.txt 해석기 (순수 클래스, 네트워크 없음).
 *
 * 규칙: 연속한 User-agent 줄은 한 그룹이다. 에이전트 이름은 대소문자를 무시하고 이름이 같은 그룹이 있으면
 * 그 그룹(들)만, 없으면 '*' 그룹을 쓴다. 가장 긴 경로 규칙이 이기고 길이가 같으면 Allow 가 이긴다.
 * '*' 와 끝의 '$' 를 지원한다. 빈 Disallow 는 전체 허용이다. 내용이 비었거나 규칙이 없으면 전부 허용한다.
 *
 * 한계: Crawl-delay·Sitemap 등 다른 지시어는 무시한다. 경로의 퍼센트 인코딩은 정규화하지 않고 문자열 그대로 비교한다.
 */
final class RobotsRules {

    /** 규칙이 없는 robots (전부 허용) */
    static final RobotsRules ALLOW_ALL = new RobotsRules(List.of());

    private record Rule(boolean allow, int length, Pattern pattern) {}

    private record Group(List<String> agents, List<Rule> rules) {}

    private final List<Group> groups;

    private RobotsRules(List<Group> groups) {
        this.groups = groups;
    }

    static RobotsRules parse(String robotsTxt) {
        if (robotsTxt == null || robotsTxt.isBlank()) return ALLOW_ALL;
        List<Group> groups = new ArrayList<>();
        Group current = null;
        boolean lastWasAgent = false;
        for (String rawLine : robotsTxt.replace("﻿", "").split("\\R")) {
            String line = rawLine;
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            line = line.trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (key.equals("user-agent")) {
                if (!lastWasAgent || current == null) {
                    current = new Group(new ArrayList<>(), new ArrayList<>());
                    groups.add(current);
                }
                current.agents().add(value.toLowerCase(Locale.ROOT));
                lastWasAgent = true;
            } else if (key.equals("allow") || key.equals("disallow")) {
                lastWasAgent = false;
                if (current == null || value.isEmpty()) continue; // 빈 값은 규칙 없음(= 전체 허용)
                current.rules().add(toRule(key.equals("allow"), value));
            }
            // sitemap, crawl-delay 등은 무시한다. 그룹 경계도 바꾸지 않는다.
        }
        return new RobotsRules(groups);
    }

    private static Rule toRule(boolean allow, String path) {
        boolean anchored = path.endsWith("$");
        String body = anchored ? path.substring(0, path.length() - 1) : path;
        StringBuilder regex = new StringBuilder("^");
        StringBuilder literal = new StringBuilder();
        for (char c : body.toCharArray()) {
            if (c == '*') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(".*");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) regex.append(Pattern.quote(literal.toString()));
        if (anchored) regex.append("$");
        return new Rule(allow, path.length(), Pattern.compile(regex.toString(), Pattern.DOTALL));
    }

    /** agentToken 이 pathAndQuery(예: "/a/b?x=1") 를 가져가도 되는지. */
    boolean isAllowed(String agentToken, String pathAndQuery) {
        String path = (pathAndQuery == null || pathAndQuery.isEmpty()) ? "/" : pathAndQuery;
        if (!path.startsWith("/")) path = "/" + path;
        String agent = agentToken == null ? "" : agentToken.trim().toLowerCase(Locale.ROOT);

        List<Rule> rules = rulesFor(agent);
        if (rules.isEmpty()) return true;

        Rule best = null;
        for (Rule r : rules) {
            if (!r.pattern().matcher(path).find()) continue;
            if (best == null || r.length() > best.length()
                    || (r.length() == best.length() && r.allow() && !best.allow())) {
                best = r;
            }
        }
        return best == null || best.allow();
    }

    private List<Rule> rulesFor(String agent) {
        List<Rule> named = new ArrayList<>();
        boolean namedFound = false;
        for (Group g : groups) {
            if (g.agents().contains(agent)) {
                namedFound = true;
                named.addAll(g.rules());
            }
        }
        if (namedFound) return named;
        List<Rule> star = new ArrayList<>();
        for (Group g : groups) {
            if (g.agents().contains("*")) star.addAll(g.rules());
        }
        return star;
    }
}
