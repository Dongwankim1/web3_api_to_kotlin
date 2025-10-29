Safe Road Club — Solana Kotlin Backend Integration

Server-side (Spring Boot) Solana transaction module ported from Web3.js to Kotlin (Solanaj).
It handles SPL token transfers, Associated Token Account (ATA) creation, balance queries, environment-aware Token Program switching, and retry + finalization checks for reliability.

Why this exists

Run on-chain operations from the backend (create/sign/send) without a web client.

Auto-switch between TokenProgram (prod) and Token-2022 (dev).

Improve reliability via retry + finalized status polling.

Auto-create ATAs and provide SOL/SPL balance helpers.

📦 Module Structure
app.saferoadclub.domain.solana/
├─ SolanaTransferService.kt
└─ TOKENPROGRAM2022.java   // Token-2022 Program wrapper


SolanaTransferService

getSolanaBalance(publicKey: String): BigDecimal

getSplBalance(userPublicKey: String): BigDecimal

transferSolana(recipientPublicKey: String, amount: BigDecimal): TransferTokenResponse

transferSpl(recipientPublicKey: String, amount: BigDecimal): TransferTokenResponse

getOrCreateAssociatedTokenAccount(mint, owner, ...): Pair<PublicKey, BigDecimal>

Internal: sendAndConfirmTransactionWithRetry(...), waitForTransactionConfirmation(...)

TOKENPROGRAM2022

Utility for Token-2022 instructions (transferChecked, mintTo, burn, …).

🔧 Dependencies

Kotlin, Spring Boot

solanaj
(Solana Java/Kotlin SDK)

bitcoinj (Base58), BouncyCastle (Ed25519), web3j (Mnemonic)

PostgreSQL/JPA etc. (for config persistence in the broader project)

build.gradle.kts (snippet):

dependencies {
implementation("org.p2p:solanaj:<version>")
implementation("org.bitcoinj:bitcoinj-core:<version>")
implementation("org.bouncycastle:bcprov-jdk15on:<version>")
implementation("org.web3j:core:<version>")
}

🔐 Configuration / System Settings

Values are read via SystemConfigService.

Key	Description
WEB3_SOLANA_RPC_URL	Solana RPC endpoint
WEB3_SOLANA_WALLET_PRIVATE_KEY	Sender (payer/owner) Base58 private key
WEB3_SOLANA_TOKEN_SRC_ADDRESS	SPL token mint address
WEB3_SOLANA_MINT_COLLECTION_ADDRESS	(Optional) Collection address
WEB3_SOLANA_MINT_CANDYMACHINE_ADDRESS	(Optional) Candy Machine address
WEB3_SOLANA_MINT_CANDYGUARD_ADDRESS	(Optional) Candy Guard address

Security

Never commit private keys. Use secret managers / env vars.

Use a reliable RPC (slots/latency/rate limits).

🌳 Environment Switching (Token Program)
private fun getTokenProgram(): PublicKey {
return if (environmentUtil.isProd()) {
PublicKey("TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA")   // Token Program (SPL)
} else {
PublicKey("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")   // Token-2022
}
}


prod: TokenProgram (example decimals=8)

dev: Token-2022 (example decimals=9)

Adjust decimals/RPC/lamports conversion based on your actual mint.

🧪 Usage Examples
1) SOL Balance
   val service = SolanaTransferService(systemConfigService, environmentUtil)
   val sol = service.getSolanaBalance("PUBLIC_KEY_BASE58")
   println("SOL balance = $sol")

2) SPL Balance
   val spl = service.getSplBalance("USER_PUBLIC_KEY_BASE58")
   println("SPL balance = $spl")

3) Transfer SOL
   val result = service.transferSolana(
   recipientPublicKey = "RECIPIENT_PUBLIC_KEY_BASE58",
   amount = BigDecimal("0.01")
   )
   println("tx = ${result.tx}")

4) Transfer SPL (auto-creates ATA)
   val result = service.transferSpl(
   recipientPublicKey = "RECIPIENT_PUBLIC_KEY_BASE58",
   amount = BigDecimal("5")   // token units (not lamports)
   )
   println("tx = ${result.tx}")


Notes

transferSpl checks sender/recipient ATA and creates them idempotently if missing.

Amounts are in token units; the service applies environment-specific lamports conversion (decimals).

🔁 Retry & Finalization
val sig = sendAndConfirmTransactionWithRetry(
transaction = tx,
senderKeypair = payer,
maxRetries = 10
)


Polls getSignatureStatuses until finalized.

Adds resilience against network delays / slot reorgs.

Tune timeoutMillis / intervalMillis for production.

🧮 Decimals & Lamports

LAMPORTS_PER_SOL = 1_000_000_000

Token decimals vary by mint. The code uses 8/9 as examples—match your mint.

Non-standard constants (e.g., PROD_LAMPORTS_PER_SOL = 100_000_000) should reflect your token policy/spec.

🧱 Error Handling

Insufficient balance: IllegalArgumentException("Insufficient balance")

Negative amount: IllegalArgumentException("Negative numbers are not allowed.")

RPC errors: catch RpcException, rethrow IllegalStateException

Not finalized after retries: IllegalStateException from sendAndConfirmTransactionWithRetry

For production, wire logs/alerts (e.g., CloudWatch/Discord).

🧰 Testing Tips

Validate on Devnet first, then Mainnet.

E2E flow: small transfer → ATA creation → retry/finalization.

Verify mint decimals matches service constants.

Respect RPC rate limits; tune timeouts and retry intervals.

🧭 Project Context

This module is part of Safe Road Club (SRC):

Move transaction logic from the web client to the backend for security & operability.

Combine with pessimistic locking (double-spend guard), CI/CD (ECS/Jenkins), and monitoring (CloudWatch/Discord).

Serves as the on-chain infrastructure layer for P2E / data monetization workflows.

⚠️ Security Checklist

Manage secrets via secret manager/env vars.

RPC access control & rate limits understood.

No sensitive data (keys/seeds) in logs.

Devnet rehearsal before production.

📄 License

Choose per your org policy (e.g., Private / Apache-2.0 / MIT).

🙌 Contributing

Please open issues for bugs/feature requests.

Roadmap ideas: Token-2022 extensions (metadata/programmable assets), NFT minting (Candy Machine/Guard) integration.