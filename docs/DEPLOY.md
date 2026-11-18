# 배포·운영 가이드 — 백엔드 리눅스 서버(네이버클라우드) / 프론트 Vercel

- **백엔드** → 리눅스(Ubuntu) 서버 1대 위 Docker (docker compose). 서버 사양은 가장 작은 급(1 vCPU, 메모리 1GB 안팎)으로 가정하고 설정했다. 네이버클라우드 Micro 서버는 공식 문서상 한국 리전 전용·계정당 1대·체험용(성능 보장 없음)이고 기본 스토리지는 10GB 까지 무료다. 콘솔 생성 화면에서 확인한 사양은 `mi1-g3`(vCPU 1, 메모리 1GB, KVM 3세대), 기본 스토리지 10GB 다.
- **DB** → 외부 PostgreSQL(Neon). 서버에는 DB 가 없다.
- **프론트엔드** → Vercel (GitHub 연동 자동배포). `/api/*` 를 백엔드로 프록시한다.
- **배포 자동화** → GitHub Actions. 테스트·빌드 검증을 통과한 `main` 푸시만 서버에 SSH 로 배포한다.

> 이 문서의 명령은 "새 서버를 처음 세우는 사람"이 위에서 아래로 그대로 따라 할 수 있게 적었다.
> 네이버클라우드 콘솔의 메뉴 이름·요금·무료 조건처럼 직접 확인하지 못한 것은 `[확인 필요: …]` 로 표시했다.

---

## 0. 전체 구조

```
브라우저 ──HTTPS──> Vercel(프론트 + /api 프록시) ──HTTP :8080──> 리눅스 서버(Docker: backend) ──SSL──> Neon(PostgreSQL)
GitHub Actions (테스트 → JAR 빌드) ──SSH :22──> 리눅스 서버 (JAR 수신 → 런타임 이미지 생성·기동 → /api/health 확인)
```

