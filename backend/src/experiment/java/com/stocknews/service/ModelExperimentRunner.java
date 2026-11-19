package com.stocknews.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.stocknews.entity.NewsArticle;
import com.stocknews.entity.Stock;
import com.stocknews.service.ExperimentConfig.ModelSpec;
import com.stocknews.service.ExperimentStats.Aggregate;
import com.stocknews.service.ExperimentStats.Price;
import com.stocknews.service.ExperimentStats.RunRecord;
import com.stocknews.service.ProviderClient.ProviderResult;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 모델 비교 실험 실행기 (실험 전용 — 운영 서버에서 돌리지 않는다. GitHub Actions 에서 `./gradlew modelExperiment`).
 *
 * 운영 공개 API 에서 현재 종목·뉴스를 읽고, 운영과 같은 프롬프트(GeminiAnalysisService.buildPrompt)와 같은 검증
 * (parseAndValidate, ModelExperimentService.buildResult)을 모든 모델에 똑같이 적용한다. 운영 DB·서버에는 쓰지 않는다.
 *
 * 설정(환경변수): EXPERIMENT_API_BASE, EXPERIMENT_MODELS, EXPERIMENT_RUNS, EXPERIMENT_VARIANTS(BASE,NUMBERS 쉼표 목록, 기본 둘 다). 키: GEMINI_API_KEY(Gemma 포함), ANTHROPIC_API_KEY, OPENAI_API_KEY,
 * UPSTAGE_API_KEY, CLOVA_API_KEY, GROQ_API_KEY, DEEPSEEK_API_KEY.
 * 종료 코드: 0 정상, 1 호출한 모든 모델이 한 번도 성공하지 못함, 2 설정·입력 오류.
 */
public final class ModelExperimentRunner {

    static final String DEFAULT_API_BASE = "http://211.188.54.232:8080";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 한 모델의 실행 결과 묶음 */
    record ModelOutcome(ModelSpec spec, boolean skippedNoKey, List<String> requestSettings, Price price,
                        List<VariantRuns> variants) {}

    /** 한 모델의 한 프롬프트 변형에 대한 반복 기록과 집계 (호출 순서대로) */
    record VariantRuns(PromptVariant variant, List<RunRecord> runs, Aggregate aggregate) {}

    /** 입력 스냅샷: 뉴스가 있는 종목과 그 기사 (운영 analyzeAllStocks 와 같은 방식, 기사는 id 오름차순) */
    record Input(List<Stock> stocks, List<List<NewsArticle>> news, int articleCount) {}

