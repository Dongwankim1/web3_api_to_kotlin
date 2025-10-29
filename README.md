# 🚀 Safe Road Club — Solana Kotlin Backend Integration

**Web3.js → Kotlin(Solanaj)** 포팅을 통해 **서버 사이드(Spring Boot)** 에서  
Solana 트랜잭션 생성 · 서명 · 전송까지 수행하는 백엔드 모듈입니다.  
SPL 토큰 전송, Associated Token Account(ATA) 자동 생성, 트랜잭션 재확인/재시도,  
환경별(TokenProgram/Token2022) 분기 기능을 포함합니다.

---

## ⚡ 핵심 요약

- ✅ 프론트 의존(Web3 클라이언트) 없이 **백엔드 단독**으로 안정적인 온체인 처리
- 🌿 dev/prod 환경별 프로그램 전환: `TokenProgram(prod)`, `Token2022(dev)`
- 🔁 재시도 + 최종 확정(`finalized`) 확인으로 트랜잭션 신뢰성 강화
- 💰 ATA 자동 생성 및 SPL/SOL 잔액 조회 지원

---

## 📦 모듈 구성

app.saferoadclub.domain.solana/
├─ SolanaTransferService.kt
└─ TOKENPROGRAM2022.java // Token-2022 전용 Program wrapper

markdown
코드 복사

### 🧩 SolanaTransferService

| 메서드 | 설명 |
|--------|------|
| `getSolanaBalance(publicKey: String): BigDecimal` | SOL 잔액 조회 |
| `getSplBalance(userPublicKey: String): BigDecimal` | SPL 잔액 조회 |
| `transferSolana(recipientPublicKey: String, amount: BigDecimal): TransferTokenResponse` | SOL 전송 |
| `transferSpl(recipientPublicKey: String, amount: BigDecimal): TransferTokenResponse` | SPL 전송 |
| `getOrCreateAssociatedTokenAccount(mint, owner, ...): Pair<PublicKey, BigDecimal>` | ATA 생성/조회 |
| (내부) `sendAndConfirmTransactionWithRetry(...)`, `waitForTransactionConfirmation(...)` | 트랜잭션 재시도 / 확정 |

### 🧱 TOKENPROGRAM2022

Token-2022 프로그램용 Instruction 생성 유틸  
(예: `transferChecked`, `mintTo`, `burn` 등)

---

## 🔧 의존성

