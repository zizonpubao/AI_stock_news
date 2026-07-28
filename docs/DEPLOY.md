# 배포 가이드 — 백엔드 AWS EC2 / 프론트 Vercel

기존 Railway 배포를 대체합니다.

- **백엔드 + PostgreSQL** → AWS EC2 위 Docker (docker-compose)
- **프론트엔드** → Vercel (GitHub 연동 자동배포)

---

## 1. 백엔드 — AWS EC2

### 1-1. EC2 인스턴스 생성
- AMI: **Ubuntu 22.04 LTS**, 타입: `t3.micro`(프리티어) 이상 권장(Gradle 빌드 메모리 때문에 `t3.small`이면 더 안정적).
- 키 페어(.pem) 생성/다운로드 → SSH 접속용.
- **보안 그룹 인바운드**:
  | 포트 | 소스 | 용도 |
  |------|------|------|
  | 22 | 내 IP | SSH |
  | 80 | 0.0.0.0/0 | HTTP (리버스 프록시 쓸 때) |
  | 443 | 0.0.0.0/0 | HTTPS |
  | 8080 | 0.0.0.0/0 | 백엔드 직접 노출(도메인/HTTPS 붙이기 전 임시) |

### 1-2. 서버 초기 설정 (SSH 접속 후)
```bash
# Docker + compose 플러그인 설치
sudo apt-get update
sudo apt-get install -y ca-certificates curl git
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo tee /etc/apt/keyrings/docker.asc > /dev/null
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" | sudo tee /etc/apt/sources.list.d/docker.list
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo usermod -aG docker $USER && newgrp docker

# t3.micro(1GB) 라면 빌드 OOM 방지용 swap 2GB 권장
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

### 1-3. 앱 배포
> 먼저 아래 **1-5. 클라우드 DB(Neon)** 를 만들어 연결 문자열을 준비하세요.
```bash
git clone https://github.com/zizonpubao/AI_stock_news.git
cd AI_stock_news
cp .env.example .env
nano .env   # Neon DB 연결정보 + 네이버/Gemini API 키 채우기
docker compose up -d --build
docker compose ps
curl http://localhost:8080/api/health
```
외부에서 `http://<EC2-퍼블릭-IP>:8080/api/health` 확인.
(DB 는 EC2 밖 Neon 을 쓰므로 컨테이너는 backend 하나만 뜹니다.)

### 1-4. (권장) 도메인 + HTTPS
브라우저(HTTPS Vercel)에서 `http://IP:8080` 호출은 **혼합 콘텐츠로 차단**됩니다.
백엔드에도 HTTPS가 필요하므로 리버스 프록시를 붙이세요. **Caddy**가 가장 간단(자동 Let's Encrypt):
```bash
# 도메인(A레코드)을 EC2 IP로 지정한 뒤
sudo apt-get install -y debian-keyring debian-archive-keyring apt-transport-https
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt-get update && sudo apt-get install -y caddy
# /etc/caddy/Caddyfile:
#   api.example.com {
#       reverse_proxy localhost:8080
#   }
sudo systemctl restart caddy
```
→ 백엔드 주소는 `https://api.example.com` 이 됩니다.

---

## 2. 프론트엔드 — Vercel

1. [vercel.com](https://vercel.com) 에서 GitHub 로그인 → **Add New → Project** → `AI_stock_news` import.
2. **Root Directory** 를 `frontend` 로 지정 (모노레포 핵심 설정).
3. Framework Preset: **Create React App** (자동 감지). Build/Output 은 `frontend/vercel.json` 에 이미 지정됨.
4. **Environment Variables** 추가:
   | Key | Value |
   |-----|-------|
   | `REACT_APP_API_URL` | `https://api.example.com/api` (백엔드 주소 + `/api`) |
5. Deploy. 이후 `main` push 시 Vercel 이 자동 재배포합니다.

> 백엔드 CORS 는 기본으로 `*.vercel.app` 을 허용합니다. 커스텀 도메인을 쓰면
> EC2 `.env` 의 `CORS_ALLOWED_ORIGINS` 에 해당 도메인을 추가하세요.

---

## 3. GitHub Actions 자동배포 (선택)

`.github/workflows/deploy-backend.yml` 이 `backend/**` 변경 push 시 EC2에 SSH로 재배포합니다.
GitHub → Settings → Secrets and variables → Actions 에 등록:

| Secret | 값 |
|--------|-----|
| `EC2_HOST` | EC2 퍼블릭 IP 또는 도메인 |
| `EC2_USER` | `ubuntu` |
| `EC2_SSH_KEY` | .pem 개인키 **전체 내용** |
| `EC2_APP_DIR` | `/home/ubuntu/AI_stock_news` |

프론트는 Vercel 이 자체적으로 자동배포하므로 별도 워크플로가 필요 없습니다.

---

### 1-5. 클라우드 DB — Neon (무료 PostgreSQL)

DB는 EC2에 두지 않고 무료 서버리스 Postgres인 **Neon**을 씁니다. (대안: Supabase — 방식 동일)

1. [neon.tech](https://neon.tech) 가입 → **Create Project** (region은 가까운 곳, 예: AWS `ap-southeast-1`).
2. 생성되면 **Connection Details** 에 연결 문자열이 나옵니다:
   ```
   postgresql://<user>:<password>@ep-xxxx.ap-southeast-1.aws.neon.tech/<dbname>?sslmode=require
   ```
3. 이를 `.env` 에 **JDBC 형식**으로 나눠서 입력:
   ```
   DATABASE_URL=jdbc:postgresql://ep-xxxx.ap-southeast-1.aws.neon.tech/<dbname>?sslmode=require
   DATABASE_USERNAME=<user>
   DATABASE_PASSWORD=<password>
   ```
   > 포인트: `postgresql://` → `jdbc:postgresql://` 로 바꾸고, `user:password@` 부분은 URL 에서 빼서
   > `DATABASE_USERNAME` / `DATABASE_PASSWORD` 로 분리. `?sslmode=require` 는 그대로 유지(Neon 필수).
4. 스키마는 앱 최초 실행 시 JPA(`ddl-auto: update`)가 자동 생성합니다.

GitHub Actions Secrets 에도 동일하게 넣거나, EC2 의 `.env` 에서 관리하면 됩니다.

---

## 5. 참고 — 로컬에서 배포 구성 검증
```bash
cp .env.example .env   # Neon 연결정보 + API 키 채우기
docker compose up -d --build
curl http://localhost:8080/api/health
```
로컬 개발만 할 때는 DB 없이 H2 로 도는 `./gradlew bootRun` 이 더 간단합니다.