    /**
     * 이 키로 generateContent 를 호출할 수 있는 Gemini API 모델 ID 목록.
     * 문서에 적힌 모델 이름이 실제 호출에서 404 가 나는 경우(1차 실험의 gemini-2.5-flash-lite)를 확인하기 위한 것이다.
     * 실패하면 빈 목록을 돌려준다. 키와 응답 본문은 어디에도 출력하지 않는다.
     */
    static List<String> listGeminiModels(ObjectMapper mapper, String apiKey) {
        List<String> ids = new ArrayList<>();
        if (apiKey == null || apiKey.isBlank()) return ids;
        try {
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10)).build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000"))
                    .header("x-goog-api-key", apiKey)
                    .timeout(java.time.Duration.ofSeconds(20)).GET().build();
            java.net.http.HttpResponse<String> res = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return ids;
            for (com.fasterxml.jackson.databind.JsonNode m : mapper.readTree(res.body()).path("models")) {
                boolean generate = false;
                for (com.fasterxml.jackson.databind.JsonNode method : m.path("supportedGenerationMethods")) {
                    if ("generateContent".equals(method.asText())) generate = true;
                }
                String name = m.path("name").asText("");
                if (generate && name.startsWith("models/")) ids.add(name.substring("models/".length()));
            }
            java.util.Collections.sort(ids);
        } catch (Exception e) {
            // 목록 조회 실패는 실험 결과에 영향을 주지 않는다
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new ArrayList<>();
        }
        return ids;
    }

    static String geminiModelsSection(List<String> ids) {
        StringBuilder sb = new StringBuilder("\n## 이 키로 호출할 수 있는 Gemini API 모델 ID\n\n");
        if (ids.isEmpty()) {
            sb.append("조회하지 못함 (키 없음 또는 목록 조회 실패).\n");
            return sb.toString();
        }
        sb.append("Gemini API 의 모델 목록(generateContent 지원)에서 flash·gemma 가 이름에 들어간 것만 적는다. 전체 ")
                .append(ids.size()).append("개.\n\n");
        for (String id : ids) {
            if (id.contains("flash") || id.contains("gemma")) sb.append("- ").append(id).append("\n");
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        System.exit(new ModelExperimentRunner().run());
    }

    int run() {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(out);
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

        List<ModelSpec> specs;
        List<PromptVariant> variants;
        int runs;
        int callDelaySeconds;
        String base = envOr("EXPERIMENT_API_BASE", DEFAULT_API_BASE);
        try {
            callDelaySeconds = ExperimentConfig.parseCallDelaySeconds(System.getenv("EXPERIMENT_CALL_DELAY_SECONDS"));
            specs = ExperimentConfig.parseModels(System.getenv("EXPERIMENT_MODELS"));
            runs = ExperimentConfig.parseRuns(System.getenv("EXPERIMENT_RUNS"));
            variants = PromptVariant.parse(System.getenv("EXPERIMENT_VARIANTS"));
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            if (!base.startsWith("http://") && !base.startsWith("https://")) {
                throw new IllegalArgumentException("EXPERIMENT_API_BASE 는 http:// 또는 https:// 로 시작해야 합니다.");
            }
        } catch (IllegalArgumentException e) {
            out.println("설정 오류: " + e.getMessage());
            return 2;
        }

        Input input;
        try {
            input = readInput(base, mapper);
        } catch (Exception e) {
            // 예외 메시지는 URL 정도만 담지만, 원인 유형만 짧게 낸다
            out.println("입력 읽기 실패: " + e.getClass().getSimpleName());
            return 2;
        }
        if (input.stocks().isEmpty()) {
            out.println("입력 오류: 뉴스가 있는 종목이 없어 모델을 호출하지 않습니다.");
            return 2;
        }

        GeminiProviderClient gemini = new GeminiProviderClient(mapper);
        Map<String, ProviderClient> clients = new LinkedHashMap<>();
        clients.put("gemini", gemini);
        clients.put("claude", new ClaudeProviderClient());
        clients.put("openai", new OpenAiProviderClient(CompatProviders.NATIVE));
        for (CompatProviders.Def def : CompatProviders.COMPATIBLE) clients.put(def.name(), new OpenAiProviderClient(def));

        // 운영과 같은 프롬프트·같은 스키마. 기준은 지침 없는 프롬프트이고 NUMBERS 변형이 운영 지침을 붙인다(= 운영 프롬프트).
        String basePrompt = gemini.service().buildPrompt(input.stocks(), input.news(), false);
        // 변형별 프롬프트: 지침 없는 프롬프트 + 변형의 추가 지침 (스키마 요약은 호출 수준에 따라 그 뒤에 붙는다)
        Map<PromptVariant, String> prompts = new LinkedHashMap<>();
        Map<PromptVariant, Integer> promptChars = new LinkedHashMap<>();
        for (PromptVariant v : variants) {
            prompts.put(v, v.apply(basePrompt));
            promptChars.put(v, prompts.get(v).length());
        }
        Map<String, Object> schema = GeminiAnalysisService.responseSchema();
        // JSON_MODE·PROMPT_ONLY 호출에만 프롬프트 뒤에 덧붙이는 문구 (모든 해당 모델에 같다)
        String schemaNote = FormatControl.schemaNote(schema);
        int[] newsCounts = input.news().stream().mapToInt(List::size).toArray();

        Map<String, Price> prices;
        try (InputStream in = ModelExperimentRunner.class.getResourceAsStream("/model-prices.json")) {
            prices = ExperimentStats.parsePrices(mapper, in);
        } catch (IOException e) {
            out.println("가격표 해석 실패: 비용은 null 로 표시합니다.");
            prices = Map.of();
        }

        OffsetDateTime ranAt = OffsetDateTime.now(KST);
        out.println("모델 비교 실험 시작: 종목 " + input.stocks().size() + "개, 기사 " + input.articleCount()
                + "건, 프롬프트 " + promptChars + "자, 모델 " + specs.size() + "개 x 변형 " + variants.size() + "개 x " + runs + "회");
        out.println("호출 간격: " + callDelaySeconds + "초 (모든 모델의 호출 사이에 같게 둔다. 소요 시간에는 포함되지 않는다)");

        boolean firstCall = true;
        List<ModelOutcome> outcomes = new ArrayList<>();
        for (ModelSpec spec : specs) {
            ProviderClient client = clients.get(spec.provider());
            List<String> settings = client.requestSettings(spec.modelId());
            Price price = prices.get(spec.key());
            if (!client.isConfigured()) {
                out.println("[" + spec.key() + "] SKIPPED_NO_KEY (키 환경변수가 설정되지 않아 호출하지 않음)");
                outcomes.add(new ModelOutcome(spec, true, settings, price, List.of()));
                continue;
            }
            Map<PromptVariant, List<RunRecord>> byVariant = new LinkedHashMap<>();
            for (PromptVariant v : variants) byVariant.put(v, new ArrayList<>());
            for (int i = 1; i <= runs; i++) {
                // 반복마다 변형 호출 순서를 돌려 시간대 차이가 한 변형에 몰리지 않게 한다
                for (PromptVariant v : PromptVariant.order(variants, i)) {
                    if (!firstCall && callDelaySeconds > 0) {
                        try {
                            Thread.sleep(callDelaySeconds * 1000L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    firstCall = false;
                    RunRecord r = runOnce(client, spec, i, v, prompts.get(v), schemaNote, schema, input, newsCounts, price, mapper);
                    byVariant.get(v).add(r);
                    out.println("[" + spec.key() + " / " + v.name() + "] " + i + "/" + runs + " " + (r.callOk() ? (r.jsonParsed() ? "OK" : r.errorCode()) : r.errorCode())
                            + " " + r.elapsedMs() + "ms [" + String.join(", ", r.formatAttempts()) + "]");
                }
            }
            List<VariantRuns> vrs = new ArrayList<>();
            for (PromptVariant v : variants) {
                vrs.add(new VariantRuns(v, byVariant.get(v), ExperimentStats.aggregate(byVariant.get(v))));
            }
            outcomes.add(new ModelOutcome(spec, false, settings, price, vrs));
        }

        Path dir = Path.of(envOr("EXPERIMENT_OUT_DIR", "build/experiment"));
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("result.json"),
                    mapper.writeValueAsString(resultJson(ranAt, base, input, variants, prompts, runs, outcomes, schemaNote, clients)),
                    StandardCharsets.UTF_8);
            String summary = ExperimentReport.render(ranAt, base, input.stocks().size(), input.articleCount(),
                    promptChars, runs, variants, outcomes, schemaNote)
                    + geminiModelsSection(listGeminiModels(mapper, System.getenv("GEMINI_API_KEY")));
            Files.writeString(dir.resolve("summary.md"), summary, StandardCharsets.UTF_8);
            List<ExperimentOutputs.StockInfo> infos = new ArrayList<>();
            for (int i = 0; i < input.stocks().size(); i++) {
                List<String> titles = new ArrayList<>();
                for (NewsArticle a : input.news().get(i)) titles.add(a.getTitle());
                infos.add(new ExperimentOutputs.StockInfo(input.stocks().get(i).getName(), titles));
            }
            Files.writeString(dir.resolve("outputs.json"),
                    mapper.writeValueAsString(ExperimentOutputs.json(ranAt, infos, variants, outcomes)), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("outputs.md"),
                    ExperimentOutputs.markdown(ranAt, infos, variants, outcomes), StandardCharsets.UTF_8);
            out.println();
            out.println(summary);
            out.println("결과 파일: " + dir.resolve("result.json") + ", " + dir.resolve("summary.md") + ", "
                    + dir.resolve("outputs.json") + ", " + dir.resolve("outputs.md"));
        } catch (IOException e) {
            out.println("결과 파일 쓰기 실패: " + e.getClass().getSimpleName());
            return 2;
        }

        // 호출한 모델이 있는데 하나도 성공(JSON 해석까지)하지 못했으면 실패로 끝낸다. 일부만 실패하면 정상 종료.
        boolean attempted = false;
        boolean anySuccess = false;
        for (ModelOutcome o : outcomes) {
            if (o.skippedNoKey()) continue;
            attempted = true;
            for (VariantRuns vr : o.variants()) if (vr.aggregate().jsonParsed() > 0) anySuccess = true;
        }
        if (!attempted) {
            out.println("경고: 키가 설정된 제공자가 없어 호출한 모델이 없습니다.");
            return 0;
        }
        return anySuccess ? 0 : 1;
    }

    // ===== 한 번 호출 + 지표 =====

    private RunRecord runOnce(ProviderClient client, ModelSpec spec, int run, PromptVariant variant, String prompt, String schemaNote,
                              Map<String, Object> schema, Input input, int[] newsCounts, Price price, ObjectMapper mapper) {
        long t0 = System.nanoTime();
        // 처음 수준의 요청을 400 계열로 거부하면 한 단계씩 내려가며 다시 부른다. 스키마로 강제하지 않는 수준에만 스키마 요약을 덧붙인다.
        FormatControl.Outcome fo = FormatControl.execute(client.formatLadder(spec.modelId()),
                level -> client.call(spec.modelId(), level.appendsSchemaNote() ? prompt + schemaNote : prompt, schema, level));
        ProviderResult r = fo.result();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Double cost = ExperimentStats.cost(price, r.inputTokens(), r.outputTokens());
        int n = newsCounts.length;
        String level = fo.level().name();

        if (r.errorCode() != null || r.text() == null) {
            return new RunRecord(run, false, r.errorCode() == null ? "EMPTY_RESPONSE" : r.errorCode(), ms,
                    r.inputTokens(), r.outputTokens(), r.reasoningTokens(), cost,
                    false, n, 0, 0, 0, 0, 0, 0, null, 0, 0, 0, List.of(), level, fo.attempts(), false,
                    variant.name(), 0, 0, List.of());
        }

        // 스키마로 강제하지 않은 수준은 코드펜스·앞뒤 설명 문장이 붙어 올 수 있어 검증 전에 첫 JSON 만 뽑는다
        String text = r.text();
        boolean unwrapped = false;
        if (fo.level().unwrapsResponse()) {
            FormatControl.Extracted ex = FormatControl.extractJson(text);
            text = ex.json();
            unwrapped = ex.unwrapped();
        }

        GeminiAnalysisService.ValidationStats stats = new GeminiAnalysisService.ValidationStats();
        Map<Integer, GeminiAnalysisService.StockAnalysis> analyses;
        try {
            analyses = GeminiAnalysisService.parseAndValidate(mapper, text, newsCounts, stats);
        } catch (IOException e) {
            return new RunRecord(run, true, "INVALID_JSON", ms,
                    r.inputTokens(), r.outputTokens(), r.reasoningTokens(), cost,
                    false, n, 0, 0, 0, 0, 0, 0, null, 0, 0, 0, List.of(), level, fo.attempts(), unwrapped,
                    variant.name(), 0, 0, List.of());
        }

        // 지표 정의는 운영 모델 비교(ModelExperimentService.buildResult)와 같다
        ModelExperimentService.ModelResult base = ModelExperimentService.buildResult(spec.key(), ms,
                new GeminiAnalysisService.ModelResponse(text, r.inputTokens(), r.outputTokens(), r.reasoningTokens()),
                n, analyses, stats);

        List<NumericGrounding.StockInput> groundingInputs = new ArrayList<>();
        List<ExperimentStats.StockOut> outputs = new ArrayList<>();
        for (Map.Entry<Integer, GeminiAnalysisService.StockAnalysis> e : analyses.entrySet()) {
            int idx = e.getKey();
            List<String> texts = new ArrayList<>();
            for (NewsArticle a : input.news().get(idx)) {
                texts.add(NumericGrounding.articleText(a.getTitle(), a.getDescription(),
                        GeminiAnalysisService.MAX_DESCRIPTION_CHARS));
            }
            groundingInputs.add(new NumericGrounding.StockInput(input.stocks().get(idx).getName(), texts, e.getValue().issues()));
            // outputs.json·outputs.md 용: 이슈 문장, 근거 번호, 확인되지 않은 숫자 (이슈만 저장한다)
            List<ExperimentStats.IssueOut> issueOuts = new ArrayList<>();
            for (GeminiAnalysisService.Issue is : e.getValue().issues()) {
                issueOuts.add(new ExperimentStats.IssueOut(is.text(), is.sources(), NumericGrounding.unverifiedOf(texts, is)));
            }
            outputs.add(new ExperimentStats.StockOut(idx, issueOuts));
        }
        NumericGrounding.Result g = NumericGrounding.evaluate(groundingInputs);

        return new RunRecord(run, true, null, ms,
                r.inputTokens(), r.outputTokens(), r.reasoningTokens(), cost,
                true, base.stocksRequested(), base.stocksReturned(), base.stocksValid(),
                base.issuesTotal(), base.issuesWithValidSources(), base.invalidSourceRefs(), base.issuesDroppedNoSource(),
                base.avgIssueChars(), g.issuesWithNumbers(), g.issuesAllConfirmed(), g.unverifiedNumbers(), g.examples(),
                level, fo.attempts(), unwrapped, variant.name(), g.issuesEvaluated(), g.numbersTotal(), outputs);
    }

    // ===== 입력 읽기 (운영 공개 API, 읽기 전용) =====

    static Input readInput(String base, ObjectMapper mapper) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        JsonNode top = getJson(http, mapper, base + "/api/stocks/top10");
        if (!top.isArray()) throw new IOException("top10 응답이 배열이 아님");

        List<Stock> stocks = new ArrayList<>();
        List<List<NewsArticle>> news = new ArrayList<>();
        int articleCount = 0;
        for (JsonNode s : top) {
            long id = s.path("id").asLong(-1);
            if (id < 0) continue;
            JsonNode body = getJson(http, mapper, base + "/api/stocks/" + id + "/news");
            JsonNode items = body.path("news");
            if (!items.isArray() || items.isEmpty()) continue; // 운영과 같이 뉴스 없는 종목은 요청에서 뺀다
            Stock stock = new Stock();
            stock.setId(id);
            stock.setName(s.path("name").asText(""));
            List<NewsArticle> articles = new ArrayList<>();
            for (JsonNode n : items) { // 서버가 id 오름차순으로 준다 — 이 순서가 인용 번호
                NewsArticle a = new NewsArticle();
                a.setTitle(n.path("title").asText(""));
                a.setDescription(n.path("description").isNull() ? null : n.path("description").asText(null));
                articles.add(a);
            }
            stocks.add(stock);
            news.add(articles);
            articleCount += articles.size();
        }
        return new Input(stocks, news, articleCount);
    }

    private static JsonNode getJson(HttpClient http, ObjectMapper mapper, String url) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build();
                HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode());
                return mapper.readTree(new String(res.body(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                last = e;
            }
        }
        throw last;
    }

    // ===== result.json =====

    private static Map<String, Object> resultJson(OffsetDateTime ranAt, String base, Input input, List<PromptVariant> variants,
                                                  Map<PromptVariant, String> prompts, int runs, List<ModelOutcome> outcomes,
                                                  String schemaNote, Map<String, ProviderClient> clients) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("ranAt", ranAt.toString());
        root.put("apiBase", base);
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("stocks", input.stocks().size());
        in.put("articles", input.articleCount());
        root.put("input", in);
        // 변형별 프롬프트 (운영 프롬프트 + 추가 지침, 스키마 요약 제외)
        Map<String, Object> pv = new LinkedHashMap<>();
        for (PromptVariant v : variants) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("promptChars", prompts.get(v).length());
            m.put("promptSha256", sha256(prompts.get(v)));
            m.put("appendedText", v.suffix());
            pv.put(v.name(), m);
        }
        root.put("variants", pv);
        root.put("callOrder", "모델마다 반복 i 에서 변형 목록을 (i-1)칸 돌린 순서로 번갈아 호출");
        // JSON_MODE·PROMPT_ONLY 호출에만 프롬프트 뒤에 덧붙인 문구 (모든 해당 모델에 같다)
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("text", schemaNote);
        note.put("chars", schemaNote.length());
        note.put("appliedAtLevels", List.of(FormatLevel.JSON_MODE.name(), FormatLevel.PROMPT_ONLY.name()));
        root.put("schemaNoteAppended", note);
        root.put("runsPerModel", runs);
        List<Object> models = new ArrayList<>();
        for (ModelOutcome o : outcomes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", o.spec().provider());
            m.put("model", o.spec().modelId());
            m.put("status", o.skippedNoKey() ? "SKIPPED_NO_KEY" : "RAN");
            m.put("formatLadder", clients.get(o.spec().provider()).formatLadder(o.spec().modelId()));
            m.put("requestSettings", o.requestSettings());
            m.put("price", o.price());
            List<Object> vs = new ArrayList<>();
            for (VariantRuns vr : o.variants()) {
                Map<String, Object> vm = new LinkedHashMap<>();
                vm.put("variant", vr.variant().name());
                vm.put("runs", vr.runs());
                vm.put("aggregate", vr.aggregate());
                vs.add(vm);
            }
            m.put("byVariant", vs);
            models.add(m);
        }
        root.put("models", models);
        return root;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v.trim();
    }
}