- Kotlin, Spring Boot
- [solanaj](https://github.com/skynetcapital/solanaj) (Solana Java/Kotlin SDK)
- bitcoinj (Base58), BouncyCastle (Ed25519), web3j (Mnemonic)
- PostgreSQL/JPA (환경설정 및 시스템 구성용)

```kotlin
dependencies {
    implementation("org.p2p:solanaj:<version>")
    implementation("org.bitcoinj:bitcoinj-core:<version>")
    implementation("org.bouncycastle:bcprov-jdk15on:<version>")
    implementation("org.web3j:core:<version>")
}
🔐 환경 변수 / 시스템 설정
서비스는 SystemConfigService를 통해 다음 키를 읽습니다.

키	설명
WEB3_SOLANA_RPC_URL	Solana RPC 엔드포인트
WEB3_SOLANA_WALLET_PRIVATE_KEY	송신자(Payer/Owner) 비밀키(Base58)
WEB3_SOLANA_TOKEN_SRC_ADDRESS	SPL 토큰 Mint 주소
WEB3_SOLANA_MINT_COLLECTION_ADDRESS	(옵션) 컬렉션 주소
WEB3_SOLANA_MINT_CANDYMACHINE_ADDRESS	(옵션) 캔디머신 주소
WEB3_SOLANA_MINT_CANDYGUARD_ADDRESS	(옵션) 캔디가드 주소

⚠️ 보안 주의

프라이빗 키는 절대 코드/레포에 커밋하지 말고, 시크릿 매니저/환경변수로 관리하세요.

RPC는 신뢰 가능한 엔드포인트(슬롯 안정성, 레이트 한도 등)를 사용하세요.

🌳 환경 분기 (Token Program 선택)
kotlin
코드 복사
private fun getTokenProgram(): PublicKey {
    return if (environmentUtil.isProd()) {
        PublicKey("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")   // Token Program
    } else {
        PublicKey("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")   // Token-2022 Program
    }
}
prod: TokenProgram + decimals = 8 (예시)

dev: Token-2022 + decimals = 9 (예시)

필요 시 프로젝트 정책에 맞게 decimals / RPC / lamports 변환을 조정하세요.

🧪 사용 예시
1️⃣ SOL 잔액 조회
kotlin
코드 복사
val service = SolanaTransferService(systemConfigService, environmentUtil)
val sol = service.getSolanaBalance("PUBLIC_KEY_BASE58")
println("SOL balance = $sol")
2️⃣ SPL 잔액 조회
kotlin
코드 복사
val spl = service.getSplBalance("USER_PUBLIC_KEY_BASE58")
println("SPL balance = $spl")
3️⃣ SOL 전송
kotlin
코드 복사
val result = service.transferSolana(
    recipientPublicKey = "RECIPIENT_PUBLIC_KEY_BASE58",
    amount = BigDecimal("0.01")
)
println("tx = ${result.tx}")
4️⃣ SPL 전송 (ATA 자동 생성 포함)
kotlin
코드 복사
val result = service.transferSpl(
    recipientPublicKey = "RECIPIENT_PUBLIC_KEY_BASE58",
    amount = BigDecimal("5")   // 토큰 단위 (lamports 아님)
)
println("tx = ${result.tx}")
💡 transferSpl()은 내부적으로 발신자/수신자 ATA를 확인하고,
없으면 idempotent하게 생성합니다.
금액 단위는 토큰 단위이며, 내부에서 환경에 맞는 lamports 변환(decimals)을 수행합니다.

🔁 트랜잭션 재시도 & 최종 확정 확인
kotlin
코드 복사
val sig = sendAndConfirmTransactionWithRetry(
    transaction = tx,
    senderKeypair = payer,
    maxRetries = 10
)
getSignatureStatuses로 finalized 상태를 확인할 때까지 polling

네트워크 지연 / 슬롯 재구성 상황에서 신뢰성 보강

timeoutMillis, intervalMillis는 운영 환경에 맞게 조정 권장

🧮 단위 / 정밀도(Decimals) & Lamports 변환
LAMPORTS_PER_SOL = 1_000_000_000

토큰마다 decimals가 다릅니다.
본 모듈은 환경에 따라 8/9 예시로 동작합니다.
실제 Mint의 Decimals와 일치하도록 확인/변경하세요.

PROD_LAMPORTS_PER_SOL = 100_000_000 등 비표준 상수는
프로젝트 정책/토큰 스펙에 맞게 관리하세요.

🧱 예외 / 에러 처리 가이드
상황	예외
잔액 부족	IllegalArgumentException("Insufficient balance")
음수 금액	IllegalArgumentException("Negative numbers are not allowed.")
RPC 실패	RpcException 캐치 후 IllegalStateException 래핑
전송 미확정	sendAndConfirmTransactionWithRetry에서 IllegalStateException 발생

운영 환경에서는 CloudWatch / Discord 등의 모니터링 시스템과 연결해
에러 및 상태 로깅을 강화하는 것을 권장합니다.

🧰 테스트 / 검증 팁
Devnet RPC로 기능 검증 후 Mainnet 반영

소액 전송 → ATA 자동 생성 → 재시도 / 확정 확인 순으로 시나리오 테스트

Mint decimals 실제 값과 코드 상수 일치 여부 확인

RPC Rate Limit 고려하여 재시도 및 타임아웃 조정

🧭 프로젝트 맥락에의 통합
Safe Road Club (SRC) 프로젝트 내에서 본 모듈은 다음 목표를 가집니다:

Web3.js 기반 프론트 트랜잭션을 백엔드 서버로 이관 (보안/안정성/운영 효율 향상)

토큰 거래 무결성(비관적 락) + CI/CD 자동배포 (ECS/Jenkins) +
모니터링(CloudWatch/Discord) 환경과 결합

P2E / 데이터 수익화 워크플로우에 자연스럽게 연결되는 온체인 인프라 레이어 역할 수행

⚠️ 보안 체크리스트
 프라이빗 키 / 민감 설정값은 시크릿 매니저 / 환경변수로 관리

 RPC 엔드포인트 접근 제어 및 Rate Limit 파악

 트랜잭션 / 서명 / 에러 로그에 민감정보(키·시드) 노출 금지

 운영 전 Devnet 리허설 필수

📄 라이선스
사내 / 프로젝트 정책에 맞추어 지정하세요.
(예: Private / Apache-2.0 / MIT 등)

🙌 기여
버그 및 개선 제안은 Issue 탭을 통해 등록해주세요.

향후 계획:

Token-2022 기능 확장 (메타데이터 / 확장 자산 등)

NFT 민팅 (Candy Machine / Candy Guard) 연동

