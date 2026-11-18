# 모델 비교 실험 결과

- 실험 일시: 2026-11-18 19:38:05 +09:00
- 입력 출처: http://211.188.54.232:8080 의 공개 API (읽기 전용)
- 입력 규모: 뉴스가 있는 종목 10개, 기사 47건, 프롬프트 BASE 9174자, NUMBERS 9303자
- 프롬프트 변형: BASE, NUMBERS
- 모델·변형당 반복: 3회

## 프롬프트 변형과 호출 순서

- BASE: 운영 프롬프트(GeminiAnalysisService.buildPrompt)를 그대로 보냈다.
- NUMBERS: 운영 프롬프트 뒤에 아래 [추가 지침]을 그대로 덧붙였다. 운영 코드는 바꾸지 않고 실험 코드에서 덧붙인다.
- 덧붙이는 순서: 운영 프롬프트, 추가 지침(NUMBERS 만), 스키마 요약(형식 강제 수준이 JSON_MODE·PROMPT_ONLY 인 호출에만).
- 호출 순서: 모델마다 반복 i 에서 변형을 번갈아 호출한다. 변형 목록을 (i-1)칸 돌린 순서로 부른다(변형이 BASE,NUMBERS 이면 반복 1 은 BASE 다음 NUMBERS, 반복 2 는 NUMBERS 다음 BASE). 한 변형만 몰아서 부르면 시간대에 따른 서버 상태 차이가 변형 차이에 섞이기 때문이다. 이번 실행의 변형: BASE, NUMBERS, 반복 3회.

NUMBERS 에 덧붙인 원문:

````text
[추가 지침]
주요 이슈를 쓸 때, 근거로 인용하는 기사에 수치(금액, 비율, 수량, 순위, 날짜 등)가 있으면 그 수치를 문장에 그대로 포함하세요. 기사에 없는 수치는 절대 쓰지 마세요. 수치가 없는 기사라면 수치 없이 쓰세요.
````

## 호출·시간·토큰·비용

| 모델 | 프롬프트 | 상태 | 호출 성공 | JSON 해석 성공 | 소요 중앙값(ms) | 최소(ms) | 최대(ms) | 평균 입력 토큰 | 평균 출력 토큰 | 평균 생각 토큰 | 평균 1회 비용(USD) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| gemini:gemini-2.5-flash | BASE | 실행 | 2/3 | 2/3 | 41791 | 36639 | 46943 | 5847.0 | 9140.0 | 6145.5 | 0.024604 |
| gemini:gemini-2.5-flash | NUMBERS | 실행 | 2/3 | 2/3 | 30458 | 28903 | 32013 | 5921.0 | 7125.0 | 5100.5 | 0.019589 |
| gemini:gemini-3.5-flash-lite | BASE | 실행 | 3/3 | 3/3 | 12246 | 5358 | 14073 | 5847.0 | 2046.3 | 미보고 | 0.006870 |
| gemini:gemini-3.5-flash-lite | NUMBERS | 실행 | 2/3 | 2/3 | 10586 | 7075 | 14096 | 5921.0 | 2584.0 | 미보고 | 0.008236 |
| claude:claude-haiku-5-5 | BASE | 실행 | 3/3 | 3/3 | 27438 | 24207 | 35756 | 9842.0 | 7248.0 | 미보고 | 0.004608 |
| claude:claude-haiku-5-5 | NUMBERS | 실행 | 3/3 | 3/3 | 33561 | 32071 | 36981 | 9979.0 | 8750.0 | 미보고 | 0.005373 |
| openai:gpt-6-luna | BASE | 실행 | 3/3 | 3/3 | 51739 | 46276 | 52594 | 6005.0 | 3516.0 | 1152.0 | 0.002359 |
| openai:gpt-6-luna | NUMBERS | 실행 | 3/3 | 3/3 | 69326 | 68698 | 69342 | 6083.0 | 4801.3 | 1749.3 | 0.003009 |

소요 시간·토큰·비용은 호출이 성공한 반복만 집계했다. 호출 성공 = 응답 텍스트를 받음, JSON 해석 성공 = 운영 검증(parseAndValidate)이 예외 없이 끝남. 모든 지표는 모델 x 프롬프트 변형 단위다.

