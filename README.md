# StockPulse — 실시간 급상승 종목 뉴스 AI 분석

네이버 금융 급상승 TOP 10 종목을 크롤링하고, 네이버 뉴스 API로 관련 뉴스를 모아
Google Gemini가 투자 관점에서 분석해주는 웹 서비스입니다. 평일 9~16시 매시 정각 자동 갱신됩니다.

이 저장소는 **백엔드 + 프론트엔드 모노레포**입니다.

```
.
├── backend/    Spring Boot 3.2 (Java 17, Gradle) — 크롤링/뉴스/AI 분석 API
├── frontend/   React 18 (CRA) + Tailwind CSS — 대시보드 UI
└── docs/DEPLOY.md   배포 가이드 (백엔드 AWS EC2 / 프론트 Vercel)
```

## 아키텍처

```
[네이버 금융/뉴스] → backend (크롤링 + Gemini 분석) → PostgreSQL
                                     ↑ REST /api
                          frontend (Vercel) ── 사용자
```

- **backend**: EC2 위 Docker 컨테이너로 실행, PostgreSQL은 무료 클라우드 DB(**Neon**).
- **frontend**: Vercel 정적 배포. `REACT_APP_API_URL` 로 백엔드 주소 주입.

## 로컬 실행

### 백엔드 (H2 인메모리)
```bash
cd backend
# application-local.yml 에 API 키 입력 후 (git 미추적)
./gradlew bootRun
# H2 콘솔: http://localhost:8080/h2-console  (JDBC: jdbc:h2:mem:stockdb / sa / 빈칸)
```

### 프론트엔드
```bash
cd frontend
npm install
npm start   # http://localhost:3000  (기본 API: http://localhost:8080/api)
```

### 백엔드 컨테이너 (Docker, 외부 Neon DB 연결)
```bash
cp .env.example .env   # 값 채우기 (Neon DB 연결정보, API 키)
docker compose up -d --build
# 백엔드: http://localhost:8080/api/health
```

## REST API

| Method | URL | 설명 |
|--------|-----|------|
| GET | `/api/stocks/top10` | 급상승 TOP 10 |
| GET | `/api/stocks/{id}/news` | 종목별 뉴스 + AI 분석 |
| POST | `/api/stocks/refresh` | 수동 갱신 |
| GET | `/api/health` | 상태 확인 |

## 환경변수

| 변수 | 용도 | 설정 위치 |
|------|------|-----------|
| `NAVER_CLIENT_ID` / `NAVER_CLIENT_SECRET` | 네이버 검색 API | EC2 `.env`, 로컬 `application-local.yml` |
| `GEMINI_API_KEY` | Gemini 2.5 Flash | 동일 |
| `DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD` | PostgreSQL (운영) | EC2 `.env` |
| `CORS_ALLOWED_ORIGINS` | 허용 오리진(콤마) | 선택, 기본 localhost + `*.vercel.app` |
| `REACT_APP_API_URL` | 프론트가 호출할 백엔드 주소 | Vercel 환경변수 |

## 배포

[docs/DEPLOY.md](docs/DEPLOY.md) 참고 — 백엔드는 AWS EC2(Docker), 프론트는 Vercel.

> 이 모노레포는 기존 `stock-news-backend` / `stock-news-frontend` 두 저장소를
> 히스토리 보존(`git subtree`)하여 하나로 합친 것입니다.
