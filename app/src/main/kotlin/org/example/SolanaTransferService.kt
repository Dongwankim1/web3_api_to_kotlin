package app.saferoadclub.domain.solana

import app.saferoadclub.domain.common.CodeEnum
import app.saferoadclub.domain.systemConfig.service.SystemConfigService
import app.saferoadclub.util.EnvironmentUtil
import app.saferoadclub.util.LogUtil.logger
import com.fasterxml.jackson.annotation.JsonAlias
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
import org.springframework.stereotype.Service
import org.web3j.crypto.MnemonicUtils
import java.math.BigDecimal

/**
 *packageName    : app.saferoadclub.domain.solana
 * fileName       : SolanaService
 * author         : mac
 * date           : 2/10/25
 * description    :
 * ===========================================================
 * DATE              AUTHOR             NOTE
 * -----------------------------------------------------------
 * 2/10/25        mac       최초 생성
 */

class SolanaTransferService(
    private val systemConfigService: SystemConfigService,
    private val environmentUtil: EnvironmentUtil
) {
    private final val TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    private final val TOKEN_2022_PROGRAM_ID = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"

    private fun getWalletPrivateKey(): String {
        return systemConfigService.getValue(CodeEnum.SystemConfigKey.WEB3_SOLANA_WALLET_PRIVATE_KEY)
    }

    private fun getSrcTokenAddress(): String {
        return systemConfigService.getValue(CodeEnum.SystemConfigKey.WEB3_SOLANA_TOKEN_SRC_ADDRESS)
    }

    private fun getMintCollection(): String {
        return systemConfigService.getValue(CodeEnum.SystemConfigKey.WEB3_SOLANA_MINT_COLLECTION_ADDRESS)
    }

    private fun getMintCandyMachine(): String {
        return systemConfigService.getValue(CodeEnum.SystemConfigKey.WEB3_SOLANA_MINT_CANDYMACHINE_ADDRESS)
    }

    private fun getMintCandyGuard(): String {
        return systemConfigService.getValue(CodeEnum.SystemConfigKey.WEB3_SOLANA_MINT_CANDYGUARD_ADDRESS)
    }

    private fun getRpcEndpoint(): String {
        return systemConfigService.getValue(CodeEnum.SystemConfigKey.WEB3_SOLANA_RPC_URL)
    }

    private fun getTokenProgram(): PublicKey {
        return if(environmentUtil.isProd()){
            PublicKey(TOKEN_PROGRAM_ID)
        }else{
            PublicKey(TOKEN_2022_PROGRAM_ID)
        }
    }

    /**
     * 솔라나 계정 금액을 가져온다.
     * @param publicKey String 공개키
     * @return BigIn
     */
    fun getSolanaBalance(publicKey: String): BigDecimal {
        println("getBalance")

        return try {
            val lamports = getConnection().api.getBalance(PublicKey(publicKey))
            BigDecimal(lamports).divide(BigDecimal(LAMPORTS_PER_SOL))
        } catch (e: Exception) {
            println("Error fetching balance: ${e.message}")
            BigDecimal.valueOf(0L)
        }
    }
    /**
     * 현재 토큰 밸런스 가져오기
     * @param userPublicKey String - 조회할 사용자 지갑주소
     * @return Double
     */
    fun getSplBalance(userPublicKey: String): BigDecimal {
        val secretKey = getWalletPrivateKey() // 민팅 owner 시크릿키 (payer)
        val mint = getSrcTokenAddress() // mint String - 민팅 주소

        val mintAddress = PublicKey(mint)


        // 3. 공개 키 생성
        // val generateSolanaAccount = getSolanaAccount(secretKey, "")

        // Select the correct program ID and lamports conversion based on the environment
        return try {
            // Find or create the associated token account
            val tokenAccount = getOrCreateAssociatedTokenAccount(
                mintAddress,
                PublicKey(userPublicKey),
                false,
                "comfirmd",
                getTokenProgram()
            )


            val amount = if(tokenAccount.second >= BigDecimal.valueOf(0L)){
                if (environmentUtil.isProd()){
                    tokenAccount.second.divide(BigDecimal.valueOf(PROD_LAMPORTS_PER_SOL));
                }else{
                    tokenAccount.second.divide(BigDecimal.valueOf(LAMPORTS_PER_SOL));
                }
            } else {
                BigDecimal.valueOf(0L)
            }
            return amount
        } catch (e: Exception) {
            return BigDecimal.valueOf(0L)
        }
    }

    /**
     * sol 토큰 전송
     * dev - Token2022Program으로 생성됨
     * prod - TokenProgram으로 생성됨
     * @param recipientPublicKey String 토큰을 받을 계쩡
     * @param amount BigDecimal 토큰 전송 양
     * @return TransferTokenResponse
     */
    fun transferSpl(recipientPublicKey: String,amount:BigDecimal): TransferTokenResponse {
        if (amount  < BigDecimal.valueOf(0L)) {
            throw IllegalArgumentException("Negative numbers are not allowed.")
        }
        // 공개키 생성
        val senderKeypair = getAccountBySecretKey(getWalletPrivateKey())

        val mint = getSrcTokenAddress()

        // Fetch balance of the SPL Token account
        val balance = try {
            getSplBalance(
                senderKeypair.publicKeyBase58
            )
        } catch (e: RpcException) {
            throw IllegalStateException("Error fetching SPL token balance: ${e.message}", e)
        }
        val connection = getConnection()
        println("Balance: $balance")
        println("Request amount: ${amount}")

        if (balance < amount) {
            throw IllegalArgumentException("Insufficient balance")
        }

        // Create the transaction
        // val transaction = Transaction()
        val mintAddress = PublicKey(mint)
        val senderAccount = getOrCreateAssociatedTokenAccount(
            mintAddress,
            senderKeypair.publicKey,
            false,
            "comfirmd",
            getTokenProgram()
        )

        val recipientAccount = getOrCreateAssociatedTokenAccount(
            mintAddress,
            PublicKey(recipientPublicKey),
            false,
            "comfirmd",
            getTokenProgram()
        )

        val transaction = Transaction()
        var transferInstruction: TransactionInstruction? = null;
        println("senderAccount : "+senderAccount)
        println("recipientAccount : "+recipientAccount)
        println(" amount.multiply : "+amount.multiply(BigDecimal.valueOf(PROD_LAMPORTS_PER_SOL)).toLong())

        if (environmentUtil.isProd()){
             println("prod senderAccount : "+senderAccount.first)
             println("prod recipientAccount : "+recipientAccount.first)
            
            transferInstruction = TokenProgram.transferChecked(
                senderAccount.first,
                recipientAccount.first,
                amount.multiply(BigDecimal.valueOf(PROD_LAMPORTS_PER_SOL)).toLong(),
                8,
                senderKeypair.publicKey,
                mintAddress
            )
            println("prod transferInstruction : "+transferInstruction)
        }else{
            transferInstruction = TOKENPROGRAM2022.transferChecked(
                senderAccount.first,
                recipientAccount.first,
                amount.multiply(BigDecimal.valueOf(LAMPORTS_PER_SOL)).toLong(),
                9,
                senderKeypair.publicKey,
                mintAddress
            )
        }

        transaction.addInstruction(transferInstruction)
        
        // // Sign and send the transaction
        val signature: String = try {
            sendAndConfirmTransactionWithRetry(transaction, senderKeypair,10)
            //getConnection().api.sendTransaction(transaction, senderKeypair)
        } catch (e: RpcException) {
            throw IllegalStateException("Transaction failed: ${e.message}", e)
        }

        println("Transaction Signature: $signature")
        val result = TransferTokenResponse(signature)
        return result
    }

    /**
     * 솔라나를 전송한다.
     * @param recipient String 받는 사람 퍼블릭키 주소
     * @param amount BigInteger 보내는 양
     */
    fun transferSolana(recipientPublicKey: String,
                       amount: BigDecimal) :TransferTokenResponse{
        if (amount  < BigDecimal.valueOf(0L)) {
            throw IllegalArgumentException("Negative numbers are not allowed.")
        }

        // 공개키 생성
        val senderAccount = getAccountBySecretKey(getWalletPrivateKey())
        // 솔라나 계정 금액 가져오기
        val balance = getSolanaBalance(senderAccount.publicKeyBase58)

        if (balance < amount) {
            throw IllegalArgumentException("Insufficient balance")
        }

        val recipientPublicKey = PublicKey(recipientPublicKey)

        val transferInstruction = SystemProgram.transfer(
            senderAccount.getPublicKey(),
            recipientPublicKey,
            calculateAmountByPerSol(amount).toLong()
        )
        val transaction = Transaction()
        transaction.addInstruction(transferInstruction)

        // Sign and send the transaction
        val signature: String = try {
            getConnection().api.sendTransaction(transaction, senderAccount)
        } catch (e: RpcException) {
            throw IllegalStateException("Transaction failed: ${e.message}", e)
        }

        println("Transaction Signature: $signature")
        val result = TransferTokenResponse(signature)
        return result
    }


    /**
     * 토큰 지갑정보를 가져오고, 만약에 존재하지 않으면 토큰 주소를 생성한다.
     * @param payer Account - 지불 계정
     * @param mint PublicKey - 민트 주소
     * @param owner PublicKey - 토큰 오너 주소
     * @param allowOwnerOffCurve Boolean
     * @param commitment String
     * @param programId PublicKey
     * @return Pair<PublicKey, BigInteger>
     */
    fun getOrCreateAssociatedTokenAccount(
        mint: PublicKey,
        owner: PublicKey,
        allowOwnerOffCurve: Boolean = false,
        commitment: String = "confirmed",
        programId: PublicKey
    ): Pair<PublicKey, BigDecimal> {
        val connection = getConnection()
        // Calculate the associated token account (ATA) address
        val associatedTokenAddress = PublicKey.findProgramAddress(
            listOf(
                owner.toByteArray(),
                getTokenProgram().toByteArray(),
                mint.toByteArray()
            ),
            AssociatedTokenProgram.PROGRAM_ID
        )
        println("associatedTokenAddress : " + associatedTokenAddress.address)
        // Check if the ATA exists
        val accountInfo = connection.api.getSplTokenAccountInfo(associatedTokenAddress.address)
        // val accountInfo = connection.api.getAccountInfo(associatedTokenAddress.address)
        //
        if (accountInfo.value != null) {
            println("accountInfo = " + accountInfo);
            println("accountInfo.value = " + accountInfo.value);
            // Fetch balance if ATA exists
            val tokenBalance = connection.api.getTokenAccountBalance(associatedTokenAddress.address)
            return Pair(associatedTokenAddress.address, BigDecimal(tokenBalance.amount))

        }

        val senderKeypair = getAccountBySecretKey(getWalletPrivateKey())

        // If ATA doesn't exist, create it
        val transaction = Transaction()
        val create = AssociatedTokenProgram.createIdempotent(senderKeypair.publicKey, owner, mint)
        transaction.addInstruction(
            create
        )

        try {
            //val signature: String = sendAndConfirmTransactionWithRetry(transaction,senderKeypair,5)
            val signature: String = connection.api.sendTransaction(transaction, senderKeypair)

        }catch (e: RpcException){
            println(e.message)
        }
        println("create token : " + create)

        return Pair(associatedTokenAddress.address, BigDecimal.ZERO)
    }

    private fun getAccountBySecretKey(secretKey: String): Account {
        val secretKeyBytes: ByteArray = Base58.decode(secretKey)
        val senderKeypair = Account(secretKeyBytes)
        return senderKeypair
    }

    /**
     * mnemonic을 분해하여 계정을 만든다
     * @param mnemonic String 니모닉 주소
     * @param passphrase String 비밀번호
     * @return Account mnemonic에 따른 계정 정보
     */
    private fun getSolanaAccount(mnemonic: String, passphrase: String = ""): Account {

        val seed = MnemonicUtils.generateSeed(mnemonic, "")

        // 2. Ed25519 비밀 키 생성 (상위 32바이트)
        val privateKey = seed.copyOf(32)
        val privateKeyParams = Ed25519PrivateKeyParameters(privateKey, 0)

        // 3. 공개 키 생성
        val publicKey = privateKeyParams.generatePublicKey().encoded

        // 4. Solana Account 생성
        return Account(privateKey + publicKey)
    }

    private fun calculateAmountByPerSol(amount :BigDecimal) : BigDecimal{
        return if(amount.compareTo(BigDecimal.valueOf(0L))<=0){
            BigDecimal.valueOf(0L)
        }else{
            amount.multiply(BigDecimal.valueOf(LAMPORTS_PER_SOL))
        }
    }

/**
 * 트랜잭션 전송 후 최종 확정 여부를 재검증하고, 재시도하는 메서드 예시
 *
 * @param transaction 전송할 트랜잭션 객체
 * @param senderKeypair 전송에 사용할 계정 객체
 * @param maxRetries 최대 재시도 횟수 (기본값: 3)
 * @return 최종 확정된 트랜잭션의 서명
 * @throws IllegalStateException 모든 시도에서 최종 확정되지 않은 경우
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
            // 트랜잭션 전송
            signature = connection.api.sendTransaction(transaction, senderKeypair)
            println("Transaction sent on attempt ${attempt + 1} with signature: $signature")
        } catch (e: RpcException) {
            println("Transaction sending failed on attempt ${attempt + 1}: ${e.message}")
        }

        // 전송한 트랜잭션의 최종 확정 여부 확인
        if (signature != null && waitForTransactionConfirmation(signature)) {
            println("Transaction confirmed as finalized on attempt ${attempt + 1}")
            return signature
        }

        attempt++
        println("Retrying transaction... (attempt ${attempt + 1})")
        Thread.sleep(1000) // 재시도 전 잠깐 대기
    }

    throw IllegalStateException("Transaction not confirmed after $maxRetries attempts.")
}

/**
 * 주어진 트랜잭션 서명이 네트워크에서 최종 확정(finalized) 상태가 될 때까지 대기하는 메서드
 *
 * @param signature 트랜잭션 서명
 * @param timeoutMillis 최대 대기 시간 (기본값: 15000ms)
 * @param intervalMillis 조회 간격 (기본값: 500ms)
 * @return 트랜잭션이 최종 확정되면 true, 그렇지 않으면 false
 */
private fun waitForTransactionConfirmation(
    signature: String,
    timeoutMillis: Long = 8000,
    intervalMillis: Long = 500
): Boolean {
    val connection = getConnection()
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < timeoutMillis) {
        val statuses = connection.api.getSignatureStatuses(listOf(signature),false)
        if (statuses.value.isNotEmpty() && statuses.value[0] != null &&
            statuses.value[0]!!.confirmationStatus == "finalized"
        ) {
            println("Transaction $signature confirmed as finalized.")
            return true
        }
        Thread.sleep(intervalMillis)
    }
    println("Timeout reached. Transaction $signature not finalized.")
    return false
}
    
    private fun getConnection(): RpcClient {
        return RpcClient(getRpcEndpoint())
    }

    companion object {
        private const val LAMPORTS_PER_SOL = 1_000_000_000L // 1 SOL = 1 billion lamports
        private const val PROD_LAMPORTS_PER_SOL = 100_000_000L // 1 SOL = 1 billion lamports
    }

    data class TransferTokenResponse(
        @JsonAlias("tx")
        val tx: String,
    )

}