## 형식·근거

| 모델 | 프롬프트 | 형식 강제 | 코드펜스 제거 | 요청 종목 합 | 응답 종목 합 | 검증 통과 종목 합 | 이슈 총수 | 유효 근거 번호가 있는 이슈 | 범위 밖 근거 번호 | 근거 없어 버려진 이슈 | 근거 유효율 | 수치 검사 대상 이슈 | 숫자 포함 이슈 | 숫자 포함 비율 | 숫자가 모두 확인된 이슈 | 수치 근거 일치율 | 이슈 문장의 숫자 총수 | 확인되지 않은 숫자 수 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| gemini:gemini-2.5-flash | BASE | SCHEMA | 0/2 | 20 | 20 | 20 | 52 | 52 | 0 | 0 | 100.0% (52/52) | 52 | 17 | 32.7% (17/52) | 17 | 100.0% (17/17) | 24 | 0 |
| gemini:gemini-2.5-flash | NUMBERS | SCHEMA | 0/2 | 20 | 20 | 20 | 44 | 44 | 0 | 0 | 100.0% (44/44) | 44 | 24 | 54.6% (24/44) | 24 | 100.0% (24/24) | 43 | 0 |
| gemini:gemini-3.5-flash-lite | BASE | SCHEMA | 0/3 | 30 | 30 | 30 | 64 | 64 | 0 | 0 | 100.0% (64/64) | 64 | 15 | 23.4% (15/64) | 15 | 100.0% (15/15) | 15 | 0 |
| gemini:gemini-3.5-flash-lite | NUMBERS | SCHEMA | 0/2 | 20 | 20 | 20 | 54 | 54 | 0 | 0 | 100.0% (54/54) | 54 | 34 | 63.0% (34/54) | 34 | 100.0% (34/34) | 54 | 0 |
| claude:claude-haiku-5-5 | BASE | SCHEMA | 0/3 | 30 | 30 | 30 | 80 | 80 | 0 | 0 | 100.0% (80/80) | 80 | 49 | 61.3% (49/80) | 49 | 100.0% (49/49) | 88 | 0 |
| claude:claude-haiku-5-5 | NUMBERS | SCHEMA | 0/3 | 30 | 30 | 30 | 86 | 86 | 0 | 0 | 100.0% (86/86) | 86 | 63 | 73.3% (63/86) | 63 | 100.0% (63/63) | 137 | 0 |
| openai:gpt-6-luna | BASE | SCHEMA_STRICT | 0/3 | 30 | 30 | 30 | 78 | 78 | 0 | 0 | 100.0% (78/78) | 78 | 42 | 53.8% (42/78) | 42 | 100.0% (42/42) | 61 | 0 |
| openai:gpt-6-luna | NUMBERS | SCHEMA_STRICT | 0/3 | 30 | 30 | 30 | 77 | 77 | 0 | 0 | 100.0% (77/77) | 77 | 57 | 74.0% (57/77) | 57 | 100.0% (57/57) | 126 | 0 |

형식·근거 지표는 JSON 해석에 성공한 반복을 모두 더한 값이다.

## 변형 간 비교 (BASE → NUMBERS)

| 모델 | 숫자 포함 비율 | 수치 근거 일치율 | 확인되지 않은 숫자 / 이슈 문장의 숫자 총수 | 평균 출력 토큰 | 소요 중앙값(ms) |
|---|---|---|---|---|---|
| gemini:gemini-2.5-flash | 32.7% → 54.6% | 100.0% → 100.0% | 0/24 → 0/43 | 9140.0 → 7125.0 | 41791 → 30458 |
| gemini:gemini-3.5-flash-lite | 23.4% → 63.0% | 100.0% → 100.0% | 0/15 → 0/54 | 2046.3 → 2584.0 | 12246 → 10586 |
| claude:claude-haiku-5-5 | 61.3% → 73.3% | 100.0% → 100.0% | 0/88 → 0/137 | 7248.0 → 8750.0 | 27438 → 33561 |
| openai:gpt-6-luna | 53.8% → 74.0% | 100.0% → 100.0% | 0/61 → 0/126 | 3516.0 → 4801.3 | 51739 → 69326 |

