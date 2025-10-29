package app.saferoadclub.domain.solana

import org.bitcoinj.core.Base58
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.example.TOKENPROGRAM2022
import org.p2p.solanaj.core.Account
import org.p2p.solanaj.core.PublicKey
import org.p2p.solanaj.core.Transaction
import org.p2p.solanaj.core.TransactionInstruction
import org.p2p.solanaj.programs.AssociatedTokenProgram
import org.p2p.solanaj.programs.SystemProgram
import org.p2p.solanaj.programs.TokenProgram
import org.p2p.solanaj.rpc.RpcClient
import org.p2p.solanaj.rpc.RpcException
import org.web3j.crypto.MnemonicUtils
import java.math.BigDecimal

/**
 * Standalone SolanaTransferService (한글 주석 버전)
 * - Spring, SystemConfigService, EnvironmentUtil 의존성 제거
 * - 아래 상수만 설정하면 바로 사용 가능
 */
class SolanaTransferService {

    // =====================[ 여기를 프로젝트에 맞게 설정하세요 ]=====================

    /** Solana RPC 엔드포인트 (Devnet 예시) */
    private val RPC_ENDPOINT: String = "https://api.devnet.solana.com"

    /**
     * Base58 인코딩된 지갑 비밀키(64바이트 키페어)
     *  - 절대 레포에 커밋하지 마세요. 운영에서는 환경변수/시크릿 매니저 사용 권장
     */
    private val WALLET_SECRET_BASE58: String =
        "3s1pLeBAsE58SEcREtKeYPUT_yours_here_DO_NOT_COMMIT"

    /** 작업할 SPL 토큰의 Mint 주소 (예시) */
    private val SRC_TOKEN_MINT: String = "E3iTukHHrabJ1f3mW8rKRZV6Y4PKMzoLD1HmN8gNGpgt"

    /** 환경 플래그: true=운영(TokenProgram), false=개발(Token-2022) */
    private val IS_PROD: Boolean = false

    /** 토큰 Decimals (예시: 운영 8, 개발 9) → 실제 Mint의 Decimals에 맞춰 수정 */
    private val PROD_TOKEN_DECIMALS: Int = 8
    private val DEV_TOKEN_DECIMALS: Int = 9

    // ============================================================================

    /** 표준 SPL Token Program ID */
    private val TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"

    /** Token-2022 Program ID */
    private val TOKEN_2022_PROGRAM_ID = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"

    /** 설정값 Getter */
    private fun getWalletPrivateKeyBase58(): String = WALLET_SECRET_BASE58
    private fun getSrcTokenAddress(): String = SRC_TOKEN_MINT
    private fun getRpcEndpoint(): String = RPC_ENDPOINT

    /** 환경에 따라 사용할 프로그램 선택 */
    private fun getTokenProgram(): PublicKey {
        return if (IS_PROD) PublicKey(TOKEN_PROGRAM_ID) else PublicKey(TOKEN_2022_PROGRAM_ID)
    }

    /** 환경에 따라 사용할 Decimals 선택 */
    private fun getTokenDecimals(): Int {
        return if (IS_PROD) PROD_TOKEN_DECIMALS else DEV_TOKEN_DECIMALS
    }

    /**
     * 계정의 SOL 잔액 조회
     * @param publicKey 공개키(Base58)
     * @return SOL 단위의 잔액 (BigDecimal)
     */
    fun getSolanaBalance(publicKey: String): BigDecimal {
        return try {
            val lamports = getConnection().api.getBalance(PublicKey(publicKey))
            BigDecimal(lamports).divide(BigDecimal(LAMPORTS_PER_SOL))
        } catch (e: Exception) {
            println("SOL 잔액 조회 실패: ${e.message}")
            BigDecimal.ZERO
        }
    }

