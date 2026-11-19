package com.stocknews.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocknews.repository.NewsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 스프링 컨텍스트 안에서 별칭 설정이 바인딩되고 NewsService 가 그 빈을 주입받아 뜨는지 확인한다.
 * (다른 테스트는 NewsService 를 new 로 만들기 때문에 실제 주입 경로를 지나지 않는다.)
 * DB·네트워크를 쓰지 않도록 필요한 빈만 올린다.
 */
class StockAliasesContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withBean(NewsRepository.class, () -> mock(NewsRepository.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(ArticleBodyFetcher.class, ArticleBodyFetcher::disabled)
            .withUserConfiguration(StockAliases.class, NewsService.class)
            .withPropertyValues(
                    "naver.api.client-id=test-id",
                    "naver.api.client-secret=test-secret",
                    "naver.api.news-url=http://localhost/none",
                    "news.aliases.by-code.035420[0]=네이버",
                    "news.aliases.by-code.035900[0]=JYP엔터테인먼트",
                    "news.aliases.by-code.035900[1]=JYP엔터");

    @Test
    @DisplayName("컨텍스트가 뜨고, 종목코드 키(앞자리 0 포함)로 별칭이 바인딩된다")
    void bindsInContext() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(NewsService.class);
            StockAliases aliases = ctx.getBean(StockAliases.class);
            assertThat(aliases.aliasesFor("035420", "NAVER")).containsExactly("네이버");
            assertThat(aliases.aliasesFor("035900", "JYP Ent.")).containsExactly("JYP엔터테인먼트", "JYP엔터");
            assertThat(aliases.aliasesFor("005930", "삼성전자")).isEqualTo(List.of());
        });
    }

    @Test
    @DisplayName("NEWS_ALIASES_ENABLED=false 에 해당하는 설정이면 별칭이 비어 있다")
    void disabledBySwitch() {
        runner.withPropertyValues("news.aliases.enabled=false").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(StockAliases.class).aliasesFor("035420", "NAVER")).isEmpty();
        });
    }
}
