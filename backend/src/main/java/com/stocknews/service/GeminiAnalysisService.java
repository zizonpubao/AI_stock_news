package com.stocknews.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocknews.entity.AnalysisStatus;
import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.Stock;
import com.stocknews.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiAnalysisService {

    private final StockRepository stockRepository;
    private final ObjectMapper objectMapper;

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    // 과부하(503) 시 순서대로 폴백할 모델들. 앞이 실패하면 다음 모델로 자동 전환.
    @Value("${gemini.api.models:gemini-2.5-flash,gemini-2.0-flash,gemini-1.5-flash}")
    private String[] geminiModels;

    private static final String MODEL_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String API_KEY_HEADER = "x-goog-api-key";

    /**
     * 10개 종목을 한 번의 API 호출로 분석해 저장한다.
     *
     * 이 메서드에는 @Transactional 을 걸지 않는다. 외부 AI 호출은 재시도·모델 폴백까지 수 분이 걸릴 수 있는데,
     * 그 시간 동안 DB 커넥션을 붙잡지 않기 위해서다. 저장은 종목별 짧은 UPDATE 로 처리한다.
     *
     * 실패 시 그럴듯한 대체 문구를 저장하지 않는다. 분석을 비우고 상태를 FAILED 로 남긴다.
     */
    public void analyzeAllStocks(List<Stock> stocks, Map<Long, List<NewsArticle>> newsMap) {
        log.info("Gemini AI 일괄 분석 시작 - 총 {}개 종목 (API 1회 호출)", stocks.size());

        String prompt = buildBatchPrompt(stocks, newsMap);
        log.debug("일괄 프롬프트 길이: {} chars", prompt.length());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
        requestBody.put("generationConfig", Map.of("maxOutputTokens", 16000, "temperature", 0.5));

        // 모델 폴백: 앞 모델이 과부하(503 등)면 다음 모델로 자동 전환
        for (String model : geminiModels) {
            String fullText;
            try {
                fullText = callGemini(model.trim(), requestBody);
            } catch (Exception e) {
                log.warn("Gemini 모델 '{}' 실패 → 다음 모델 시도. 사유: {}", model.trim(), e.getMessage());
                continue;
            }
            log.info("Gemini 분석 성공 (모델: {}, 응답 {}자)", model.trim(), fullText.length());
            saveAnalyses(fullText, stocks);
            return;
        }
        log.error("모든 Gemini 모델 실패 → 전 종목 분석 상태를 FAILED 로 기록 (대체 문구 저장 안 함)");
        markAllFailed(stocks);
    }

    /** 단일 모델 호출 (과부하 503/429 시 백오프 재시도). 실패 시 예외 던짐. */
    private String callGemini(String model, Map<String, Object> requestBody) {
        // API 키는 URL 쿼리(?key=)가 아니라 헤더로 보낸다.
        // URL 에 넣으면 호출 실패 시 예외 메시지·접근 로그에 키가 그대로 남는다.
        String url = MODEL_BASE + model + ":generateContent";

        WebClient client = WebClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(API_KEY_HEADER, geminiApiKey)
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();

        String response = client.post()
                .uri(url)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(String.class)
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(2))
                        .maxBackoff(Duration.ofSeconds(10))
                        .filter(ex -> ex instanceof WebClientResponseException w
                                && (w.getStatusCode().is5xxServerError()
                                    || w.getStatusCode().value() == 429))
                        .doBeforeRetry(rs -> log.warn("  [{}] 재시도 {}회차", model, rs.totalRetries() + 1)))
                .block(Duration.ofSeconds(90));

        JsonNode root;
        try {
            root = objectMapper.readTree(response);
        } catch (Exception e) {
            throw new RuntimeException("응답 파싱 실패: " + e.getMessage());
        }

        String finishReason = root.path("candidates").path(0).path("finishReason").asText("");
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : parts) sb.append(part.path("text").asText());
        String fullText = sb.toString();

        if (fullText.isBlank()) {
            throw new RuntimeException("빈 응답 (finishReason=" + finishReason + ")");
        }
        return fullText;
    }

    private String buildBatchPrompt(List<Stock> stocks, Map<Long, List<NewsArticle>> newsMap) {
        StringBuilder sb = new StringBuilder();
        sb.append("아래는 오늘 한국 주식시장 검색 상위(인기검색) 종목과 관련 뉴스 목록입니다.\n");
        sb.append("각 종목 분석은 반드시 정확히 '[종목1]', '[종목2]', ..., '[종목10]' 형식의 태그로 시작해야 합니다.\n");
        sb.append("태그 형식을 절대 바꾸지 마세요. 오타 없이 정확히 작성해주세요.\n\n");

        for (int i = 0; i < stocks.size(); i++) {
            Stock stock = stocks.get(i);
            List<NewsArticle> articles = newsMap.getOrDefault(stock.getId(), List.of());

            sb.append("=== 종목").append(i + 1).append(": ").append(stock.getName()).append(" ===\n");
            if (articles.isEmpty()) {
                sb.append("- 관련 뉴스 없음\n");
            } else {
                for (NewsArticle a : articles) {
                    sb.append("- ").append(a.getTitle()).append("\n");
                    if (a.getDescription() != null && !a.getDescription().isBlank()) {
                        sb.append("  ").append(a.getDescription()).append("\n");
                    }
                }
            }
            sb.append("\n");
        }

        sb.append("---\n");
        sb.append("각 종목을 아래 형식으로 분석해주세요.\n");
        sb.append("주의: 태그는 반드시 '[종목1]' ~ '[종목10]' 형식만 사용하세요. 절대 다른 형식 사용 금지.\n\n");
        sb.append("[종목N]\n");
        sb.append("📈 주요 이슈\n- 핵심 이유 2~3가지\n\n");
        sb.append("💡 투자 관점 요약\n- 긍정 요인 1~2가지\n- 주의 사항 1가지\n\n");
        sb.append("⚠️ 리스크 요인\n- 단기/중기 리스크 1~2가지\n\n");
        sb.append("* 본 분석은 AI 자동 생성 참고 자료이며, 투자 권유가 아닙니다.\n\n");

        return sb.toString();
    }

    /**
     * AI 응답을 '[종목N]' 태그 기준으로 잘라 종목 순번(0부터) → 분석 텍스트로 돌려준다.
     * 태그가 없거나 내용이 비어 있는 종목은 결과에 포함하지 않는다(호출 측에서 FAILED 처리).
     * DB·네트워크에 의존하지 않는 순수 함수라 단위 테스트로 검증한다.
     */
    static Map<Integer, String> splitByTags(String fullText, int stockCount) {
        Map<Integer, String> result = new LinkedHashMap<>();
        if (fullText == null) return result;

        // 1단계: 각 [종목N] 태그의 시작 위치를 모두 찾기
        int[] positions = new int[stockCount];
        for (int i = 0; i < stockCount; i++) {
            positions[i] = fullText.indexOf(tagOf(i));
        }

        // 2단계: 위치 기반으로 각 종목 텍스트 슬라이싱 (다음에 존재하는 태그 직전까지)
        for (int i = 0; i < stockCount; i++) {
            if (positions[i] == -1) continue;
            int start = positions[i] + tagOf(i).length();
            int end = fullText.length();
            for (int j = i + 1; j < stockCount; j++) {
                if (positions[j] != -1) {
                    end = positions[j];
                    break;
                }
            }
            if (end < start) continue; // 태그 순서가 뒤바뀐 비정상 응답
            String analysis = fullText.substring(start, end).trim();
            if (!analysis.isEmpty()) result.put(i, analysis);
        }
        return result;
    }

    private static String tagOf(int index) {
        return "[종목" + (index + 1) + "]";
    }

    private void saveAnalyses(String fullText, List<Stock> stocks) {
        Map<Integer, String> analyses = splitByTags(fullText, stocks.size());
        for (int i = 0; i < stocks.size(); i++) {
            Stock stock = stocks.get(i);
            String analysis = analyses.get(i);
            if (analysis == null) {
                log.warn("응답에서 {} 분석을 찾지 못함: {} → FAILED 기록", tagOf(i), stock.getName());
                apply(stock, null, AnalysisStatus.FAILED);
                continue;
            }
            apply(stock, analysis, AnalysisStatus.SUCCESS);
            log.info("  분석 저장 완료: {} ({}자)", stock.getName(), analysis.length());
        }
    }

    private void markAllFailed(List<Stock> stocks) {
        for (Stock stock : stocks) {
            apply(stock, null, AnalysisStatus.FAILED);
        }
    }

    private void apply(Stock stock, String analysis, AnalysisStatus status) {
        stockRepository.updateAnalysis(stock.getId(), analysis, status);
        // 호출 측이 들고 있는 객체도 같은 값으로 맞춰 둔다
        stock.setAiAnalysis(analysis);
        stock.setAnalysisStatus(status);
    }
}