각 칸은 'BASE 값 → NUMBERS 값'이다. '-' 는 호출하지 않았거나 해당 값을 낼 표본이 없다는 뜻이다. 표본이 작아(모델·변형당 반복 수만큼) 차이의 유의성은 검증하지 않았다.

## 형식 강제 시도 이력

반복마다 어떤 수준을 시도했고 어떻게 끝났는지(수준:결과, '>' 는 한 단계 내려가 다시 시도)를 횟수와 함께 적는다.

- gemini:gemini-2.5-flash / BASE: SCHEMA:OK 2회, SCHEMA:HTTP_429 1회
- gemini:gemini-2.5-flash / NUMBERS: SCHEMA:OK 2회, SCHEMA:HTTP_429 1회
- gemini:gemini-3.5-flash-lite / BASE: SCHEMA:OK 3회
- gemini:gemini-3.5-flash-lite / NUMBERS: SCHEMA:HTTP_503 1회, SCHEMA:OK 2회
- claude:claude-haiku-5-5 / BASE: SCHEMA:OK 3회
- claude:claude-haiku-5-5 / NUMBERS: SCHEMA:OK 3회
- openai:gpt-6-luna / BASE: SCHEMA_STRICT:OK 3회
- openai:gpt-6-luna / NUMBERS: SCHEMA_STRICT:OK 3회

## 실패한 반복의 오류 코드

- gemini:gemini-2.5-flash / BASE: HTTP_429 1회
- gemini:gemini-2.5-flash / NUMBERS: HTTP_429 1회
- gemini:gemini-3.5-flash-lite / NUMBERS: HTTP_503 1회

## 모델별로 달랐던 요청 설정

프롬프트 텍스트와 응답 스키마의 필드 구성, 최대 출력 한도(16000)는 모든 모델에 같다. 다만 형식 강제 수준이 JSON_MODE·PROMPT_ONLY 로 적용된 호출에는 프롬프트(변형의 추가 지침까지 붙인 것) 뒤에 아래 스키마 요약 문구를 덧붙였고(모든 해당 모델에 같은 문구, 순서: 운영 프롬프트, 추가 지침, 스키마 요약), 스키마로 강제된 수준(SCHEMA_STRICT·SCHEMA)의 프롬프트는 건드리지 않았다. 아래는 제공자 사정으로 달랐던 부분이다.

덧붙인 문구 (689자):

````text