    /**
     * 사용자 지갑의 SPL 토큰 잔액 조회
     * - 필요한 경우 ATA를 조회/생성
     * @param userPublicKey 사용자 공개키(Base58)
     * @return 토큰 단위의 잔액 (Decimals 반영됨)
     */
    fun getSplBalance(userPublicKey: String): BigDecimal {
        val mintAddress = PublicKey(getSrcTokenAddress())
        return try {
            val tokenAccount = getOrCreateAssociatedTokenAccount(
                mint = mintAddress,
                owner = PublicKey(userPublicKey),
                programId = getTokenProgram()
            )
            // on-chain 금액은 소수점 없는 정수(최소 단위) → Decimals로 나눠 사람이 읽기 좋은 단위로 변환
            val decimals = getTokenDecimals()
            val divisor = BigDecimal.TEN.pow(decimals)
            tokenAccount.second.divide(divisor)
        } catch (e: Exception) {
            println("SPL 잔액 조회 실패: ${e.message}")
            BigDecimal.ZERO
        }
    }

    /**
     * SPL 토큰 전송 (필요 시 수신자 ATA 자동 생성)
     * @param recipientPublicKey 수신자 공개키(Base58)
     * @param amount 전송 수량 (토큰 단위)
     */
    fun transferSpl(recipientPublicKey: String, amount: BigDecimal): TransferTokenResponse {
        require(amount >= BigDecimal.ZERO) { "음수 전송은 허용되지 않습니다." }

        // 송신자 계정
        val sender = getAccountBySecretKey(getWalletPrivateKeyBase58())

        // 잔액 확인(토큰 단위)
        val senderBalance = getSplBalance(sender.publicKeyBase58)
        if (senderBalance < amount) throw IllegalArgumentException("토큰 잔액 부족")

        // ATA 확보 (없으면 생성)
        val mintAddress = PublicKey(getSrcTokenAddress())
        val senderAta = getOrCreateAssociatedTokenAccount(mintAddress, sender.publicKey, programId = getTokenProgram())
        val recipientAta = getOrCreateAssociatedTokenAccount(mintAddress, PublicKey(recipientPublicKey), programId = getTokenProgram())

        // 전송 금액을 on-chain 최소 단위(정수)로 변환
        val decimals = getTokenDecimals()
        val rawAmount = amount.multiply(BigDecimal.TEN.pow(decimals)).toLong()

        // 환경별 프로그램으로 transferChecked 인스트럭션 생성
        val tx = Transaction()
        val ix: TransactionInstruction = if (IS_PROD) {
            TokenProgram.transferChecked(
                senderAta.first,
                recipientAta.first,
                rawAmount,
                decimals.toByte(),
                sender.publicKey,
                mintAddress
            )
        } else {
            TOKENPROGRAM2022.transferChecked(
                senderAta.first,
                recipientAta.first,
                rawAmount,
                decimals.toByte(),
                sender.publicKey,
                mintAddress
            )
        }
        tx.addInstruction(ix)

        // 전송 + 재시도/확정 확인
        val sig = try {
            sendAndConfirmTransactionWithRetry(tx, sender, maxRetries = 10)
        } catch (e: RpcException) {
            throw IllegalStateException("SPL 전송 실패: ${e.message}", e)
        }

        println("SPL 전송 완료: $sig")
        return TransferTokenResponse(sig)
    }

    /**
     * SOL 전송
     * @param recipientPublicKey 수신자 공개키(Base58)
     * @param amount 전송 수량 (SOL 단위)
     */
    fun transferSolana(recipientPublicKey: String, amount: BigDecimal): TransferTokenResponse {
        require(amount >= BigDecimal.ZERO) { "음수 전송은 허용되지 않습니다." }

        val sender = getAccountBySecretKey(getWalletPrivateKeyBase58())
        val balance = getSolanaBalance(sender.publicKeyBase58)
        if (balance < amount) throw IllegalArgumentException("SOL 잔액 부족")

        // SOL → Lamports 변환 후 전송
        val transferIx = SystemProgram.transfer(
            sender.publicKey,
            PublicKey(recipientPublicKey),
            amount.multiply(BigDecimal(LAMPORTS_PER_SOL)).toLong()
        )
        val tx = Transaction().apply { addInstruction(transferIx) }

        val sig = try {
            getConnection().api.sendTransaction(tx, sender)
        } catch (e: RpcException) {
            throw IllegalStateException("SOL 전송 실패: ${e.message}", e)
        }

        println("SOL 전송 완료: $sig")
        return TransferTokenResponse(sig)
    }

