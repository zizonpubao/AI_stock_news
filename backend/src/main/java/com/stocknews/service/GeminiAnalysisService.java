package com.stocknews.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.Stock;
import com.stocknews.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    @Value("${gemini.api.url}")
    private String geminiApiUrl;

    @Transactional
    public void analyzeAllStocks(List<Stock> stocks, Map<Long, List<NewsArticle>> newsMap) {
        log.info("Gemini AI 일괄 분석 시작 - 총 {}개 종목 (API 1회 호출)", stocks.size());

        String prompt = buildBatchPrompt(stocks, newsMap);
        log.debug("일괄 프롬프트 길이: {} chars", prompt.length());

        try {
            String urlWithKey = geminiApiUrl + "?key=" + geminiApiKey;

            WebClient client = WebClient.builder()
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                    .build();

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("contents", List.of(
                    Map.of("parts", List.of(Map.of("text", prompt)))
            ));
            requestBody.put("generationConfig", Map.of(
                    "maxOutputTokens", 16000,
                    "temperature", 0.5
            ));

            // Gemini 는 과부하 시 503/429 를 자주 반환 → 즉시 더미로 떨어지지 않고 백오프 재시도
            String response = client.post()
                    .uri(urlWithKey)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .retryWhen(Retry.backoff(4, Duration.ofSeconds(3))
                            .maxBackoff(Duration.ofSeconds(20))
                            .filter(ex -> ex instanceof WebClientResponseException w
                                    && (w.getStatusCode().is5xxServerError()
                                        || w.getStatusCode().value() == 429))
                            .doBeforeRetry(rs -> log.warn("Gemini 재시도 {}회차 (사유: {})",
                                    rs.totalRetries() + 1, rs.failure().getMessage())))
                    .block(Duration.ofSeconds(120));

            JsonNode root = objectMapper.readTree(response);

            // finishReason 확인 (STOP이 아니면 응답이 잘린 것)
            String finishReason = root.path("candidates").get(0).path("finishReason").asText();
            log.info("Gemini finishReason: {}", finishReason);
            if (!"STOP".equals(finishReason)) {
                log.warn("Gemini 응답이 완전하지 않을 수 있음 (finishReason={})", finishReason);
            }

            // parts 배열 전체를 이어붙이기 (긴 응답은 여러 parts로 분할될 수 있음)
            JsonNode parts = root.path("candidates").get(0).path("content").path("parts");
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : parts) {
                sb.append(part.path("text").asText());
            }
            String fullText = sb.toString();

            log.info("Gemini 일괄 응답 수신 완료 (응답 길이: {} chars)", fullText.length());
            log.debug("Gemini 응답 원문:\n{}", fullText);

            parseAndSave(fullText, stocks);

        } catch (WebClientResponseException e) {
            log.error("Gemini API 오류: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            saveDummyForAll(stocks);
        } catch (Exception e) {
            log.error("Gemini 일괄 분석 실패: {}", e.getMessage());
            saveDummyForAll(stocks);
        }
    }

    private String buildBatchPrompt(List<Stock> stocks, Map<Long, List<NewsArticle>> newsMap) {
        StringBuilder sb = new StringBuilder();
        sb.append("아래는 오늘 한국 주식시장 급상승 종목과 관련 뉴스 목록입니다.\n");
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
     * [종목N] 태그로 응답을 분리해서 각 종목에 저장
     * 오타 방어: indexOf로 각 태그 위치를 먼저 모두 찾은 뒤 슬라이싱
     */
    private void parseAndSave(String fullText, List<Stock> stocks) {
        // 1단계: 각 [종목N] 태그의 시작 위치를 모두 찾기
        int[] positions = new int[stocks.size()];
        for (int i = 0; i < stocks.size(); i++) {
            String tag = "[종목" + (i + 1) + "]";
            positions[i] = fullText.indexOf(tag);
            if (positions[i] == -1) {
                log.warn("태그 미발견: {} → 더미 저장", tag);
            }
        }

        // 2단계: 위치 기반으로 각 종목 텍스트 슬라이싱
        for (int i = 0; i < stocks.size(); i++) {
            Stock stock = stocks.get(i);

            if (positions[i] == -1) {
                stock.setAiAnalysis(getDummyAnalysis());
                stockRepository.save(stock);
                continue;
            }

            // 태그 길이만큼 건너뛰기
            String tag = "[종목" + (i + 1) + "]";
            int start = positions[i] + tag.length();

            // 다음 태그 위치 찾기 (없으면 끝까지)
            int end = fullText.length();
            for (int j = i + 1; j < stocks.size(); j++) {
                if (positions[j] != -1) {
                    end = positions[j];
                    break;
                }
            }

            String analysis = fullText.substring(start, end).trim();

            stock.setAiAnalysis(analysis);
            stockRepository.save(stock);
            log.info("  분석 저장 완료: {} ({}자)", stock.getName(), analysis.length());
        }
    }

    private void saveDummyForAll(List<Stock> stocks) {
        log.warn("전체 더미 분석 저장");
        for (Stock stock : stocks) {
            stock.setAiAnalysis(getDummyAnalysis());
            stockRepository.save(stock);
        }
    }

    private String getDummyAnalysis() {
        return "📈 주요 이슈\n- 실적 개선 기대감으로 외국인 및 기관 순매수 유입\n- 업황 개선 신호 포착\n\n"
                + "💡 투자 관점 요약\n- 긍정: 단기 모멘텀 강화, 거래량 동반 상승\n- 주의: 단기 급등에 따른 차익실현 가능성\n\n"
                + "⚠️ 리스크 요인\n- 단기: 급등 이후 조정 가능성\n- 중기: 글로벌 경기 불확실성\n\n"
                + "* 본 분석은 AI 자동 생성 참고 자료이며, 투자 권유가 아닙니다.";
    }
}