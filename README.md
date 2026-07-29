<div align="center">

# 📈 StockPulse

### 실시간 급상승 종목 뉴스 AI 분석 서비스

네이버 금융의 **실시간 검색 급상승 TOP 10** 종목을 자동으로 수집하고,
관련 뉴스와 **Google Gemini AI 투자 분석**을 한 화면에서 보여주는 웹 서비스입니다.

<br/>

[![Live Demo](https://img.shields.io/badge/🔗_Live_Demo-ai--stock--news-00d4ff?style=for-the-badge)](https://ai-stock-news-smoky.vercel.app)

<br/>

![Java](https://img.shields.io/badge/Java-17-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.2-6DB33F?logo=springboot&logoColor=white)
![React](https://img.shields.io/badge/React-18-61DAFB?logo=react&logoColor=black)
![Tailwind CSS](https://img.shields.io/badge/Tailwind-3-06B6D4?logo=tailwindcss&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-Neon-4169E1?logo=postgresql&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-2496ED?logo=docker&logoColor=white)
![Gemini](https://img.shields.io/badge/Google_Gemini-2.5_Flash-8E75B2?logo=googlegemini&logoColor=white)

</div>

---

## 📖 소개

주식 초보자가 "지금 갑자기 오르내리는 종목이 **왜** 그런지" 알기 위해선
여러 사이트를 돌아다니며 시세·뉴스·재무지표를 직접 조합해야 합니다.

**StockPulse는 이 과정을 자동화합니다.**

> 급상승 종목 크롤링 → 관련 뉴스 수집 → AI가 이슈·투자관점·리스크를 요약
> → 한 화면에서 확인 → 날짜별로 기록까지 보관

평일 **9~20시(프리장 포함) 매 정각** 자동 갱신되며, 매 거래일 **종가(16시)** 데이터는 별도로 아카이브되어 과거 날짜를 골라 다시 볼 수 있습니다.

<div align="center">

### 🔗 **https://ai-stock-news-smoky.vercel.app**

</div>

<!-- 스크린샷을 넣으려면 docs/ 폴더에 이미지를 추가하고 아래 주석을 해제하세요
<div align="center">
  <img src="docs/screenshot-main.png" width="85%"/>
</div>
-->

---

## ✨ 주요 기능

| 기능 | 설명 |
|------|------|
| 🔥 **실시간 급상승 TOP 10** | 네이버 금융 인기검색 종목을 매 정각 크롤링 — 현재가·등락률·**거래대금(조 단위)**·PER/PBR·52주 신고가 |
| 📰 **관련 뉴스 자동 수집** | 종목별 네이버 뉴스를 48시간 이내·최대 5건 선별 (제목에 종목명 포함 필터) |
| 🤖 **Gemini AI 투자 분석** | 종목+뉴스를 바탕으로 **주요 이슈 / 투자 관점 / 리스크 요인** 을 자동 요약 |
| 📊 **코스피·코스닥 지수** | 상단에 실시간 지수 표시 (30초 캐시) |
| 🎨 **한국식 등락 색상** | 상승 🔴 빨강 / 하락 🔵 파랑 |
| 🗓 **날짜별 아카이브** | 매 거래일 종가 스냅샷(종목+뉴스+AI)을 저장, 상단 날짜 선택기로 **과거 그날의 데이터** 조회 |
| ⏱ **자동 갱신** | 평일 9~20시 매 정각 (KST), 서버 타임존 고정 |

---

## 🛠 기술 스택

<div align="center">

| 구분 | 기술 |
|------|------|
| **Frontend** | React 18 (CRA), Tailwind CSS v3, Axios |
| **Backend** | Java 17, Spring Boot 3.2, Spring Data JPA, Spring Scheduler |
| **크롤링 / 외부 연동** | Jsoup(HTML 크롤링), Spring WebFlux WebClient |
| **Database** | H2 (로컬) / PostgreSQL — [Neon](https://neon.tech) (운영) |
| **AI / 외부 API** | Google Gemini 2.5 Flash, 네이버 검색 API, 네이버 금융/지수 |
| **Infra / 배포** | Docker, AWS EC2, Vercel |

</div>

---

## 🏗 시스템 아키텍처

```mermaid
flowchart LR
    U["👤 사용자"] -->|HTTPS| V["▲ Vercel<br/>React SPA"]
    V -->|"/api 프록시<br/>(mixed-content 우회)"| E["🐳 AWS EC2<br/>Spring Boot (Docker)"]
    E -->|크롤링| NF["네이버 금융<br/>급상승 TOP10"]
    E -->|지수 조회| IDX["네이버 지수 API<br/>코스피/코스닥"]
    E -->|뉴스 검색| NN["네이버 검색 API"]
    E -->|AI 분석| G["Google Gemini<br/>2.5 Flash"]
    E <-->|JPA| DB[("🐘 Neon<br/>PostgreSQL")]
```

- **프론트(Vercel)** 는 HTTPS, **백엔드(EC2)** 는 HTTP라 브라우저가 직접 호출하면 mixed-content로 차단됩니다.
  → Vercel `rewrites` 로 `/api/*` 요청을 EC2로 **서버사이드 프록시**하여 해결 (백엔드 HTTPS/도메인 불필요).

---

## 🔄 동작 방식

```mermaid
flowchart TD
    S["⏰ 스케줄러<br/>평일 9~20시 매 정각 (KST)"] --> C["1️⃣ 급상승 TOP10 크롤링"]
    C --> N["2️⃣ 종목별 뉴스 수집<br/>(48h 이내·최대 5건)"]
    N --> A["3️⃣ Gemini 일괄 AI 분석<br/>(10종목 1회 호출)"]
    A --> DB[("현재 스냅샷 저장<br/>stocks / news_articles")]

    S2["⏰ 매 거래일 16:05 (KST)"] --> AR["당일 종가 데이터를<br/>날짜별 아카이브로 적재"]
    AR --> DB2[("daily_stock / daily_news")]
```

- 현재 데이터(`stocks`, `news_articles`)는 매 갱신마다 **덮어쓰기** → 항상 최신 스냅샷만 유지
- 아카이브(`daily_stock`, `daily_news`)는 **append-only** 누적 → 과거 이력 보존 (hot + cold 구조)

---

## 🗂 REST API

| Method | Endpoint | 설명 |
|--------|----------|------|
| `GET` | `/api/stocks/top10` | 급상승 TOP 10 종목 |
| `GET` | `/api/stocks/{id}/news` | 종목별 뉴스 + AI 분석 |
| `POST` | `/api/stocks/refresh` | 수동 갱신 트리거 |
| `GET` | `/api/market/indices` | 코스피·코스닥 실시간 지수 |
| `GET` | `/api/archive/dates` | 아카이브가 있는 날짜 목록 |
| `GET` | `/api/archive/{date}/stocks` | 특정 날짜의 TOP10 |
| `GET` | `/api/archive/stocks/{id}/news` | 아카이브 종목의 뉴스 + AI |
| `POST` | `/api/archive/run` | 오늘 데이터 수동 아카이브 |
| `GET` | `/api/health` | 서버 상태 (종목/뉴스 개수) |

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
        Long tradingValue "거래대금(원)"
        Integer ranking "순위"
        String per_pbr "PER/추정PER/PBR"
        String high52Week "52주 신고가"
        TEXT aiAnalysis "AI 분석"
        LocalDateTime updatedAt "갱신시각"
    }
    news_articles {
        Long id PK
        Long stock_id FK
        String title "제목"
        String link "원문 URL"
        String pubDate "발행일"
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
├── backend/                Spring Boot 3.2 (Java 17, Gradle)
│   └── src/main/java/com/stocknews/
│       ├── controller/     REST API
│       ├── service/        크롤링 · 뉴스 · Gemini 분석 · 지수 · 아카이브
│       ├── scheduler/      정각 갱신 + 16:05 아카이브
│       ├── entity/         Stock, NewsArticle, DailyArchiveStock/News
│       └── repository/     Spring Data JPA
├── frontend/               React 18 (CRA) + Tailwind CSS
│   └── src/
│       ├── api/            axios 클라이언트
│       └── components/     Header, StockCard, NewsPanel
├── docker-compose.yml      백엔드 컨테이너 (외부 Neon DB 연결)
└── docs/DEPLOY.md          배포 가이드 (EC2 / Vercel / Neon)
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
> gemini:
>   api:
>     key: YOUR_GEMINI_API_KEY
>     url: https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent
> ```

---

## ☁️ 배포

| 구성 | 서비스 |
|------|--------|
| **Frontend** | Vercel (Root: `frontend`, env `REACT_APP_API_URL=/api`) |
| **Backend** | AWS EC2 — `docker compose up -d --build` |
| **Database** | Neon (무료 PostgreSQL) |

자세한 절차는 **[docs/DEPLOY.md](docs/DEPLOY.md)** 참고.

---

## 🔑 환경변수

| 변수 | 용도 | 위치 |
|------|------|------|
| `NAVER_CLIENT_ID` / `NAVER_CLIENT_SECRET` | 네이버 검색 API | EC2 `.env` / 로컬 `application-local.yml` |
| `GEMINI_API_KEY` | Gemini 2.5 Flash | 동일 |
| `DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD` | PostgreSQL(운영) | EC2 `.env` |
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
