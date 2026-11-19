package com.stocknews.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockAliasesTest {

    private static StockAliases with(boolean enabled, Map<String, List<String>> map) {
        StockAliases a = new StockAliases();
        a.setEnabled(enabled);
        a.setByCode(new LinkedHashMap<>(map));
        return a;
    }

    @Test
    @DisplayName("enabled=false 이면 빈 목록")
    void disabled() {
        assertThat(with(false, Map.of("035420", List.of("네이버"))).aliasesFor("035420", "NAVER")).isEmpty();
    }

    @Test
    @DisplayName("없는 코드·null 코드는 빈 목록, none() 도 빈 목록")
    void unknownCode() {
        StockAliases a = with(true, Map.of("035420", List.of("네이버")));
        assertThat(a.aliasesFor("005930", "삼성전자")).isEmpty();
        assertThat(a.aliasesFor(null, "NAVER")).isEmpty();
        assertThat(StockAliases.none().aliasesFor("035420", "NAVER")).isEmpty();
    }

    @Test
    @DisplayName("공식명과 같은 값·빈 값·중복은 빼고 trim 한다. 결과는 불변")
    void cleansValues() {
        StockAliases a = with(true, Map.of("035420", List.of(" 네이버 ", "NAVER", "", "  ", "네이버", "N")));
        List<String> r = a.aliasesFor("035420", "NAVER");
        assertThat(r).containsExactly("네이버", "N");
        assertThatThrownBy(() -> r.add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("실제 application.yml 이 종목코드 문자열(앞자리 0 유지)로 바인딩된다")
    void bindsFromApplicationYml() throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        sources.forEach(s -> env.getPropertySources().addLast(s));

        StockAliases bound = Binder.get(env).bind("news.aliases", StockAliases.class).get();

        assertThat(bound.isEnabled()).isTrue();
        assertThat(bound.getByCode()).containsKeys("035420", "035900", "041510");
        assertThat(bound.aliasesFor("035420", "NAVER")).containsExactly("네이버");
        assertThat(bound.aliasesFor("035900", "JYP Ent.")).containsExactly("JYP엔터테인먼트", "JYP엔터");
        assertThat(bound.aliasesFor("041510", "에스엠")).containsExactly("SM엔터테인먼트", "SM엔터");
    }
}