    /**
     * ATA 조회/생성
     * @return Pair(ATA 주소, 잔액(최소 단위 정수))
     */
    fun getOrCreateAssociatedTokenAccount(
        mint: PublicKey,
        owner: PublicKey,
        allowOwnerOffCurve: Boolean = false,
        commitment: String = "confirmed",
        programId: PublicKey
    ): Pair<PublicKey, BigDecimal> {
        val connection = getConnection()

        // ATA 주소 계산 (owner + tokenProgram + mint)
        val associatedTokenAddress = PublicKey.findProgramAddress(
            listOf(
                owner.toByteArray(),
                getTokenProgram().toByteArray(),
                mint.toByteArray()
            ),
            AssociatedTokenProgram.PROGRAM_ID
        ).address

        // 존재하면 잔액 반환
        val accountInfo = connection.api.getSplTokenAccountInfo(associatedTokenAddress)
        if (accountInfo.value != null) {
            val tokenBalance = connection.api.getTokenAccountBalance(associatedTokenAddress)
            return Pair(associatedTokenAddress, BigDecimal(tokenBalance.amount))
        }

        // 없으면 idempotent 생성
        val payer = getAccountBySecretKey(getWalletPrivateKeyBase58())
        val tx = Transaction().apply {
            addInstruction(AssociatedTokenProgram.createIdempotent(payer.publicKey, owner, mint))
        }
        try {
            connection.api.sendTransaction(tx, payer)
        } catch (e: RpcException) {
            // 이미 존재하는 경우 등은 실패 로그만 남기고 계속 진행
            println("ATA 생성 실패(이미 존재 가능): ${e.message}")
        }

        return Pair(associatedTokenAddress, BigDecimal.ZERO)
    }

    // =====================[ 계정/유틸 ]=====================

    /** Base58 비밀키로 Account 생성 */
    private fun getAccountBySecretKey(secretKeyBase58: String): Account {
        val sk = Base58.decode(secretKeyBase58)
        return Account(sk)
    }

    /** (옵션) 니모닉으로 Account 생성 */
    @Suppress("unused")
    private fun getSolanaAccountFromMnemonic(mnemonic: String, passphrase: String = ""): Account {
        val seed = MnemonicUtils.generateSeed(mnemonic, passphrase)
        val privateKey = seed.copyOf(32)
        val privateKeyParams = Ed25519PrivateKeyParameters(privateKey, 0)
        val publicKey = privateKeyParams.generatePublicKey().encoded
        return Account(privateKey + publicKey)
    }

    // =====================[ 신뢰성: 재시도/확정 ]=====================

    /**
     * 트랜잭션 전송 후 finalized 확정까지 재시도
     */
    fun sendAndConfirmTransactionWithRetry(
        transaction: Transaction,
        senderKeypair: Account,
        maxRetries: Int = 3
    ): String {
        val connection = getConnection()
        var attempt = 0
        var signature: String? = null

        while (attempt < maxRetries) {
            try {
                signature = connection.api.sendTransaction(transaction, senderKeypair)
                println("전송 시도 ${attempt + 1}회: $signature")
            } catch (e: RpcException) {
                println("전송 실패 ${attempt + 1}회: ${e.message}")
            }

            if (signature != null && waitForTransactionConfirmation(signature)) {
                println("finalized 확정 (시도 ${attempt + 1}회)")
                return signature
            }

            attempt++
            Thread.sleep(1000) // 재시도 대기
        }

        throw IllegalStateException("모든 재시도 후에도 finalized 되지 않았습니다. (maxRetries=$maxRetries)")
    }

    /**
     * 시그니처가 finalized 될 때까지 폴링
     */
    private fun waitForTransactionConfirmation(
        signature: String,
        timeoutMillis: Long = 8000,
        intervalMillis: Long = 500
    ): Boolean {
        val connection = getConnection()
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMillis) {
            val statuses = connection.api.getSignatureStatuses(listOf(signature), false)
            if (statuses.value.isNotEmpty() &&
                statuses.value[0]?.confirmationStatus == "finalized"
            ) {
                return true
            }
            Thread.sleep(intervalMillis)
        }
        return false
    }

    /** RPC 연결 */
    private fun getConnection(): RpcClient = RpcClient(getRpcEndpoint())

    companion object {
        /** 1 SOL = 1_000_000_000 lamports */
        private const val LAMPORTS_PER_SOL = 1_000_000_000L
    }

    /** 트랜잭션 결과 */
    data class TransferTokenResponse(val tx: String)
}
