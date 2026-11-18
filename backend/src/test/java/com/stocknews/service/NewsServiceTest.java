package com.stocknews.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocknews.entity.NewsArticle;
import com.stocknews.repository.NewsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** 네트워크·DB 없이 응답 해석 로직만 검증한다. 현재 시각은 인자로 고정한다. */
class NewsServiceTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 5, 20, 12, 0, 0, 0, ZoneOffset.ofHours(9));
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private final NewsService service = new NewsService(mock(NewsRepository.class), new ObjectMapper());

    /** 링크는 기본적으로 제목마다 다르게 만든다(같은 링크는 중복으로 걸러지므로). 첫 기사 링크는 .../a 로 고정. */
    private static String item(String title, ZonedDateTime pub) {
        return item(title, pub, "https://example.com/" + Math.abs(title.hashCode()));
    }

    private static String item(String title, ZonedDateTime pub, String link) {
        return "{\"title\":\"" + title + "\",\"description\":\"<b>설명</b> 본문\","
                + "\"link\":\"" + link + "\",\"pubDate\":\"" + pub.format(FMT) + "\"}";
    }

    private static String body(String... items) {
        return "{\"items\":[" + String.join(",", items) + "]}";
    }

    @Test
    @DisplayName("48시간이 지난 기사는 제외한다")
    void excludesOlderThan48Hours() throws IOException {
        String json = body(
                item("가나전자 신제품", NOW.minusHours(47)),
                item("가나전자 옛날 기사", NOW.minusHours(49)));

        List<NewsArticle> result = service.parseNews(json, "가나전자", NOW);

        assertThat(result).extracting(NewsArticle::getTitle).containsExactly("가나전자 신제품");
    }

    @Test
    @DisplayName("제목에 종목명이 없는 기사는 제외한다")
    void excludesTitleWithoutStockName() throws IOException {
        String json = body(
                item("다른 회사 소식", NOW.minusHours(1)),
                item("가나전자 실적 발표", NOW.minusHours(2)));

        List<NewsArticle> result = service.parseNews(json, "가나전자", NOW);

        assertThat(result).extracting(NewsArticle::getTitle).containsExactly("가나전자 실적 발표");
    }

    @Test
    @DisplayName("기사는 최대 5건까지만 돌려준다")
    void limitsToFive() throws IOException {
        String[] items = new String[8];
        for (int i = 0; i < items.length; i++) items[i] = item("가나전자 기사" + i, NOW.minusHours(1));

        List<NewsArticle> result = service.parseNews(body(items), "가나전자", NOW);

        assertThat(result).hasSize(5);
        assertThat(result.get(4).getTitle()).isEqualTo("가나전자 기사4");
    }

    @Test
    @DisplayName("제목과 설명의 HTML 태그를 제거한다")
    void stripsHtmlTags() throws IOException {
        String json = body(item("<b>가나전자</b> 호실적", NOW.minusHours(1), "https://example.com/a"));

        List<NewsArticle> result = service.parseNews(json, "가나전자", NOW);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("가나전자 호실적");
        assertThat(result.get(0).getDescription()).isEqualTo("설명 본문");
        assertThat(result.get(0).getLink()).isEqualTo("https://example.com/a");
    }

    @Test
    @DisplayName("조건에 맞는 기사가 없으면 실패가 아니라 빈 목록이다")
    void noMatchingArticlesIsNotAnError() throws IOException {
        assertThat(service.parseNews(body(), "가나전자", NOW)).isEmpty();
        assertThat(service.parseNews(body(item("다른 회사", NOW)), "가나전자", NOW)).isEmpty();
    }

    @Test
    @DisplayName("잘못된 JSON, 빈 응답, items 없는 응답은 해석 실패로 예외를 던진다")
    void unparseableResponseFails() {
        assertThatThrownBy(() -> service.parseNews("{not json", "가나전자", NOW)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> service.parseNews("", "가나전자", NOW)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> service.parseNews(null, "가나전자", NOW)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> service.parseNews("{\"errorMessage\":\"x\"}", "가나전자", NOW))
                .isInstanceOf(IOException.class);
    }

    private List<String> titles(String json) throws IOException {
        return service.parseNews(json, "가나전자", NOW).stream().map(NewsArticle::getTitle).toList();
    }

    @Test
    @DisplayName("제목이 완전히 같은 기사 여러 건은 1건만 남긴다")
    void dedupesIdenticalTitles() throws IOException {
        String t = "가나전자 3분기 영업이익 2조 돌파";
        String json = body(
                item(t, NOW.minusHours(1), "https://a.com/1"),
                item(t, NOW.minusHours(2), "https://b.com/2"),
                item(t, NOW.minusHours(3), "https://c.com/3"));

        assertThat(titles(json)).containsExactly(t);
    }

    @Test
    @DisplayName("머리말·문장부호·따옴표만 다른 제목은 중복으로 처리하고 먼저 나온 기사를 남긴다")
    void dedupesTitlesDifferingByTagsAndPunctuation() throws IOException {
        String json = body(
                item("가나전자 3분기 영업이익 2조 돌파", NOW.minusHours(1), "https://a.com/1"),
                item("[속보] 가나전자, 3분기 영업이익 2조 돌파", NOW.minusHours(2), "https://b.com/2"),
                item("'가나전자' 3분기 영업이익 2조 돌파 (종합)", NOW.minusHours(3), "https://c.com/3"),
                item("[단독][포토] 가나전자 3분기 영업이익 2조 돌파!", NOW.minusHours(4), "https://d.com/4"));

        assertThat(titles(json)).containsExactly("가나전자 3분기 영업이익 2조 돌파");
    }

    @Test
    @DisplayName("조사 한두 글자만 다른 거의 같은 제목은 중복으로 처리한다")
    void dedupesNearlyIdenticalTitles() throws IOException {
        String json = body(
                item("가나전자 3분기 영업이익 2조 돌파", NOW.minusHours(1), "https://a.com/1"),
                item("가나전자 3분기 영업이익 2조원 돌파", NOW.minusHours(2), "https://b.com/2"),
                item("가나전자가 신제품 공개", NOW.minusHours(3), "https://c.com/3"),
                item("가나전자 신제품 공개", NOW.minusHours(4), "https://d.com/4"));

        assertThat(titles(json)).containsExactly("가나전자 3분기 영업이익 2조 돌파", "가나전자가 신제품 공개");
    }

    @Test
    @DisplayName("같은 종목이라도 다른 사건을 다룬 제목은 둘 다 남긴다")
    void keepsDifferentEventsOfSameStock() throws IOException {
        String json = body(
                item("가나전자 3분기 영업이익 2조 돌파", NOW.minusHours(1), "https://a.com/1"),
                item("가나전자 신제품 공개", NOW.minusHours(2), "https://b.com/2"),
                item("가나전자 1분기 영업이익 증가", NOW.minusHours(3), "https://c.com/3"),
                item("가나전자 2분기 영업이익 증가", NOW.minusHours(4), "https://d.com/4"));

        assertThat(titles(json)).hasSize(4);
    }

    @Test
    @DisplayName("같은 링크가 반복되면 제목이 달라도 1건만 남긴다")
    void dedupesSameLink() throws IOException {
        String json = body(
                item("가나전자 신제품 공개", NOW.minusHours(1), "https://a.com/same"),
                item("가나전자 해외 공장 증설 발표", NOW.minusHours(2), "https://a.com/same"));

        assertThat(titles(json)).containsExactly("가나전자 신제품 공개");
    }

    @Test
    @DisplayName("중복을 걸러낸 뒤에도 최대 5건이며 뒤쪽의 서로 다른 기사가 채워진다")
    void limitsToFiveAfterDedupe() throws IOException {
        String dup = "가나전자 3분기 영업이익 2조 돌파";
        String json = body(
                item(dup, NOW.minusHours(1), "https://a.com/1"),
                item("[속보] " + dup, NOW.minusHours(1), "https://a.com/2"),
                item(dup + " (종합)", NOW.minusHours(1), "https://a.com/3"),
                item("가나전자 신제품 공개", NOW.minusHours(1), "https://a.com/4"),
                item("가나전자 해외 공장 증설 발표", NOW.minusHours(1), "https://a.com/5"),
                item("가나전자 대표이사 선임", NOW.minusHours(1), "https://a.com/6"),
                item("가나전자 배당 확대 결정", NOW.minusHours(1), "https://a.com/7"),
                item("가나전자 자사주 매입 소각", NOW.minusHours(1), "https://a.com/8"),
                item("가나전자 노조 파업 돌입", NOW.minusHours(1), "https://a.com/9"));

        assertThat(titles(json)).containsExactly(dup, "가나전자 신제품 공개", "가나전자 해외 공장 증설 발표",
                "가나전자 대표이사 선임", "가나전자 배당 확대 결정");
    }

    @Test
    @DisplayName("제목 유사도는 임계값 경계에서 보수적으로 동작한다")
    void similarityBoundary() {
        // 같은 문장에 글자 하나 추가: 0.75 -> 중복
        String a = NewsService.normalizeTitle("가나전자 3분기 영업이익 2조 돌파", "가나전자");
        String b = NewsService.normalizeTitle("가나전자 3분기 영업이익 2조원 돌파", "가나전자");
        assertThat(NewsService.bigramJaccard(a, b)).isGreaterThanOrEqualTo(NewsService.DUPLICATE_SIMILARITY_THRESHOLD);
        assertThat(NewsService.isSimilarTitle(a, b)).isTrue();

        // 숫자만 다른 분기 기사: 문자 유사도만으로는 임계값을 넘지만 숫자가 다르므로(다른 사실) 묶지 않는다
        String q1 = NewsService.normalizeTitle("가나전자 1분기 영업이익 증가", "가나전자");
        String q2 = NewsService.normalizeTitle("가나전자 2분기 영업이익 증가", "가나전자");
        assertThat(NewsService.bigramJaccard(q1, q2)).isGreaterThanOrEqualTo(NewsService.DUPLICATE_SIMILARITY_THRESHOLD);
        assertThat(NewsService.isSimilarTitle(q1, q2)).isFalse();

        // 완전히 다른 사건
        String c = NewsService.normalizeTitle("가나전자 신제품 공개", "가나전자");
        assertThat(NewsService.isSimilarTitle(a, c)).isFalse();
    }

    // ---- 종목명이 다른 단어의 앞부분으로만 나오는 기사(다른 회사) 제외 ----

    @Test
    @DisplayName("에스엠 종목: 에스엠벡셀 기사는 제외하고 에스엠 기사는 통과시킨다")
    void ownWordSm() {
        assertThat(NewsService.mentionsAsOwnWord("에스엠벡셀, 군용 통신장비용 배터리 공급계약", "에스엠")).isFalse();
        assertThat(NewsService.mentionsAsOwnWord("에스엠 주가 상한가", "에스엠")).isTrue();
        assertThat(NewsService.mentionsAsOwnWord(
                "[주식마감] '한한령 완화 기대감'에 에스엠·스튜디오드래곤 '上'", "에스엠")).isTrue();
    }

    @Test
    @DisplayName("삼성전자 종목: 삼성전자우 기사는 제외하고 조사·쉼표가 붙은 기사는 통과시킨다")
    void ownWordSamsung() {
        assertThat(NewsService.mentionsAsOwnWord("삼성전자우 주가, 11월 18일 158,000원", "삼성전자")).isFalse();
        assertThat(NewsService.mentionsAsOwnWord("삼성전자는 3분기 실적 발표", "삼성전자")).isTrue();
        assertThat(NewsService.mentionsAsOwnWord("삼성전자의 신제품 공개", "삼성전자")).isTrue();
        assertThat(NewsService.mentionsAsOwnWord("삼성전자, 연휴 앞두고 3%대 급등마감", "삼성전자")).isTrue();
        assertThat(NewsService.mentionsAsOwnWord("SK하이닉스發 훈풍에 삼성전자 상승", "SK하이닉스")).isTrue();
    }

    @Test
    @DisplayName("삼성전자우 종목: 삼성전자우 기사는 통과시킨다")
    void ownWordSamsungPreferred() {
        assertThat(NewsService.mentionsAsOwnWord("삼성전자우 주가, 11월 18일 158,000원", "삼성전자우")).isTrue();
    }

    @Test
    @DisplayName("두산 종목: 두산에너빌리티 기사는 제외하고 조사 에가 붙은 두산 기사는 통과시킨다")
    void ownWordDoosan() {
        assertThat(NewsService.mentionsAsOwnWord("두산에너빌리티 자금 투입", "두산")).isFalse();
        assertThat(NewsService.mentionsAsOwnWord("두산에 대한 투자의견 상향", "두산")).isTrue();
    }

    @Test
    @DisplayName("꼬리는 접두가 아니라 전체가 허용 목록과 같아야 한다 (에너빌리티는 조사 에로 통과하지 않는다)")
    void tailMustMatchWholly() {
        assertThat(NewsService.mentionsAsOwnWord("두산에너빌리티", "두산")).isFalse();
        assertThat(NewsService.mentionsAsOwnWord("두산에서 발표", "두산")).isTrue();
        assertThat(NewsService.mentionsAsOwnWord("두산이엔티 인수", "두산")).isFalse();
    }

    @Test
    @DisplayName("한 제목에 종목명이 두 번 나오면 하나라도 독립 등장이면 통과한다")
    void ownWordAnyPosition() {
        assertThat(NewsService.mentionsAsOwnWord("에스엠벡셀·에스엠 동반 상승", "에스엠")).isTrue();
        assertThat(NewsService.mentionsAsOwnWord("에스엠벡셀·에스엠벡셀 동반 상승", "에스엠")).isFalse();
    }

    @Test
    @DisplayName("허용 목록의 꼬리가 붙은 실제 기사 제목은 통과한다 (2026-11-18 아카이브·운영 수집분에서 발췌)")
    void allowedTailsFromRealTitles() {
        String[][] cases = {
                {"삼성전자", "클레온, 삼성전자와 '게임스컴 2026' 참가"},
                {"SK하이닉스", "삼성전자, 3.4% 급등 마감…SK하이닉스는 6.4% 뛰어(종합)"},
                {"SK하이닉스", "삼성전자, 연휴 앞두고 3%대 급등마감…SK하이닉스도 1.2%↑(종합)"},
                {"현대차", "도요타가 꽉 잡은 美 하이브리드 시장... 현대차가 '정면승부' 나섰다"},
                {"삼성SDI", "AI는 전기를 먹고 로봇은 배터리로 걷는다···삼성SDI의 다음 무대는"},
                {"삼성전기", "삼성전기에 꽂힌 상위 1%, 하나마이크론·심텍은 팔았다"},
                {"셀트리온", "지투지바이오, 셀트리온과 손잡고 SC 항체 플랫폼 확장"},
                {"JYP Ent.", "BTS 공연 ‘글로벌 대박’ 쳤는데…엔터 4사 목표가, JYP Ent.만 올라"},
                {"SK하이닉스", "中 CXMT, 삼성전자·SK하이닉스보다 먼저 LPDDR6 스마트폰 상용화"},
                {"두산에너빌리티", "[N2 증시 풍향계] 두산에너빌리티부터 오르비텍까지 원전株 ‘불기둥’"},
                {"삼성전기", "삼성전자·SK하이닉스에 삼성전기까지…퇴직연금 ETF 상장"},
                {"SK텔레콤", "[카카오, 둘로 다시 뛴다] SK텔레콤처럼 둘로 나뉘는 카카오"},
                {"삼성전기", "장덕현 삼성전기사장, 경남 협력사 찾아가 '동반성장' 머리맞댔다"},
                {"현대차", "현대차 ‘전면파업’에 기아도 ‘불안’…현대차그룹 ‘흔들’"},
                {"현대차", "현대차그룹주, 8월 글로벌 판매 호조"},
        };
        for (String[] c : cases) {
            assertThat(NewsService.mentionsAsOwnWord(c[1], c[0])).as("%s / %s", c[0], c[1]).isTrue();
        }
        // 목록의 각 꼬리를 종목명 바로 뒤에 붙여도 통과한다 (데이터에서 관찰하지 못한 문법상 조사 포함)
        for (String tail : NewsService.ALLOWED_NAME_TAILS) {
            assertThat(NewsService.mentionsAsOwnWord("가나전자" + tail + " 소식", "가나전자")).as(tail).isTrue();
        }
    }

    @Test
    @DisplayName("실제 운영에서 걸러져야 했던 다른 회사 기사 제목은 제외한다")
    void excludesRealOtherCompanyTitles() {
        assertThat(NewsService.mentionsAsOwnWord("에스엠벡셀, 군용 무전기·전술통신장비 배터리 공급계약", "에스엠")).isFalse();
        assertThat(NewsService.mentionsAsOwnWord("카카오뱅크, '개인사업자 신용대출' 네이버페이 대출비교 입점", "카카오")).isFalse();
        assertThat(NewsService.mentionsAsOwnWord("삼성전자서비스, 추석 연휴에도 수리 서비스", "삼성전자")).isFalse();
    }

    @Test
    @DisplayName("종목명이 null 이거나 비어 있으면 기존처럼 단순 포함 여부만 따른다")
    void ownWordNullOrEmptyName() {
        assertThat(NewsService.mentionsAsOwnWord("아무 제목", "")).isTrue();
        assertThat(NewsService.mentionsAsOwnWord("아무 제목", null)).isTrue();
        assertThat(NewsService.mentionsAsOwnWord(null, "에스엠")).isFalse();
    }

    @Test
    @DisplayName("parseNews: 에스엠벡셀 기사 3건과 에스엠 기사 2건이 섞여 있으면 에스엠 기사만 남긴다")
    void parseNewsDropsOtherCompany() throws IOException {
        String json = body(
                item("에스엠벡셀, 군용 통신장비용 배터리 공급계약", NOW.minusHours(1), "https://a.com/1"),
                item("에스엠 주가 상한가", NOW.minusHours(2), "https://a.com/2"),
                item("에스엠벡셀, 군용 무전기·전술통신장비 배터리 공급계약", NOW.minusHours(3), "https://a.com/3"),
                item("[주식마감] '한한령 완화 기대감'에 에스엠·스튜디오드래곤 '上'", NOW.minusHours(4), "https://a.com/4"),
                item("에스엠벡셀 2차전지 증설 검토", NOW.minusHours(5), "https://a.com/5"));

        List<NewsArticle> result = service.parseNews(json, "에스엠", NOW);

        assertThat(result).extracting(NewsArticle::getTitle).containsExactly(
                "에스엠 주가 상한가", "[주식마감] '한한령 완화 기대감'에 에스엠·스튜디오드래곤 '上'");
    }
}