[출력 형식]
반드시 아래 JSON 스키마를 따르는 JSON 객체 하나만 출력하세요. 코드펜스(```)나 설명 문장을 붙이지 마세요.
{"properties":{"stocks":{"items":{"properties":{"caution":{"type":"string"},"issues":{"items":{"properties":{"sources":{"items":{"type":"integer"},"type":"array"},"text":{"type":"string"}},"required":["text","sources"],"type":"object"},"type":"array"},"positives":{"items":{"type":"string"},"type":"array"},"risks":{"items":{"type":"string"},"type":"array"},"sentiment":{"enum":["POSITIVE","NEUTRAL","NEGATIVE"],"type":"string"},"stockIndex":{"type":"integer"}},"required":["stockIndex","sentiment","issues","positives","caution","risks"],"type":"object"},"type":"array"}},"required":["stocks"],"type":"object"}
````

**gemini:gemini-2.5-flash**

- temperature 0.3 전송 (운영 buildRequestBody 규칙)
- 형식 강제 순서(처음 → 400 거부 때 한 단계씩 내려감): SCHEMA(generationConfig.responseMimeType + responseJsonSchema, 운영 스키마 그대로) → JSON_MODE(responseMimeType application/json 만) → PROMPT_ONLY(둘 다 없음). maxOutputTokens 16000
- 잘못된 API 키로 오는 400 은 형식 거부로 보지 않고 HTTP_400_AUTH 로 기록해 내려가지 않음
- 호출: 운영 callModel — 5xx·429 는 최대 2회 재시도, 제한 90초
- 출력 토큰 = 응답 토큰 + 생각 토큰(thoughtsTokenCount) 합산

**gemini:gemini-3.5-flash-lite**

- temperature 미전송 (운영 buildRequestBody 규칙: gemini-3·4 계열)
- 형식 강제 순서(처음 → 400 거부 때 한 단계씩 내려감): SCHEMA(generationConfig.responseMimeType + responseJsonSchema, 운영 스키마 그대로) → JSON_MODE(responseMimeType application/json 만) → PROMPT_ONLY(둘 다 없음). maxOutputTokens 16000
- 잘못된 API 키로 오는 400 은 형식 거부로 보지 않고 HTTP_400_AUTH 로 기록해 내려가지 않음
- 호출: 운영 callModel — 5xx·429 는 최대 2회 재시도, 제한 90초
- 출력 토큰 = 응답 토큰 + 생각 토큰(thoughtsTokenCount) 합산

**claude:claude-haiku-5-5**

- 형식 강제: SCHEMA (output_config.format). strict 플래그는 없고 내려가기 없음
- temperature·top_p·top_k 미전송 (이 모델은 기본값이 아닌 값을 400 으로 거부)
- 구조화 출력: output_config.format (JSON 스키마). 운영 스키마에 object 마다 additionalProperties=false 를 더함
- thinking: 파라미터 생략(기본 adaptive). max_tokens 16000, 비스트리밍
- 재시도·타임아웃: SDK 기본 재시도, 호출 제한 180초
- 출력 토큰 = output_tokens (생각 토큰 포함, 따로 보고되지 않음)

**openai:gpt-6-luna**

- Chat Completions API. temperature 등 샘플링 파라미터 미전송 (모델별 지원 여부는 확인 못 함)
- 형식 강제 순서(처음 → 400 계열 거부 때 한 단계씩 내려감): SCHEMA_STRICT. OpenAI 본체: json_schema + strict, 내려가기 없음 (기준선을 흐리지 않기 위해)
- 수준별 요청: SCHEMA_STRICT = response_format json_schema(name=stock_analysis, strict=true), SCHEMA = json_schema(strict 없음, name 은 SDK 필수라 보냄), JSON_MODE = response_format json_object, PROMPT_ONLY = response_format 없음. 스키마 수준은 운영 스키마에 object 마다 additionalProperties=false 를 더함
- max_completion_tokens 16000, 비스트리밍 (제공자가 이 파라미터 이름·한도를 받는지는 확인 못 함)
- 재시도·타임아웃: SDK 기본 재시도, 호출 제한 180초
- 출력 토큰 = completion_tokens (추론 토큰 포함, 추론 토큰은 completion_tokens_details 로 따로 기록)

## 적용한 가격

- gemini:gemini-2.5-flash: 입력 0.3, 출력 2.5 USD / 100만 토큰 (출처 https://aistudio.google.com/docs/pricing, 확인일 2026-11-18) — 현재 운영 모델. 지원 종료 예정이며 2026-12-01 부터 gemini-3.5-flash-lite 로 자동 전환(https://aistudio.google.com/docs/deprecations).
- gemini:gemini-3.5-flash-lite: 입력 0.3, 출력 2.5 USD / 100만 토큰 (출처 https://aistudio.google.com/docs/pricing, 확인일 2026-11-18) — Standard 등급 텍스트 단가.
- claude:claude-haiku-5-5: 입력 0.1, 출력 0.5 USD / 100만 토큰 (출처 https://platform.claude.com/docs/en/about-claude/pricing, 확인일 2026-11-18) — 프롬프트 10만 토큰 이하 기준 단가. 초과 구간은 단가가 다르므로 이 표로 계산하지 않는다.
- openai:gpt-6-luna: 입력 0.1, 출력 0.5 USD / 100만 토큰 (출처 https://developers.openai.com/api/docs/pricing, 확인일 2026-11-18) — 최신 세대(GPT-6) 중 최저가. 이전 세대 gpt-5-nano 가 더 싸지만 세대가 달라 제외. 입력 272K 토큰 초과 구간은 단가가 다르다.

## 지표 정의와 한계

- 근거 지표(이슈 총수, 유효 근거 번호가 있는 이슈, 범위 밖 근거 번호, 버려진 이슈)는 운영의 모델 비교(ModelExperimentService)와 같은 정의다.
- 근거 유효율 = 유효 근거 번호가 있는 이슈 / 이슈 총수.
- 수치 근거 일치율: 검증을 통과한 이슈 문장에서 숫자 표현(정수·소수·퍼센트, 쉼표 제거)을 뽑아, 그 이슈가 인용한 기사의 제목+요약(프롬프트에 들어간 길이까지) 안에 같은 숫자 표현이 있는지 문자열로 확인한다. 일치율 = 숫자가 모두 확인된 이슈 / 숫자가 들어 있는 이슈.
- 수치 근거 일치율의 한계: ① "2조"와 "20,000억"처럼 표기가 달라도 같은 값이면 불일치로 잡힌다. ② 모델이 기사 숫자로 계산한 합계·증감도 불일치로 잡힌다. ③ 날짜·분기·순번의 숫자도 포함된다. ④ 인용 기사에 같은 숫자가 있으면 맥락이 달라도 일치로 잡힌다. 따라서 환각의 증명이 아니라 숫자 문자열이 인용 기사에 있는지의 점검이다.
- 수치 검사 대상 이슈 = 검증을 통과해 숫자 검사에 들어간 이슈 수. 이슈 총수(모델이 낸 이슈 전부, 근거 없어 버려진 것 포함)와 다를 수 있어 숫자 포함 비율의 분모는 이 값이다.
- 숫자 포함 비율 = 숫자 포함 이슈 / 수치 검사 대상 이슈. 숫자 포함 이슈 = 이슈 문장에서 숫자 표현이 하나 이상 나온 이슈.
- 이슈 문장의 숫자 총수 = 숫자 포함 이슈마다 (중복을 뺀) 숫자 표현의 개수를 모두 더한 값. 확인되지 않은 숫자 수 = 그중 인용 기사 제목·요약에 같은 표기가 없는 숫자의 합(이슈 단위가 아니라 숫자 단위).
- 출력 토큰은 제공자별 보고 방식을 맞춰 생각·추론 토큰을 포함한다. 생각 토큰은 보고하는 제공자만 따로 적고, 미보고는 확인하지 못했다는 뜻이다.
- 소요 시간에는 SDK·호출 코드의 재시도와 대기가 포함된다. 같은 모델의 반복 사이에도 서버 상태에 따라 편차가 있다.
- 형식 강제 수준이 모델마다 달라서 "JSON 해석 성공"의 의미가 같지 않다. SCHEMA_STRICT·SCHEMA 는 제공자가 형식을 강제한 결과이고, JSON_MODE·PROMPT_ONLY 는 프롬프트 지시와 코드펜스·앞뒤 문장 제거(첫 JSON 객체/배열 추출)에 기댄 결과다. 수준이 다른 모델의 형식 성공률을 같은 잣대로 비교하지 않는다.
- 같은 입력으로 반복 호출한 표본이 작아 통계적 유의성은 검증하지 않았다. 판단은 사람이 한다.

## 확인되지 않은 숫자 예시

없음 (또는 호출한 모델이 없음)

## 이 키로 호출할 수 있는 Gemini API 모델 ID

Gemini API 의 모델 목록(generateContent 지원)에서 flash·gemma 가 이름에 들어간 것만 적는다. 전체 49개.

- gemini-2.5-flash
- gemini-2.5-flash-image
- gemini-2.5-flash-lite
- gemini-2.5-flash-preview-tts
- gemini-3-flash-preview
- gemini-3.1-flash-image
- gemini-3.1-flash-image-preview
- gemini-3.1-flash-lite
- gemini-3.1-flash-lite-image
- gemini-3.1-flash-lite-preview
- gemini-3.1-flash-tts-preview
- gemini-3.5-flash
- gemini-3.5-flash-lite
- gemini-3.6-flash
- gemini-3.7-flash
- gemini-3.8-flash
- gemini-3.8-flash-lite-tts
- gemini-3.8-flash-tts
- gemini-flash-latest
- gemini-flash-lite-latest
- gemini-omni-1.1-flash
- gemini-omni-1.2-flash
- gemini-omni-flash-preview
- gemma-4-26b-a4b-it
- gemma-4-31b-it
