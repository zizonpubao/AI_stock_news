package com.stocknews.scheduler;

import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.PipelineRun;
import com.stocknews.service.ArticleBodyFetcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 본문 수집 상태를 세어 실행 기록에 옮기는 부분만 검증한다. */
class StockSchedulerBodyCountsTest {

    private static NewsArticle withStatus(String status) {
        NewsArticle a = new NewsArticle();
        a.setTitle("소식");
        a.setBodyStatus(status);
        return a;
    }

    @Test
    @DisplayName("상태별로 사용·robots 제외·실패를 센다")
    void countsByGroup() {
        Map<Long, List<NewsArticle>> news = new HashMap<>();
        news.put(1L, List.of(withStatus(ArticleBodyFetcher.BODY), withStatus(ArticleBodyFetcher.BODY),
                withStatus(ArticleBodyFetcher.ROBOTS_DISALLOWED)));
        news.put(2L, List.of(withStatus(ArticleBodyFetcher.AI_DISALLOWED), withStatus(ArticleBodyFetcher.ROBOTS_UNKNOWN),
                withStatus(ArticleBodyFetcher.FETCH_FAILED), withStatus(ArticleBodyFetcher.EXTRACT_FAILED),
                withStatus(ArticleBodyFetcher.BUDGET_EXCEEDED), withStatus(ArticleBodyFetcher.INVALID_URL)));
        PipelineRun run = new PipelineRun();

        StockScheduler.applyBodyCounts(run, news);

        assertThat(run.getBodiesUsed()).isEqualTo(2);
        assertThat(run.getBodiesRobotsExcluded()).isEqualTo(3);
        assertThat(run.getBodiesFailed()).isEqualTo(4);
    }

    @Test
    @DisplayName("기능이 꺼져 있으면(DISABLED) 세 값 모두 0")
    void disabledIsAllZero() {
        PipelineRun run = new PipelineRun();
        StockScheduler.applyBodyCounts(run, Map.of(1L, List.of(withStatus(ArticleBodyFetcher.DISABLED),
                withStatus(ArticleBodyFetcher.DISABLED))));
        assertThat(run.getBodiesUsed()).isZero();
        assertThat(run.getBodiesRobotsExcluded()).isZero();
        assertThat(run.getBodiesFailed()).isZero();
    }
}
