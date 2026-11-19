<div align="center">

# 📈 StockPulse

### 실시간 검색 상위 종목 뉴스 AI 분석 서비스

네이버 증권의 **실시간 검색 상위 TOP 10** 종목을 자동으로 수집하고,
관련 뉴스와 **근거 기사 번호가 붙은 AI 분석**을 한 화면에서 보여주는 웹 서비스입니다.

<br/>

[![Live Demo](https://img.shields.io/badge/🔗_Live_Demo-ai--stock--news-00d4ff?style=for-the-badge)](https://ai-stock-news-smoky.vercel.app)

<br/>

![Java](https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5-6DB33F?logo=springboot&logoColor=white)
![React](https://img.shields.io/badge/React-18-61DAFB?logo=react&logoColor=black)
![Tailwind CSS](https://img.shields.io/badge/Tailwind-3-06B6D4?logo=tailwindcss&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-Neon-4169E1?logo=postgresql&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-2496ED?logo=docker&logoColor=white)
![Claude](https://img.shields.io/badge/Claude-Haiku_5.5-D97757?logo=anthropic&logoColor=white)
![Gemini](https://img.shields.io/badge/Google_Gemini-예비_모델-8E75B2?logo=googlegemini&logoColor=white)

</div>

---

## 📖 소개

주식 초보자가 "지금 갑자기 오르내리는 종목이 **왜** 그런지" 알기 위해선
여러 사이트를 돌아다니며 시세·뉴스·재무지표를 직접 조합해야 합니다.

**StockPulse는 이 과정을 자동화합니다.**

> 검색 상위 종목 수집 → 관련 뉴스 수집 → AI가 이슈·투자관점·리스크를 정리(문장마다 근거 기사 번호)
> → 한 화면에서 확인 → 날짜별로 기록까지 보관

수집이나 AI 호출이 실패하면 대체 데이터나 대체 문구를 만들지 않습니다. 실패는 상태값으로 저장하고 화면에 그대로 표시합니다.
개선 과정의 측정·실험·한계는 [docs/IMPROVEMENT_LOG.md](docs/IMPROVEMENT_LOG.md), 기술 선택의 대안과 이유는 [docs/ARCHITECTURE_DECISIONS.md](docs/ARCHITECTURE_DECISIONS.md)에 있습니다.

평일 8\~20시(넥스트레이드 프리장\~애프터마켓) 매 정각 자동 갱신되며, 매 거래일 **종가(16시)** 데이터는 별도로 아카이브되어 과거 날짜를 골라 다시 볼 수 있습니다.

<div align="center">

### 🔗 **https://ai-stock-news-smoky.vercel.app**

</div>

<div align="center">
  <img src="docs/img/hero.png" width="92%" alt="StockPulse 대시보드"/>
  <br/>
  <sub>한 화면에서 검색 상위 TOP10 · AI 투자 분석 · 관련 뉴스 · 코스피/코스닥 지수를 확인</sub>
</div>

---

## ✨ 주요 기능

한눈에 보기 👇

| # | 기능 | 요약 |
|---|------|------|
| 1 | 🔥 실시간 검색 상위 TOP 10 | 인기검색 종목을 매 정각 수집 (거래대금·PER·52주고 등) |
| 2 | 🤖 AI 분석 (근거 표시) | 이슈 / 투자관점 / 리스크 3섹션, 이슈 문장마다 근거 기사 번호 |
| 3 | 📰 관련 뉴스 자동 수집 | 48h 이내·최대 5건 선별, 수집이 허용된 기사는 본문 앞부분까지 분석에 사용 |
| 4 | 📊 코스피·코스닥 지수 | 상단 실시간 지수 |
| 5 | 🗓 날짜별 아카이브 | 과거 거래일 종가 데이터 다시보기 |
| 6 | 🧾 실행 이력 | 매 수집·분석 사이클의 성공 여부, 사용 모델, 토큰 수를 기록하고 화면에 요약 표시 |

> 📷 아래 이미지·GIF는 **실제 라이브 사이트에서 캡처**한 것입니다. 2026-11 개선(근거 번호, 뉴스 논조 표시, 실행 상태 표시) 이전 화면이라 지금 화면과 일부 다릅니다.

<br/>

### 🔥 1. 실시간 검색 상위 TOP 10

<div align="center"><img src="docs/gifs/01-top10.gif" width="90%" alt="종목 카드 클릭하며 둘러보기"/></div>

화면 왼쪽에 **네이버 증권 검색 상위(인기검색) 종목 1\~10위**가 카드로 나열됩니다. 각 카드는 종목 하나에 대한 요약 대시보드예요.

> 종목 목록과 상세 지표는 네이버 모바일 증권이 내려주는 JSON 응답을 읽어 수집합니다. 네이버가 문서로 공개한 Open API 가 아니어서 응답 구조가 예고 없이 바뀔 수 있습니다.
> 수집에 실패하면 임의 데이터를 만들지 않고 직전 수집분을 유지하며, 화면과 `/api/health` 에 실패 사실을 표시합니다.

**카드에 담기는 정보** (예: `#1 SK하이닉스`)
- **현재가 / 등락률 / 전일대비** — `₩1,373,000 · ▼ -11.42% · -177,000`
- **거래대금** — `15.3조` 처럼 **조·억 단위로 환산**해 표시 (원 단위 거래량보다 규모가 직관적)
- **PER / 추정PER / PBR** — `13.26배 / 4.00배 / 5.92배` (값이 없으면 `X`)
- **52주 신고가** — `₩2,987,000` (현재가와 비교해 고점 대비 위치를 가늠)
- **AI 분석 완료** 뱃지 — 이 종목의 AI 리포트가 준비됐다는 표시
- **장 세션 뱃지** — 정규장 외 시간엔 **넥스트레이드(NXT) 실시간 가격**을 반영하고 `🌅 프리장` / `🌙 애프터마켓` 뱃지 표시

> 💡 등락 색상은 **한국식** 입니다 — 상승 🔴 **빨강**, 하락 🔵 **파랑** (해외식과 반대).
> 🕗 가격은 넥스트레이드 **프리장(08시)\~정규장\~애프터마켓(20시)** 을 실시간 시세로 커버합니다.

<br/>

### 🤖 2. AI 분석 (근거 표시)

<div align="center"><img src="docs/img/02-ai-analysis.png" width="88%" alt="AI 분석 리포트"/></div>

카드를 클릭하면 오른쪽 패널에 AI 가 생성한 분석이 나타납니다. 수집된 뉴스(제목과 본문 앞부분 또는 요약문)를 입력으로 쓰며, **3개 섹션**으로 정리됩니다.

- **📈 주요 이슈** — 뉴스에 나온 핵심 사건. 문장마다 **근거 기사 번호**가 붙고, 누르면 해당 기사로 이동합니다
- **💡 투자 관점 요약** — 긍정 요인 / 주의 요인
- **⚠️ 리스크 요인** — 단기·중기 리스크

**모델 구성과 검증**
- 주 모델은 **Claude Haiku 5.5**, 예비 모델은 **Gemini 3.5 Flash-Lite** 입니다. 주 모델 호출이 실패하면 예비 모델로 넘어가고, 모두 실패하면 대체 문구 없이 "분석 실패"로 기록·표시합니다.
- 응답은 JSON 스키마로 받고, 이슈 문장의 근거 기사 번호가 실제 기사를 가리키는지 코드가 확인합니다.
- 이슈 문장 속 숫자가 인용 기사에 있는지 매 실행 세어 실행 이력에 남깁니다(측정만 하고 문장을 고치지는 않습니다).
- 10개 종목을 **한 번의 API 호출**로 분석합니다.

> ⚠️ 한계: 생성한 문장을 기사 본문과 대조해 검증하지는 않습니다. 본문은 앞부분(최대 1,500자)만 쓰고, 수집이 허용되지 않거나 추출에 실패한 기사는 잘린 요약문만 씁니다.
> ⚠️ AI 자동 생성 참고 자료이며 투자 권유가 아닙니다.

<br/>

### 📰 3. 관련 뉴스 자동 수집

<div align="center"><img src="docs/img/03-news.png" width="88%" alt="관련 뉴스 목록"/></div>

AI 리포트 아래에는 **네이버 검색 API로 수집한 관련 뉴스**가 붙습니다.
- **48시간 이내** 발행 + **제목에 종목명이 독립된 단어로 나온** 기사만 선별합니다 (예: 종목 "에스엠"에 "에스엠벡셀" 기사가 붙지 않도록 함)
- 기사 제목이 종목명과 다른 표기를 쓰는 종목은 **별칭**으로도 검색합니다 (예: `NAVER` → 네이버, `JYP Ent.` → JYP엔터테인먼트). 별칭 사전은 설정 파일(`news.aliases`)에서 손으로 관리하고, 목록에 없는 종목은 종목명만으로 검색합니다
- 내용이 거의 같은 중복 기사는 제외합니다
- 종목당 **최대 5건**, 제목·요약·발행일 표시
- 분석 입력용으로 기사 원문에서 **본문 앞부분**을 가져옵니다. 요청 전에 언론사의 robots.txt 를 확인해 금지된 곳은 가져오지 않고, 본문은 저장하거나 화면에 표시하지 않습니다. 사진은 수집하지 않습니다
- 뉴스 수집에 실패하면 "뉴스 없음"과 구분해 실패로 표시합니다
- 클릭하면 **원문 기사**가 새 탭으로 열립니다

<br/>

### 📊 4. 코스피 · 코스닥 지수

<div align="center"><img src="docs/img/04-index.png" width="70%" alt="코스피 코스닥 실시간 지수"/></div>

상단 헤더 시계 아래에 **코스피·코스닥 실시간 지수**가 표시됩니다 (예: `코스피 5,665.44 ▼ -358.22 (-5.95%)`). 개별 종목이 시장 전체 흐름과 어떤지 배경을 함께 볼 수 있어요. (백엔드에서 30초 캐시)

<br/>

### 🗓 5. 날짜별 아카이브 (과거 데이터 다시보기)

<div align="center"><img src="docs/gifs/05-archive.gif" width="90%" alt="날짜 선택 → 그날의 아카이브로 전환"/></div>

현재 데이터는 매시간 덮어써지지만, 매 거래일 **종가(16:05 KST)** 시점의 TOP10·뉴스·AI 분석은 **날짜별로 아카이브**됩니다.

- 상단 **날짜 선택기**에서 `오늘 (실시간)` ↔ 과거 날짜 전환
- 과거 날짜를 고르면 **"📁 YYYY-MM-DD 종가 기준 아카이브"** 로 바뀌며, 그날의 종목·뉴스·AI 리포트를 그대로 다시 볼 수 있음
- "어제 1위는 뭐였고 왜 올랐지?" 같은 **회고**가 가능

> 🏛 구조: 현재 스냅샷(hot) + 날짜별 누적 아카이브(cold) 하이브리드 → [ERD](#-데이터베이스-erd) 참고

<br/>

### ⏱ 자동 갱신

- 평일 **8\~20시(프리장 08시\~애프터마켓 20시) 매 정각** 자동 수집·분석 (KST), 공휴일 자동 휴장
- 서버 타임존을 `Asia/Seoul` 로 고정해 표시 시각이 한국시간과 일치
- 매 사이클의 결과(수집 성공 여부, 분석 성공 수, 사용 모델, 토큰 수, 본문 사용 건수)를 `pipeline_run` 테이블에 기록하고 `/api/runs` 로 조회

---

## 🛠 기술 스택

<div align="center">

| 구분 | 기술 |
|------|------|
| **Frontend** | React 18 (CRA), Tailwind CSS v3, Axios |
| **Backend** | Java 17, Spring Boot 3.5, Spring Data JPA, Spring Scheduler |
| **수집 / 외부 연동** | Jsoup(HTTP 호출 · JSON 수집 · 기사 본문 추출), Spring WebFlux WebClient |
| **Database** | H2 (로컬) / PostgreSQL — [Neon](https://neon.tech) (운영) |
| **AI / 외부 API** | Claude Haiku 5.5(주 모델, Anthropic Java SDK), Gemini 3.5 Flash-Lite(예비 모델), 네이버 검색 API, 네이버 금융/지수 |
| **Infra / 배포** | Docker, 네이버클라우드 VPC 서버, Vercel, GitHub Actions(테스트 → 빌드 → 배포) |

</div>

---

## 🏗 시스템 아키텍처

```mermaid
flowchart LR
    U["👤 사용자"] -->|HTTPS| V["▲ Vercel<br/>React SPA"]
    V -->|"/api 프록시<br/>(mixed-content 우회)"| E["🐳 네이버클라우드 서버<br/>Spring Boot (Docker)"]
    E -->|JSON 수집| NF["네이버 증권<br/>검색 상위 TOP10"]
    E -->|지수 조회| IDX["네이버 지수 API<br/>코스피/코스닥"]
    E -->|뉴스 검색| NN["네이버 검색 API"]
    E -->|기사 본문| PUB["언론사 기사 페이지<br/>(robots.txt 확인 후)"]
    E -->|AI 분석| G["Claude Haiku 5.5<br/>(실패 시 Gemini)"]
    GH["GitHub Actions<br/>테스트·빌드"] -->|JAR 전송·기동| E
    E <-->|JPA| DB[("🐘 Neon<br/>PostgreSQL")]
```

- **프론트(Vercel)** 는 HTTPS, **백엔드(서버)** 는 HTTP라 브라우저가 직접 호출하면 mixed-content로 차단됩니다.
  → Vercel `rewrites` 로 `/api/*` 요청을 서버로 **서버사이드 프록시**하여 해결 (백엔드 HTTPS/도메인 불필요).
- 서버는 사양이 작아(1 vCPU, 메모리 1GB) 서버에서 빌드하지 않습니다. GitHub Actions 가 테스트를 통과한 JAR 을 만들어 서버로 보내고 Docker 로 기동합니다.

---

## 🔄 동작 방식

```mermaid
flowchart TD
    S["⏰ 스케줄러<br/>평일 8~20시 매 정각 (KST)"] --> C["1️⃣ 검색 상위 TOP10 수집"]
    C --> N["2️⃣ 종목별 뉴스 수집<br/>(48h 이내·최대 5건)"]
    N --> B["기사 본문 앞부분 수집<br/>(robots.txt 허용 시)"]
    B --> A["3️⃣ AI 분석 (10종목 1회 호출)<br/>Claude → 실패 시 Gemini"]
    A --> DB[("현재 스냅샷 저장<br/>stocks / news_articles")]
    A --> RUN[("실행 이력 기록<br/>pipeline_run")]

    S2["⏰ 매 거래일 16:05 (KST)"] --> AR["당일 종가 데이터를<br/>날짜별 아카이브로 적재"]
    AR --> DB2[("daily_stock / daily_news")]
```

- 현재 데이터(`stocks`, `news_articles`)는 매 갱신마다 **덮어쓰기** → 항상 최신 스냅샷만 유지
- 아카이브(`daily_stock`, `daily_news`)는 **append-only** 누적 → 과거 이력 보존 (hot + cold 구조)

---

## 🗂 REST API

| Method | Endpoint | 설명 |
|--------|----------|------|
| `GET` | `/api/stocks/top10` | 검색 상위 TOP 10 종목 |
| `GET` | `/api/stocks/{id}/news` | 종목별 뉴스 + AI 분석 |
| `POST` | `/api/stocks/refresh` | 수동 갱신 트리거 (관리용, `X-Admin-Token` 헤더 필요) |
| `GET` | `/api/market/indices` | 코스피·코스닥 실시간 지수 |
| `GET` | `/api/archive/dates` | 아카이브가 있는 날짜 목록 |
| `GET` | `/api/archive/{date}/stocks` | 특정 날짜의 TOP10 |
| `GET` | `/api/archive/stocks/{id}/news` | 아카이브 종목의 뉴스 + AI |
| `POST` | `/api/archive/run` | 오늘 데이터 수동 아카이브 (관리용, `X-Admin-Token` 헤더 필요) |
| `GET` | `/api/health` | 서버 상태 (종목/뉴스 개수, 마지막 수집 시각과 성공 여부) |
| `GET` | `/api/runs` | 수집·분석 실행 이력 (모델, 성공 수, 토큰, 본문 사용 건수 등) |
| `GET` | `/api/runs/summary` | 최근 실행 이력 요약 |

---

## 🗄 데이터베이스 (ERD)

```mermaid
erDiagram
    stocks ||--o{ news_articles : "has"
    daily_stock ||--o{ daily_news : "has"

    stocks {
        Long id PK
        String code "종목코드"
        String name "종목명"
        String currentPrice "현재가"
        String changeRate "등락률"
        Long tradingValue "통합 거래대금(원)"
        String marketSession "장세션 정규/프리/애프터"
        Integer ranking "순위"
        String per_pbr "PER/추정PER/PBR"
        String high52Week "52주 신고가"
        TEXT aiAnalysis "AI 분석"
        String analysisStatus "분석 상태 성공/실패/대기"
        String analysisModel "실제로 응답한 모델"
        String analysisSentiment "뉴스 논조"
        String newsStatus "뉴스 수집 상태"
        LocalDateTime updatedAt "갱신시각"
    }
    news_articles {
        Long id PK
        Long stock_id FK
        String title "제목"
        String link "원문 URL"
        String pubDate "발행일"
        String bodyStatus "본문 수집 상태"
        Integer bodyChars "분석에 쓴 본문 글자 수"
    }
    pipeline_run {
        Long id PK
        LocalDateTime startedAt "시작 시각"
        String trigger "정기/기동/수동"
        String outcome "결과"
        String analysisModel "응답한 모델"
        Integer analysisSuccess "분석 성공 종목 수"
        Integer promptTokens "입력 토큰"
        Integer numbersUnverified "인용 기사에서 확인 안 된 숫자"
        Integer bodiesUsed "본문을 쓴 기사 수"
    }
    daily_stock {
        Long id PK
        LocalDate snapshotDate "아카이브 날짜"
        String name "종목명(스냅샷)"
        Integer ranking "순위"
        TEXT aiAnalysis "AI 분석(스냅샷)"
    }
    daily_news {
        Long id PK
        Long archive_stock_id FK
        String title "제목"
        String link "원문 URL"
    }
```

---

## 📁 프로젝트 구조

```
.
├── backend/                Spring Boot 3.5 (Java 17, Gradle)
│   └── src/main/java/com/stocknews/
│       ├── controller/     REST API
│       ├── service/        종목 수집 · 뉴스 · 기사 본문 수집 · AI 분석(모델 체인) · 지수 · 아카이브
│       ├── scheduler/      정각 갱신 + 16:05 아카이브
│       ├── entity/         Stock, NewsArticle, PipelineRun, DailyArchiveStock/News
│       └── repository/     Spring Data JPA
├── frontend/               React 18 (CRA) + Tailwind CSS
│   └── src/
│       ├── api/            axios 클라이언트
│       └── components/     Header, StockCard, NewsPanel, RunStatusLine
├── .github/workflows/      CI(테스트·빌드), 백엔드 배포
├── docker-compose.yml      백엔드 컨테이너 (외부 Neon DB 연결)
├── docker-compose.server.yml  서버 기동용 (CI 가 만든 JAR 사용)
└── docs/
    ├── DEPLOY.md                  배포 가이드 (서버 / Vercel / Neon)
    ├── IMPROVEMENT_LOG.md         개선 기록 (측정, 실험, 적용, 문제 해결)
    ├── ARCHITECTURE_DECISIONS.md  기술 선택의 대안·이유·한계
    └── experiments/               모델·프롬프트 실험 결과 원본
```

---

## 🚀 로컬 실행

### 1. 백엔드 (H2 인메모리)

```bash
cd backend
# src/main/resources/application-local.yml 에 API 키 입력 (git 미추적)
./gradlew bootRun
```
- 서버: `http://localhost:8080`
- H2 콘솔: `http://localhost:8080/h2-console` (JDBC: `jdbc:h2:mem:stockdb` / `sa` / 빈칸)

### 2. 프론트엔드

```bash
cd frontend
npm install
npm start   # http://localhost:3000
```

> `application-local.yml` 예시
> ```yaml
> naver:
>   api:
>     client-id: YOUR_NAVER_CLIENT_ID
>     client-secret: YOUR_NAVER_CLIENT_SECRET
>     news-url: https://openapi.naver.com/v1/search/news.json
> anthropic:
>   api:
>     key: YOUR_ANTHROPIC_API_KEY   # 주 모델. 없으면 건너뛰고 예비 모델을 씁니다
> gemini:
>   api:
>     key: YOUR_GEMINI_API_KEY      # 예비 모델
> ```

---

## ☁️ 배포

| 구성 | 서비스 |
|------|--------|
| **Frontend** | Vercel (Root: `frontend`, env `REACT_APP_API_URL=/api`) |
| **Backend** | 네이버클라우드 VPC 서버 (Docker). GitHub Actions 가 테스트 후 JAR 을 빌드해 서버로 보내고 `docker-compose.server.yml` 로 기동 |
| **Database** | Neon (무료 PostgreSQL) |

자세한 절차는 **[docs/DEPLOY.md](docs/DEPLOY.md)** 참고.

---

## 🔑 환경변수

| 변수 | 용도 | 위치 |
|------|------|------|
| `NAVER_CLIENT_ID` / `NAVER_CLIENT_SECRET` | 네이버 검색 API | 서버 `.env` / 로컬 `application-local.yml` |
| `ANTHROPIC_API_KEY` | 주 모델(Claude Haiku 5.5). 없으면 예비 모델로 넘어감 | 동일 |
| `GEMINI_API_KEY` | 예비 모델(Gemini) | 동일 |
| `ANALYSIS_MODELS` | 모델 체인 변경용(`제공자:모델` 쉼표 목록). 비우면 기본 체인 | 선택 |
| `NEWS_BODY_ENABLED` | 기사 본문 수집 켜기/끄기 (기본 `true`) | 선택 |
| `NEWS_ALIASES_ENABLED` | 종목 별칭 검색 켜기/끄기 (기본 `true`) | 선택 |
| `DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD` | PostgreSQL(운영) | 서버 `.env` |
| `ADMIN_TOKEN` | 관리용 API(수동 갱신·아카이브) 인증 토큰. 비우면 해당 API 차단 | 서버 `.env` |
| `CORS_ALLOWED_ORIGINS` | 허용 오리진 | 선택 (기본: localhost + `*.vercel.app`) |
| `REACT_APP_API_URL` | 프론트가 호출할 API 경로 | Vercel 환경변수 |

---

## 👤 Author

**zizonpubao** · [GitHub](https://github.com/zizonpubao)

---

<div align="center">

> ⚠️ 본 서비스는 AI 자동 생성 정보를 제공하며, **투자 권유가 아닙니다.**
> 투자 손실에 대한 책임은 본인에게 있습니다.

</div>