| 위치 | 방화벽 계층 |
|------|-------------|
| 클라우드 쪽 | 네이버클라우드 ACG(서버 앞단 방화벽). 규칙은 프로토콜·허용 포트·접근 소스(IP/CIDR/ACG 이름) 단위로 인바운드·아웃바운드를 따로 설정한다(공식 가이드: https://guide.ncloud-docs.com/docs/server-acg-vpc). 직접 만든 ACG 에는 기본 규칙이 없다. [확인 필요: VPC 생성 시 자동으로 생기는 Default ACG 의 기본 규칙] |
| 서버 안 | ufw (OS 방화벽) |

두 겹으로 막는다. 한쪽 설정이 틀려도 다른 쪽이 막아 주게 하려는 것이다.

---

## 1. 서버 생성 직후 첫 설정

### 1-1. 서버 생성 (콘솔)
- OS 이미지: Ubuntu LTS. 네이버클라우드 OS 수명주기 문서 기준 Ubuntu 22.04 는 2028-04-30 까지 지원 예정(https://guide.ncloud-docs.com/docs/ncp-os-lifecycle). 이 서버는 콘솔에서 제공하는 `ubuntu-24.04-base` 이미지로 만들었다.
- 인증키(SSH 키) 생성·다운로드. VPC 기준 최초 접속은 콘솔의 '관리자 비밀번호 확인'에 인증키(.pem)를 올려 관리자 계정·비밀번호를 확인한 뒤, 공인 IP 와 ACG 22번 허용을 설정하고 SSH 로 접속한다(https://guide.ncloud-docs.com/docs/server-access-vpc). 즉 처음에는 root 비밀번호 로그인이므로, 1-2·1-3 의 배포 사용자 생성과 SSH 키 전용 설정을 가장 먼저 한다.
- 공인 IP 할당과 ACG 설정은 1-4 와 2장에서 다룬다. 공인 IP 는 별도로 신청해 서버에 연결하며, 서버에 연결하지 않아도 보유하는 동안 과금된다(https://guide.ncloud-docs.com/docs/server-publicip-vpc). Micro 서버 무료와는 별개 비용일 수 있다. 콘솔 안내 기준 공인 IP 이용료는 월 4,032원이다. [확인 필요: 서버 정지·재시작 시 IP 유지 여부] — Vercel 프록시 주소가 이 IP 이므로 **고정 IP 여부가 중요하다**(이전 EC2 에서 재부팅 때 IP 가 바뀌어 프록시를 두 번 고친 이력이 있다).

### 1-2. 패키지 업데이트 (최초 접속한 관리자 계정으로)
```bash
sudo apt-get update
sudo apt-get upgrade -y
sudo apt-get install -y ca-certificates curl git ufw fail2ban unattended-upgrades
[ -f /var/run/reboot-required ] && echo "재부팅 필요" || echo "재부팅 불필요"
# 커널 등이 갱신돼 재부팅이 필요하다고 나오면: sudo reboot  (재접속 후 계속)
```

### 1-3. 배포 전용 일반 사용자 만들기
root 로 배포하지 않는다. 배포(GitHub Actions)와 운영 모두 `deploy` 라는 일반 사용자로 한다.
```bash
sudo adduser --disabled-password --gecos "" deploy
sudo usermod -aG sudo deploy
sudo passwd deploy      # sudo 실행용 비밀번호. SSH 비밀번호 로그인은 아래에서 막는다.
```
SSH 키 쌍은 **내 PC** 에서 배포 전용으로 새로 만든다(서버 생성 때 받은 키와 분리).
```bash
# 내 PC 에서
ssh-keygen -t ed25519 -f ~/.ssh/stockpulse_deploy -C "stockpulse-deploy"
cat ~/.ssh/stockpulse_deploy.pub     # 이 한 줄을 서버에 등록
```
```bash
# 서버에서
sudo install -d -m 700 -o deploy -g deploy /home/deploy/.ssh
echo '<위에서 출력한 공개키 한 줄>' | sudo tee /home/deploy/.ssh/authorized_keys
sudo chown deploy:deploy /home/deploy/.ssh/authorized_keys
sudo chmod 600 /home/deploy/.ssh/authorized_keys
```
**새 터미널**을 열어 `ssh -i ~/.ssh/stockpulse_deploy deploy@<서버IP>` 로 접속되는지 먼저 확인한다. 접속이 되기 전에는 다음 단계로 가지 않는다.

### 1-4. SSH 접근 통제: 키 로그인만, 비밀번호·root 직접 로그인 차단
Ubuntu 클라우드 이미지는 `/etc/ssh/sshd_config.d/` 에 기본 설정이 있을 수 있고, **먼저 읽힌 값이 이긴다.** 그래서 파일 이름을 `00-` 으로 시작해 가장 먼저 읽히게 한다.
```bash
sudo tee /etc/ssh/sshd_config.d/00-hardening.conf > /dev/null <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
PubkeyAuthentication yes
AllowUsers deploy
MaxAuthTries 3
LoginGraceTime 30
EOF
sudo sshd -t && echo "문법 OK"          # 오류가 없어야 한다
sudo sshd -T | grep -Ei 'passwordauthentication|permitrootlogin|allowusers'   # 실제 적용값 확인
sudo systemctl restart ssh               # 서비스 이름이 다르면 sshd. [확인 필요: 이미지의 ssh 서비스 이름]
```
**현재 접속을 끊지 말고** 새 터미널에서 (1) `deploy` 키 접속 성공, (2) `ssh -o PubkeyAuthentication=no deploy@<IP>` 가 거부되는지 확인한다. 실패하면 열어 둔 기존 세션에서 `/etc/ssh/sshd_config.d/00-hardening.conf` 를 지우고 되돌린다.

> `AllowUsers deploy` 로 제한하면 관리자 계정은 SSH 로 못 들어온다. 콘솔 접속(VNC/시리얼) 같은 비상 경로가 있는지 확인해 둔다. 네이버클라우드 콘솔의 서버 목록에는 '서버 접속 콘솔' 버튼이 있다. [확인 필요: 이 기능으로 SSH 없이 로그인되는지 — 직접 써보지 않음]

### 1-5. 서버 안 방화벽(ufw)
```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 22/tcp comment 'SSH'
sudo ufw allow 8080/tcp comment 'backend (Vercel proxy)'
sudo ufw enable
sudo ufw status verbose
```
> **Docker 주의:** `docker compose` 가 `ports: "8080:8080"` 으로 공개한 포트는 Docker 가 iptables 에 직접 규칙을 넣기 때문에 **ufw 규칙을 우회한다.** 즉 8080 은 ufw 로 막아도 열려 있다. 8080 의 접근 통제는 클라우드 ACG 가 실질적인 방어선이다(2장). ufw 는 그 외 포트(예: 실수로 열린 서비스)를 막는 역할이다.

### 1-6. 로그인 시도 차단 (fail2ban)
SSH 가 인터넷에 열려 있으면 무차별 대입 시도가 계속 들어온다. 키 전용이라 뚫리지는 않지만 로그와 부하를 줄이기 위해 반복 실패 IP 를 일정 시간 차단한다.
```bash
sudo tee /etc/fail2ban/jail.d/sshd.local > /dev/null <<'EOF'
[sshd]
enabled = true
maxretry = 5
findtime = 10m
bantime = 1h
EOF
sudo systemctl enable --now fail2ban
sudo fail2ban-client status sshd
```
> GitHub Actions 가 접속에 연속 실패하면(키 오류 등) 그 러너 IP 가 잠시 차단될 수 있다. 배포가 갑자기 막히면 `sudo fail2ban-client status sshd` 로 확인한다.

---

## 2. 클라우드 방화벽(ACG)에서 열 포트

| 포트 | 소스 | 이유 |
|------|------|------|
| 22/tcp | 아래 선택지 참고 | 관리 접속 + GitHub Actions 배포 |
| 8080/tcp | 0.0.0.0/0 | Vercel 이 백엔드로 프록시. Vercel 의 출발 IP 는 고정이 아니라 소스를 좁힐 수 없다 |
| 80, 443 | 지금은 열지 않음 | HTTPS 리버스 프록시를 붙일 때(문서 끝 "다음 개선") 연다 |

**아웃바운드 규칙도 반드시 확인한다.** 공식 가이드는 아웃바운드 규칙이 없으면 서버에서 나가는 패킷이 차단될 수 있다고 안내한다. 이 서버는 네이버 증권·네이버 검색 API·Gemini·Neon(PostgreSQL 5432)·GitHub·Docker Hub 로 나가야 하므로 아웃바운드 TCP 443, 5432 (및 패키지 저장소용 80)을 허용해야 한다. [확인 필요: 규칙 개수 제한, Default ACG 에 기본으로 열려 있는 포트]. 사용하지 않는 기본 규칙은 지운다.

### 22번 포트를 어디까지 열 것인가
GitHub Actions 러너의 IP 는 계속 바뀌는 대규모 대역이라, "내 IP 만" 허용하면 자동 배포가 불가능하다. 선택지는 다음과 같다.

| 선택 | 방법 | 위험 | 보완책 |
|------|------|------|--------|
| A. 전체 공개 (0.0.0.0/0) | 22 를 모두 허용 | 무차별 대입·스캔이 계속 들어옴. 설정 실수나 SSH 취약점 발생 시 노출 | 키 전용 로그인(1-4), root 차단, `AllowUsers`, fail2ban(1-6), 자동 보안 업데이트(3장). **이 프로젝트의 기본 선택** — 자동 배포를 유지하는 가장 단순한 방법 |
| B. GitHub Actions IP 대역만 허용 | `https://api.github.com/meta` 의 `actions` 대역을 ACG 에 등록 | 대역이 수백 개이고 수시로 바뀌어 관리가 어렵다. 규칙 개수 제한에 걸릴 수 있다 [확인 필요: ACG 규칙 개수 제한]. GitHub 러너 전체에 열리므로 완전히 좁은 것도 아니다 | 정기적으로 대역 갱신 |
| C. 평소엔 내 IP 만, 배포 때만 임시 허용 | 배포 직전 수동으로 규칙 추가 → 끝나면 삭제 | 자동화가 깨지고 사람 손이 든다. 지우는 걸 잊을 수 있다 | 규칙 삭제를 점검표에 넣는다 |
| D. 22 를 닫고 서버가 가져가는 방식 | 서버에 self-hosted runner 를 설치하거나, 서버가 주기적으로 `git pull` | runner 상시 실행으로 메모리 소모(1GB 서버에 부담), 구성 복잡 | 이번 범위 밖 |

선택 A 를 쓰는 경우에도 ACG 에서 **내 IP 용 규칙과 분리**해 두면, 나중에 B/C 로 좁힐 때 쉽다. 면접에서는 "자동 배포와 접근 통제의 트레이드오프이며, 키 전용·fail2ban 으로 보완했고 개선 방향은 B 또는 D"라고 설명할 수 있다.

---

## 3. 보안 패치

### 3-1. 자동 보안 업데이트 (unattended-upgrades)
```bash
sudo dpkg-reconfigure -plow unattended-upgrades     # "Yes" 선택
cat /etc/apt/apt.conf.d/20auto-upgrades              # 아래 두 줄이 "1" 인지 확인
#   APT::Periodic::Update-Package-Lists "1";
#   APT::Periodic::Unattended-Upgrade "1";
sudo unattended-upgrade --dry-run --debug 2>&1 | tail -20   # 동작 점검(실제 설치 안 함)
```
기본값은 보안 저장소의 업데이트만 설치하고, **자동 재부팅은 하지 않는다.** 커널 패치는 재부팅해야 적용되므로 자동 재부팅 여부를 정한다.
```bash
sudo nano /etc/apt/apt.conf.d/50unattended-upgrades
#   Unattended-Upgrade::Automatic-Reboot "true";          # 자동 재부팅을 쓸 때만
#   Unattended-Upgrade::Automatic-Reboot-Time "04:00";    # 서버 시간대 기준. 수집 스케줄(평일 8~20시) 밖 시각
```
서버 시간대가 UTC 일 수 있다(`timedatectl`). 컨테이너는 `restart: unless-stopped` 와 Docker 서비스 자동 시작(4장)으로 재부팅 후 자동 복구된다. 업데이트 이력은 `/var/log/unattended-upgrades/` 에 남는다.

### 3-2. 수동 패치·재부팅 (월 1회 + 중대한 취약점 공지 때)
```bash
sudo apt-get update
apt list --upgradable                    # 무엇이 올라갈지 확인
sudo apt-get upgrade -y
[ -f /var/run/reboot-required ] && cat /var/run/reboot-required.pkgs
sudo reboot                              # 필요할 때만. 배포 중이 아닐 때 한다
# 재접속 후
docker compose -f ~/AI_stock_news/docker-compose.yml ps
curl -s http://localhost:8080/api/health
```
Docker 이미지의 베이스(`eclipse-temurin:17-jre`) 패치는 apt 로 되지 않는다. 베이스 이미지를 새로 받아 재빌드해야 반영된다:
```bash
cd ~/AI_stock_news && docker compose -f docker-compose.server.yml build --pull backend && docker compose -f docker-compose.server.yml up -d backend
```

---

## 4. Docker 설치, 스왑, 앱 배포

### 4-1. Docker + compose 플러그인
`deploy` 사용자로 접속해서 실행한다.
```bash
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo tee /etc/apt/keyrings/docker.asc > /dev/null
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" | sudo tee /etc/apt/sources.list.d/docker.list
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
sudo systemctl enable --now docker          # 재부팅 후 자동 시작
sudo usermod -aG docker deploy              # 재로그인해야 적용
docker --version && docker compose version
```
> `docker` 그룹 사용자는 사실상 root 와 같은 권한이다. 배포 키가 유출되면 서버 전체가 위험하다는 뜻이므로, 이 키는 GitHub Secrets 외에 복사해 두지 않는다.

### 4-2. 스왑 (실행 중 메모리 여유용)
**이 서버에서는 빌드를 하지 않는다.** 서버 설치 직후 실측값(Docker·Compose·스왑 설치 후):
```
Mem:   961Mi total, 555Mi used, 405Mi available
Swap:  2.0Gi total, 0B used
/dev/vda2  9.8G  7.4G used  1.9G avail  80%
```
서버에서 `docker compose up --build` 로 Gradle 빌드를 하려면 JDK 이미지, Gradle 배포판, 의존성 캐시가 필요한데 남은 디스크 1.9GB 와 가용 메모리 405MB 로는 감당하기 어렵다. 그래서 JAR 은 GitHub Actions 러너에서 빌드하고(`./gradlew bootJar`), 서버는 JRE 이미지(`backend/Dockerfile.runtime`)에 그 JAR 만 넣어 실행한다. 스왑은 이제 빌드용이 아니라, 실행 중 메모리가 잠깐 모자랄 때 프로세스가 바로 죽지 않게 하는 여유분이다(2GB 유지). 다만 스왑도 디스크 2GB 를 차지하므로 디스크 사용률(6-3)을 함께 본다.
```bash
free -h; swapon --show                      # 스왑이 이미 있는지 확인
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-swap.conf && sudo sysctl --system
free -h                                     # Swap: 2.0Gi 확인
```
스왑은 디스크를 메모리처럼 쓰는 것이라 느리다. 평상시 운영에서 스왑을 계속 많이 쓰고 있다면 메모리가 부족한 것이다(6장 점검표).

### 4-3. 저장소 clone, `.env` 작성 (서버에서 하는 일은 여기까지)
서버에서 직접 하는 일은 **저장소 clone, `.env` 작성, GitHub Secrets 등록(4-4)** 까지다. **첫 기동도 서버에서 하지 않고 배포 워크플로가 한다(4-5).** 먼저 5장의 Neon 연결 정보를 준비한다.
```bash
cd ~
git clone https://github.com/zizonpubao/AI_stock_news.git
cd AI_stock_news            # 앱 경로: /home/deploy/AI_stock_news
cp .env.example .env
chmod 600 .env
nano .env
```
`.env` 에 채울 항목(이름만. 값은 이 문서·저장소에 적지 않는다):

| 항목 | 설명 |
|------|------|
| `DATABASE_URL` | Neon 연결 문자열을 JDBC 형식으로 변환한 값 (5장) |
| `DATABASE_USERNAME` | DB 사용자 |
| `DATABASE_PASSWORD` | DB 비밀번호 |
| `NAVER_CLIENT_ID` / `NAVER_CLIENT_SECRET` | 네이버 검색 API |
| `GEMINI_API_KEY` | Google Gemini |
| `ADMIN_TOKEN` | 관리용 API(`POST /api/stocks/refresh`, `POST /api/archive/run`)를 호출할 때 `X-Admin-Token` 헤더로 보내는 값. 추측 어려운 긴 임의 문자열을 직접 생성(예: `openssl rand -hex 32`). 비우면 두 API 는 403 |
| `CORS_ALLOWED_ORIGINS` | 선택. 커스텀 도메인을 쓸 때 추가 |

> 서버의 저장소는 배포 때마다 워크플로가 빌드한 커밋으로 `git checkout -f --detach` 된다. 저장소는 HTTPS 로 clone 하므로 서버의 `git fetch` 는 공개 저장소 기준으로 인증 없이 동작한다. [확인 필요: 저장소가 비공개라면 서버에 읽기 전용 자격(deploy key 등)이 필요하다]

기동 확인은 첫 배포가 끝난 뒤에 한다.
```bash
cd ~/AI_stock_news
docker compose -f docker-compose.server.yml ps
docker compose -f docker-compose.server.yml logs --tail=100 backend
curl http://localhost:8080/api/health
docker stats --no-stream
```
외부(내 PC)에서 `http://<서버 공인IP>:8080/api/health` 도 확인한다. 안 열리면 ACG(2장)를 먼저 본다.

> 컨테이너는 메모리 512MB 로 제한(`docker-compose.server.yml` 의 `mem_limit`)되고 JVM 힙은 그 50%(`-XX:MaxRAMPercentage=50`, 약 256MB)로 잡힌다. 이 값은 서버 실측 가용 메모리 405MB 를 근거로 정한 값이다. 실제 컨테이너 사용량은 첫 배포 후 `docker stats --no-stream` 으로 확인하고, OOM 으로 재시작되면(`docker inspect -f '{{.State.OOMKilled}}' stockpulse-backend`) 조정한다.

### 4-4. GitHub Secrets
GitHub → 저장소 Settings → Secrets and variables → Actions → Repository secrets

| Secret | 값 |
|--------|-----|
| `SERVER_HOST` | 서버 공인 IP 또는 도메인. 예: `211.188.54.232` |
| `SERVER_USER` | `deploy` |
| `SERVER_SSH_KEY` | 1-3 에서 만든 `~/.ssh/stockpulse_deploy` **개인키 전체 내용** (`-----BEGIN` ~ `END` 줄 포함) |
| `SERVER_APP_DIR` | 저장소를 clone 한 절대 경로. 예: `/home/deploy/AI_stock_news` |
| `SERVER_SSH_PORT` | 선택. 22 가 아닌 포트를 쓸 때만(없으면 22) |

앱 비밀 값(DB·API 키)은 GitHub 가 아니라 서버의 `.env` 에만 둔다. 워크플로는 그 파일을 건드리지 않는다.

### 4-5. 배포 워크플로 동작
- `.github/workflows/ci.yml` — 모든 브랜치 push 와 Pull Request 에서 백엔드 테스트(JDK 17), 프론트 빌드(Node 20)를 실행한다.
- `.github/workflows/deploy-backend.yml` — `main` 푸시(백엔드·compose·워크플로 파일 변경 시) 또는 수동 실행에서만 돈다. 순서:
  1. `ci.yml` 을 재사용해 검증. **통과한 경우에만** 다음으로 간다.
  2. 러너에서 `./gradlew bootJar -x test` 로 JAR 빌드(테스트는 1단계에서 이미 돌았다).
  3. SSH 키를 러너 파일(권한 600)로 쓰고, `ssh-keyscan` 으로 받은 서버 호스트 키를 `known_hosts` 에 넣는다. 서드파티 SSH 액션 없이 러너 기본 `ssh`/`scp` 를 쓴다.
  4. `scp` 로 JAR 을 서버의 `<앱 경로>/backend/app.jar.new` 로 올린다.
  5. 서버에서 저장소를 **워크플로가 빌드한 커밋 그대로** 맞추고(`git fetch --prune origin` → `git checkout -f --detach <커밋>`), 기존 `app.jar` 를 `app.jar.prev` 로 남긴 뒤 `app.jar.new` 를 `app.jar` 로 교체, `docker compose -f docker-compose.server.yml up -d --build`, `docker image prune -f`.
  6. `http://localhost:8080/api/health` 를 최대 약 3분(36회 x 5초) 재시도. 응답이 없으면 컨테이너 로그 마지막 100줄과 되돌리는 방법을 출력하고 실패로 끝난다.
- **첫 기동도 이 워크플로가 한다.** GitHub → Actions → "Deploy backend" → Run workflow. 실행할 **브랜치를 고를 수 있고**, main 이 아닌 브랜치를 골라도 그 브랜치의 커밋이 배포된다(main 에 합치기 전 시험 배포 가능).
- 호스트 키 확인 방식: 실행할 때마다 `ssh-keyscan` 으로 받은 키를 신뢰한다(최초 신뢰, TOFU). 러너는 매번 새 환경이라 이전 실행에서 저장한 키와 비교하지는 않는다. 즉 러너와 서버 사이 경로에서 가로채는 공격자가 있으면 막지 못한다. 더 엄격하게 하려면 서버 호스트 키 지문을 Secret 으로 고정해 비교하는 방식으로 개선할 수 있다.
- 이 파이프라인은 실제 서버·실제 GitHub 에서 아직 실행해 본 적이 없다(YAML 과 셸 문법만 로컬 검증). 첫 배포는 수동 실행으로 로그를 지켜본다.

---

## 5. 클라우드 DB — Neon (PostgreSQL)

DB 는 서버에 두지 않고 Neon 을 쓴다. (대안: Supabase — 방식 동일)

1. [neon.tech](https://neon.tech) 가입 → **Create Project**.
2. **Connection Details** 의 연결 문자열:
   ```
   postgresql://<user>:<password>@ep-xxxx.<region>.aws.neon.tech/<dbname>?sslmode=require
   ```
3. `.env` 에 **JDBC 형식**으로 나눠 입력:
   ```
   DATABASE_URL=jdbc:postgresql://ep-xxxx.<region>.aws.neon.tech/<dbname>?sslmode=require
   DATABASE_USERNAME=<user>
   DATABASE_PASSWORD=<password>
   ```
   > `postgresql://` → `jdbc:postgresql://` 로 바꾸고, `user:password@` 는 URL 에서 빼서 `DATABASE_USERNAME` / `DATABASE_PASSWORD` 로 분리한다. `?sslmode=require` 는 그대로 둔다(Neon 필수).
4. 스키마는 앱 최초 실행 시 JPA(`ddl-auto: update`)가 자동 생성한다.

---

## 6. 운영 점검

### 6-1. 수집 상태 확인: `/api/health`
```bash
curl -s http://localhost:8080/api/health
# 예) {"status":"UP","stockCount":10,"newsCount":...,"lastCollectedAt":"...","lastAttemptAt":"...","lastAttemptOk":true}
```
| 필드 | 의미 |
|------|------|
| `status` | 앱이 요청에 응답 중이면 `UP`. 수집이 잘 되는지는 알려 주지 않는다 |
| `stockCount`, `newsCount` | DB 에 저장된 종목·뉴스 수 |
| `lastCollectedAt` | 종목 데이터가 **마지막으로 성공적으로 수집된 시각**(없으면 `null`) |
| `lastAttemptAt` | 마지막 수집 **시도** 시각 |
| `lastAttemptOk` | 마지막 시도의 성공 여부. `false` 면 수집 실패(직전 데이터를 그대로 유지 중). 앱 기동 후 아직 시도 전이면 `null` |

해석 요령: 서버는 떠 있는데(`UP`) 데이터가 낡은 경우를 이 필드들로 구분한다. 수집은 평일 8~20시 매시 정각에 도므로(`StockScheduler`) 밤·주말에 `lastAttemptAt` 이 오래된 것은 정상이다. 평일 업무 시간에 `lastAttemptAt` 이 1시간 넘게 갱신되지 않거나 `lastAttemptOk=false` 이면 8장의 수집 실패 절차로 간다.

### 6-2. 로그·상태 확인
```bash
cd ~/AI_stock_news
docker compose ps                              # 상태, 재시작 여부
docker compose logs --tail=200 backend         # 최근 로그
docker compose logs -f --since=30m backend     # 실시간
docker compose logs backend | grep -i -E "ERROR|수집 실패"
docker stats --no-stream                       # 컨테이너 CPU·메모리
docker inspect -f 'restarts={{.RestartCount}} oom={{.State.OOMKilled}}' stockpulse-backend
```
SSH 로그인 시도: `sudo journalctl -u ssh --since "1 day ago" | grep -i "failed" | tail`, 차단 현황: `sudo fail2ban-client status sshd`. [확인 필요: 이미지의 SSH 로그 위치(journald 인지 /var/log/auth.log 인지)]

### 6-3. 디스크·메모리
```bash
df -h /                       # 디스크 사용률
free -h                       # 메모리·스왑
docker system df              # Docker 가 쓰는 용량
uptime                        # 부하(1 vCPU 이므로 load 가 1 을 넘으면 포화)
```
디스크 기준값: 서버 설치 직후 측정에서 이미 **80% 사용(7.4G/9.8G, 여유 1.9G)** 이었다. 여유가 작으므로 배포 때마다 `df -h /` 를 확인한다. 배포 워크플로는 배포 끝에 `docker image prune -f` 로 안 쓰는 이미지를 지운다.

디스크가 찼을 때:
```bash
docker image prune -f                        # 안 쓰는 이미지 정리
sudo apt-get clean                           # apt 캐시 삭제
sudo journalctl --vacuum-size=100M           # 시스템 로그를 100MB 이하로
```

### 6-4. 점검표
| 주기 | 항목 | 정상 기준 |
|------|------|-----------|
| 매일 (또는 평일 업무 시간 중 1회) | `/api/health` | `lastAttemptOk=true`, `lastAttemptAt` 최근 |
| 매일 | 서비스 화면 | Vercel 주소에서 종목 10개·분석 표시 |
| 주 1회 | `docker compose ps`, `RestartCount`, `OOMKilled` | 재시작 0, OOM false |
| 주 1회 | `df -h /`, `docker system df`, `free -h` | 디스크 사용률이 설치 직후 실측(80%)보다 늘지 않음, 스왑 사용 적음 |
| 주 1회 | `docker compose logs` 의 ERROR, SSH 실패 로그, fail2ban 현황 | 반복되는 오류 없음 |
| 월 1회 | 수동 패치·재부팅(3-2), 자동 업데이트 이력 | 대기 중인 보안 업데이트 없음 |
| 월 1회 | DB 백업 파일 생성·복원 시험(7장) | 복원 성공 |
| 분기 1회 | API 키·SSH 키·`ADMIN_TOKEN` 교체 여부, ACG 규칙 검토 | 불필요한 규칙 없음 |

---

## 7. 백업·복구

### 7-1. 무엇을 백업하나
- **데이터(DB)**: 서버가 아니라 Neon 에 있다. 서버를 잃어도 데이터는 남지만, **DB 를 잃으면(실수 삭제·계정 문제·Neon 장애)** 복구할 길이 없다. 그래서 서버 백업보다 DB 백업이 핵심이다. Neon 자체의 시점 복구·보존 기간은 플랜에 따라 다르다. [확인 필요: 사용 중인 Neon 플랜의 백업/시점 복구 제공 여부와 보존 기간]
- **서버**: 코드는 GitHub 에, 설정은 이 문서와 `.env` 에 있다. 서버 안에서 유일하게 대체 불가능한 것은 `.env`(비밀 값) 이므로 **비밀번호 관리자 등 서버 밖의 안전한 곳에 별도로 보관**한다.

### 7-2. `pg_dump` 로 백업
`pg_dump` 는 서버 버전과 같거나 더 높은 버전이 필요하다. [확인 필요: Neon 의 PostgreSQL 메이저 버전]
```bash
sudo apt-get install -y postgresql-client
mkdir -p ~/backup && chmod 700 ~/backup
# 비밀번호를 명령줄에 직접 쓰지 않는다(ps·history 에 남음). ~/.pgpass 사용:
#   형식: host:port:dbname:user:password   (5432 포트)  → chmod 600 ~/.pgpass
echo 'ep-xxxx.<region>.aws.neon.tech:5432:<dbname>:<user>:<password>' >> ~/.pgpass && chmod 600 ~/.pgpass
pg_dump -h ep-xxxx.<region>.aws.neon.tech -U <user> -d <dbname> -Fc -f ~/backup/stockpulse_$(date +%Y%m%d).dump
ls -lh ~/backup
```
주기 실행(예: 매일 03:30, 14일치 유지) — `crontab -e`:
```
30 3 * * * pg_dump -h ep-xxxx.<region>.aws.neon.tech -U <user> -d <dbname> -Fc -f $HOME/backup/stockpulse_$(date +\%Y\%m\%d).dump && find $HOME/backup -name 'stockpulse_*.dump' -mtime +14 -delete
```
백업 파일을 **같은 서버에만** 두면 서버와 함께 사라진다. 내 PC 로 주기적으로 내려받는다: `scp -i ~/.ssh/stockpulse_deploy deploy@<서버IP>:backup/stockpulse_YYYYMMDD.dump .` 또는 외부 저장소에 올린다. 덤프에는 뉴스·분석 데이터가 들어 있으므로 공개 장소에 두지 않는다.

### 7-3. 복원
복원은 **새로 만든 빈 DB**(Neon 에서 새 프로젝트 또는 새 데이터베이스)로 먼저 해 본다. 운영 DB 를 덮어쓰기 전에 검증하기 위해서다.
```bash
pg_restore -h <새 호스트> -U <user> -d <새 dbname> --no-owner --no-privileges ~/backup/stockpulse_YYYYMMDD.dump
psql -h <새 호스트> -U <user> -d <새 dbname> -c "select count(*) from daily_stock;"   # 테이블: stocks, news_articles, daily_stock(아카이브), daily_news
```
그다음 서버 `.env` 의 `DATABASE_*` 를 새 DB 로 바꾸고 `docker compose -f docker-compose.server.yml up -d backend` 로 재기동한다. 복원 시험은 월 1회 점검표에 넣는다. 백업은 복원해 봐야 의미가 있다.

### 7-4. 서버를 통째로 잃었을 때 (이번 EC2 → 새 서버 이전이 실제 사례)
이 프로젝트는 AWS EC2 를 더 쓸 수 없게 되어 새 서버로 옮겼다. 데이터가 외부 DB 에 있었기 때문에 서버는 "다시 세우면 되는 것"이었다. 순서:
1. 새 서버 생성, 공인 IP 확보 (1-1)
2. 1장 첫 설정: 업데이트, `deploy` 사용자, SSH 키 전용, ufw, fail2ban
3. 2장 ACG 설정
4. 3장 자동 보안 업데이트
5. 4장 Docker 설치, 스왑, clone, `.env` 작성(보관해 둔 값 사용), GitHub Secrets 의 `SERVER_HOST` 갱신 후 Actions 에서 배포 워크플로 실행(첫 기동)
6. GitHub Secrets 의 `SERVER_HOST` 를 새 IP 로, 새 배포 키를 만들었다면 `SERVER_SSH_KEY` 도 교체 → 수동 배포 실행
7. **Vercel 프록시 주소를 새 IP 로 변경** (9장) — 이걸 빠뜨리면 서버는 멀쩡한데 화면은 계속 죽어 있다
8. `/api/health` 와 화면으로 확인. 다음 평일 정시 수집이 `lastAttemptOk=true` 로 도는지 본다
9. 옛 서버의 접근 정보(키·IP)를 폐기한다

DB 까지 잃은 경우는 7-3 으로 복원한 뒤 5번 단계에서 연결 정보를 바꾼다. 백업이 없으면 데이터는 복구할 수 없고, 앱은 빈 DB 에서 스키마를 새로 만들고 다음 수집부터 다시 쌓는다(과거 아카이브는 사라진다).

---

## 8. 장애 대응

### 8-1. 배포 실패 → 이전 버전으로 되돌리기
워크플로는 헬스체크 실패 시 로그를 출력하고 되돌리는 방법을 알려 준다. 방법은 두 가지다.

**(a) 정석: Actions 에서 이전 커밋을 골라 워크플로를 다시 실행.** GitHub → Actions → "Deploy backend" → Run workflow 에서, 정상이던 시점의 브랜치(또는 그 커밋을 가리키는 브랜치)를 골라 실행한다. 워크플로가 그 커밋을 다시 빌드해 JAR 과 서버 저장소를 그 상태로 맞춘다. 서버를 되돌린 뒤에는 GitHub 쪽에서도 원인 커밋을 `git revert` 로 되돌려 다음 배포가 같은 문제를 반복하지 않게 한다.

**(b) 급할 때: 서버에서 직전 JAR 로 복구.** 직전 배포의 JAR 이 `app.jar.prev` 로 남아 있다.
```bash
cd ~/AI_stock_news/backend
ls -l app.jar app.jar.prev
mv -f app.jar.prev app.jar
cd ..
docker compose -f docker-compose.server.yml up -d --build
curl -s http://localhost:8080/api/health
```
(b) 는 JAR 만 되돌리고 서버의 저장소 체크아웃(compose 파일 등)은 그대로다. 설정 파일까지 바뀐 배포였다면 (a) 를 쓴다. `app.jar.prev` 는 직전 한 번분만 남는다(배포할 때마다 덮어쓴다). 테스트·빌드가 실패한 경우는 서버 단계까지 가지 않으므로 서버는 이전 상태 그대로이다.

### 8-2. 컨테이너가 안 뜨거나 계속 재시작될 때
```bash
docker compose ps
docker compose logs --tail=200 backend
docker inspect -f 'oom={{.State.OOMKilled}} exit={{.State.ExitCode}}' stockpulse-backend
free -h; df -h /
```
- `oom=true` → 메모리 부족. `docker-compose.server.yml` 의 `mem_limit` 을 올리거나 스왑·서버 사양을 확인
- 로그에 DB 연결 오류 → `.env` 의 `DATABASE_*`, Neon 상태, `sslmode=require` 확인
- 서버 이미지 빌드 중 오류 → `backend/app.jar` 가 있는지(`ls -l ~/AI_stock_news/backend/`), 디스크 여유(`df -h /`)를 확인

### 8-3. 수집 실패 (`lastAttemptOk=false`, 또는 `lastAttemptAt` 이 갱신되지 않음)
확인 순서:
1. `curl -s localhost:8080/api/health` 로 어느 필드가 이상한지 본다.
2. `docker compose logs --since=2h backend | grep -E "수집 실패|ERROR"` 로 오류 문구를 찾는다.
3. 종목 수집 단계 실패 — 네이버 모바일 증권의 **비공식 주소**를 쓰므로 응답 형식이나 주소가 바뀌었을 수 있다. 서버에서 직접 호출해 응답을 확인하고 백엔드 담당에게 넘긴다. 이 경우 앱은 직전 데이터를 유지하며 가짜 데이터를 만들지 않는다.
4. 뉴스 수집 실패 — 네이버 검색 API 의 키·일일 호출 한도를 확인한다.
5. AI 분석 실패 — Gemini 키·한도·모델 응답을 확인한다. 화면의 분석 상태(`analysisStatus`)에 반영된다.
6. 서버에서 외부로 나가는 통신이 되는지(`curl -sI https://openapi.naver.com`), DB 접속이 되는지 확인한다.
7. 원인을 고친 뒤 즉시 다시 수집하려면 관리용 API 를 쓴다(값은 서버 `.env` 의 `ADMIN_TOKEN`).
   ```bash
   curl -X POST -H "X-Admin-Token: $ADMIN_TOKEN" http://localhost:8080/api/stocks/refresh
   ```
   (`ADMIN_TOKEN` 을 셸 변수로 넣을 때 history 에 남지 않게 주의: 명령 앞에 공백을 두거나 `read -s` 사용)

---

## 9. 프론트엔드 — Vercel

### 9-1. 최초 설정
1. [vercel.com](https://vercel.com) 에서 GitHub 로그인 → **Add New → Project** → `AI_stock_news` import.
2. **Root Directory** 를 `frontend` 로 지정(모노레포 핵심 설정).
3. Framework Preset: **Create React App**. Build/Output 은 `frontend/vercel.json` 에 지정돼 있다.
4. Environment Variables: `REACT_APP_API_URL`. [확인 필요: 현재 Vercel 에 설정된 값. `/api/*` 프록시(`vercel.json` rewrites)를 쓰는 구성이라면 `/api` 여야 브라우저가 같은 출처로 호출한다]
5. Deploy. 이후 `main` 푸시마다 Vercel 이 자동 재배포한다. 백엔드 CORS 는 기본으로 `*.vercel.app` 을 허용하며, 커스텀 도메인은 서버 `.env` 의 `CORS_ALLOWED_ORIGINS` 에 추가한다.

### 9-2. 백엔드 서버 주소가 바뀔 때 (서버 이전 시 필수)
`frontend/vercel.json` 의 rewrites 가 백엔드 주소를 직접 가리킨다.
```json
{ "source": "/api/:path*", "destination": "http://<새 서버 공인IP>:8080/api/:path*" }
```
1. 새 서버 `/api/health` 가 외부에서 응답하는지 먼저 확인한다(`curl http://<새IP>:8080/api/health`).
2. `frontend/vercel.json` 의 `destination` 의 IP 를 새 IP 로 바꾸고 커밋·푸시한다. 이 파일은 프론트 배포(Vercel)만 일으킨다.
3. Vercel 배포가 끝나면 화면에서 데이터가 나오는지, 브라우저 개발자 도구 Network 탭에서 `/api/...` 가 200 인지 확인한다.

> `vercel.json` 의 프록시 주소는 새 서버(211.188.54.232)로 바꿔 두었다. main 에 반영되어 Vercel 이 다시 배포해야 실제로 적용된다.

---

## 10. 참고 — 로컬에서 배포 구성 검증
```bash
cp .env.example .env   # Neon 연결정보 + API 키 채우기
docker compose up -d --build
curl http://localhost:8080/api/health
```
로컬 개발만 할 때는 DB 없이 H2 로 도는 `./gradlew bootRun` 이 더 간단하다. (Docker 이미지 빌드는 이 문서 작성 시점에 실행해 검증하지 못했다.)

---

## 다음 개선
- **백엔드 HTTPS 적용**: 지금 Vercel → 백엔드 구간은 평문 HTTP(`:8080`)다. 도메인을 붙이고 Caddy 등 리버스 프록시로 TLS 를 종단한 뒤 80/443 만 열고 8080 은 외부에서 닫는다(그러면 Docker 의 ufw 우회 문제도 `127.0.0.1:8080` 바인딩으로 해결된다).
- **JAR 빌드를 CI 로 이전(완료)**: 서버의 Gradle 빌드를 없애고 GitHub Actions 러너에서 JAR 을 빌드해 서버로 올리도록 바꿨다(4장). 서버는 JRE 이미지에 JAR 만 넣어 실행한다.
- **이미지를 레지스트리에 올려 서버에서 pull**: 지금은 서버가 JAR 로 이미지를 만든다(JRE 베이스 이미지 pull + 작은 빌드). 이미지 자체를 CI 에서 빌드해 레지스트리에 올리고 서버는 pull 만 하게 하면 서버의 빌드 단계가 사라지고, 이미지 태그로 되돌리기도 더 쉬워진다.
- **수집 실패 알림**: `lastAttemptOk=false` 를 주기적으로 확인해 메일·메신저로 알리는 외부 감시(예: 정기 호출 후 실패 시 알림)를 둔다.
