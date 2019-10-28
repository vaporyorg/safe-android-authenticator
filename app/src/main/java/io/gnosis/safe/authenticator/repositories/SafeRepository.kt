package io.gnosis.safe.authenticator.repositories

import android.content.Context
import io.gnosis.safe.authenticator.*
import io.gnosis.safe.authenticator.BuildConfig
import io.gnosis.safe.authenticator.R
import io.gnosis.safe.authenticator.data.JsonRpcApi
import io.gnosis.safe.authenticator.data.RelayServiceApi
import io.gnosis.safe.authenticator.data.TransactionServiceApi
import io.gnosis.safe.authenticator.data.TransferLimitServiceApi
import io.gnosis.safe.authenticator.data.models.LimitTransferExecution
import io.gnosis.safe.authenticator.data.models.ServiceTransaction
import io.gnosis.safe.authenticator.data.models.ServiceTransactionRequest
import io.gnosis.safe.authenticator.repositories.SafeRepository.Companion.TRANSFER_LIMIT_MODULE_ADDRESS
import io.gnosis.safe.authenticator.utils.asMiddleEllipsized
import io.gnosis.safe.authenticator.utils.nullOnThrow
import io.gnosis.safe.authenticator.utils.shiftedString
import kotlinx.coroutines.rx2.await
import okio.ByteString
import org.walleth.khex.toHexString
import pm.gnosis.crypto.ECDSASignature
import pm.gnosis.crypto.KeyGenerator
import pm.gnosis.crypto.KeyPair
import pm.gnosis.crypto.utils.Sha3Utils
import pm.gnosis.crypto.utils.asEthereumAddressChecksumString
import pm.gnosis.mnemonic.Bip39
import pm.gnosis.model.Solidity
import pm.gnosis.svalinn.common.PreferencesManager
import pm.gnosis.svalinn.common.utils.edit
import pm.gnosis.svalinn.security.EncryptionManager
import pm.gnosis.utils.*
import java.math.BigInteger
import java.nio.charset.Charset

interface SafeRepository {

    suspend fun loadDeviceId(): Solidity.Address
    suspend fun setSafeAddress(safe: Solidity.Address)
    suspend fun loadSafeAddress(): Solidity.Address
    suspend fun confirmSafeTransaction(
        safe: Solidity.Address, transaction: SafeTx, execInfo: SafeTxExecInfo
    )

    suspend fun loadPendingTransactions(safe: Solidity.Address): List<ServiceSafeTx>

    data class SafeInfo(
        val address: Solidity.Address,
        val masterCopy: Solidity.Address,
        val owners: List<Solidity.Address>,
        val threshold: BigInteger,
        val currentNonce: BigInteger
    )

    data class ServiceSafeTx(
        val hash: String,
        val tx: SafeTx,
        val execInfo: SafeTxExecInfo,
        val confirmations: List<Pair<Solidity.Address, String?>>,
        val executed: Boolean
    )

    data class SafeTx(
        val to: Solidity.Address,
        val value: BigInteger,
        val data: String,
        val operation: Operation
    ) {

        enum class Operation(val id: Int) {
            CALL(0),
            DELEGATE(1)
        }
    }

    data class SafeTxExecInfo(
        val baseGas: BigInteger,
        val txGas: BigInteger,
        val gasPrice: BigInteger,
        val gasToken: Solidity.Address,
        val refundReceiver: Solidity.Address,
        val nonce: BigInteger
    ) {
        val fees by lazy { (baseGas + txGas) * gasPrice }
    }

    data class TokenInfo(
        val symbol: String,
        val decimals: Int,
        val name: String,
        val icon: String?
    )

    data class TransactionInfo(
        val recipient: Solidity.Address,
        val recipientLabel: String,
        val assetIcon: String?,
        val assetLabel: String,
        val additionalInfo: String? = null
    )

    data class Limit(
        val token: Solidity.Address,
        val amount: BigInteger,
        val spent: BigInteger,
        val lastSpent: Long,
        val resetPeriod: Long,
        val nonce: BigInteger
    )

    suspend fun loadSafeNonce(safe: Solidity.Address): BigInteger
    suspend fun loadSafeInfo(safe: Solidity.Address): SafeInfo
    suspend fun loadTokenInfo(token: Solidity.Address): TokenInfo
    suspend fun loadPendingTransaction(txHash: String): ServiceSafeTx
    suspend fun loadTransactionInformation(safe: Solidity.Address, transaction: SafeTx): TransactionInfo
    suspend fun performTransferLimit(safe: Solidity.Address, limit: Limit, to: Solidity.Address, amount: BigInteger)
    suspend fun loadLimits(safe: Solidity.Address): List<Limit>
    suspend fun loadModules(safe: Solidity.Address): List<Solidity.Address>

    companion object {
        val ETH_TOKEN_INFO = TokenInfo("ETH", 18, "Ether", null)
        val TRANSFER_LIMIT_MODULE_ADDRESS = BuildConfig.TRANSFER_LIMIT_MODULE.asEthereumAddress()!!
    }
}

class SafeRepositoryImpl(
    context: Context,
    private val bip39: Bip39,
    private val encryptionManager: EncryptionManager,
    private val jsonRpcApi: JsonRpcApi,
    private val relayServiceApi: RelayServiceApi,
    private val transactionServiceApi: TransactionServiceApi,
    private val transferLimitServiceApi: TransferLimitServiceApi,
    private val preferencesManager: PreferencesManager
) : SafeRepository {

    private val accountPrefs = context.getSharedPreferences(ACC_PREF_NAME, Context.MODE_PRIVATE)

    fun isInitialized(): Boolean =
        accountPrefs.getString(PREF_KEY_APP_MNEMONIC, null) != null

    private suspend fun enforceEncryption() {
        if (!encryptionManager.initialized().await() && !encryptionManager.setupPassword(ENC_PASSWORD.toByteArray()).await())
            throw RuntimeException("Could not setup encryption")
    }

    override suspend fun setSafeAddress(safe: Solidity.Address) {
        preferencesManager.prefs.edit {
            putString(PREF_KEY_SAFE_ADDRESS, safe.asEthereumAddressString())
        }
    }

    override suspend fun loadSafeAddress(): Solidity.Address =
        preferencesManager.prefs.getString(PREF_KEY_SAFE_ADDRESS, null)!!.asEthereumAddress()!!

    override suspend fun loadDeviceId(): Solidity.Address {
        enforceEncryption()
        return getKeyPair(0).address.toAddress()
    }

    private suspend fun loadMnemonic(): String {
        encryptionManager.unlockWithPassword(ENC_PASSWORD.toByteArray()).await()
        return (accountPrefs.getString(PREF_KEY_APP_MNEMONIC, null) ?: run {
            val generateMnemonic =
                encryptionManager.encrypt(bip39.generateMnemonic(languageId = R.id.english).toByteArray(Charset.defaultCharset())).toString()
            accountPrefs.edit { putString(PREF_KEY_APP_MNEMONIC, generateMnemonic) }
            generateMnemonic
        }).let {
            encryptionManager.decrypt(EncryptionManager.CryptoData.fromString(it)).toString(Charset.defaultCharset())
        }
    }

    private suspend fun getKeyPair(index: Long = 0): KeyPair {
        val seed = bip39.mnemonicToSeed(loadMnemonic())
        val hdNode = KeyGenerator.masterNode(ByteString.of(*seed))
        return hdNode.derive(KeyGenerator.BIP44_PATH_ETHEREUM).deriveChild(index).keyPair
    }

    override suspend fun loadModules(safe: Solidity.Address): List<Solidity.Address> =
        jsonRpcApi.post(
            JsonRpcApi.JsonRpcRequest(
                method = "eth_call",
                params = listOf(
                    mapOf(
                        "to" to safe,
                        "data" to GnosisSafe.GetModules.encode()
                    ),
                    "latest"
                )
            )
        ).result!!.let { GnosisSafe.GetModules.decode(it).param0.items }

    override suspend fun loadLimits(safe: Solidity.Address): List<SafeRepository.Limit> {
        val tokensResp = jsonRpcApi.post(
            JsonRpcApi.JsonRpcRequest(
                method = "eth_call",
                params = listOf(
                    mapOf(
                        "to" to TRANSFER_LIMIT_MODULE_ADDRESS,
                        "data" to TransferLimitModule.GetTokens.encode(safe)
                    ),
                    "latest"
                )
            )
        ).result!!
        val tokens = TransferLimitModule.GetTokens.decode(tokensResp).param0.items
        return jsonRpcApi.post(
            tokens.mapIndexed { index, address ->
                JsonRpcApi.JsonRpcRequest(
                    id = index,
                    method = "eth_call",
                    params = listOf(
                        mapOf(
                            "to" to TRANSFER_LIMIT_MODULE_ADDRESS,
                            "data" to TransferLimitModule.GetTokenLimit.encode(safe, address)
                        ),
                        "latest"
                    )
                )
            }
        ).map { resp ->
            TransferLimitModule.GetTokenLimit.decode(resp.result!!).param0.items.let {
                SafeRepository.Limit(
                    token = tokens[resp.id],
                    amount = it[0].value,
                    spent = it[1].value,
                    lastSpent = it[2].value.toLong(),
                    resetPeriod = it[3].value.toLong(),
                    nonce = it[4].value
                )
            }
        }

    }

    private suspend fun getTransferLimitHash(
        safe: Solidity.Address,
        token: Solidity.Address,
        to: Solidity.Address,
        amount: BigInteger,
        paymentToken: Solidity.Address,
        payment: BigInteger,
        nonce: BigInteger
    ) = jsonRpcApi.post(
        JsonRpcApi.JsonRpcRequest(
            method = "eth_call",
            params = listOf(
                mapOf(
                    "to" to TRANSFER_LIMIT_MODULE_ADDRESS,
                    "data" to TransferLimitModule.GenerateTransferHash.encode(
                        safe,
                        token,
                        to,
                        Solidity.UInt96(amount),
                        paymentToken,
                        Solidity.UInt96(payment),
                        Solidity.UInt16(nonce)
                    )
                ),
                "latest"
            )
        )
    ).result!!.let {
        TransferLimitModule.GenerateTransferHash.decode(it).param0.bytes
    }

    override suspend fun performTransferLimit(safe: Solidity.Address, limit: SafeRepository.Limit, to: Solidity.Address, amount: BigInteger) {
        val transferLimitHash = getTransferLimitHash(safe, limit.token, to, amount, Solidity.Address(BigInteger.ZERO), BigInteger.ZERO, limit.nonce)
        val keyPair = getKeyPair()
        val signature = keyPair.sign(transferLimitHash)
        val hash = transferLimitServiceApi.executeLimitTransfer(
            safe.asEthereumAddressChecksumString(),
            limit.token.asEthereumAddressChecksumString(),
            LimitTransferExecution(
                to,
                amount,
                signature.toSignatureString().addHexPrefix()
            )
        )
        print(hash)
    }

    // TODO: optimize
    private val cachedTokenInfo = HashMap<Solidity.Address, SafeRepository.TokenInfo>()

    override suspend fun loadTokenInfo(token: Solidity.Address): SafeRepository.TokenInfo {
        return cachedTokenInfo[token] ?: run {
            relayServiceApi.tokenInfo(token.asEthereumAddressChecksumString()).let {
                SafeRepository.TokenInfo(it.symbol, it.decimals, it.name, it.logoUri).apply {
                    cachedTokenInfo[token] = this
                }
            }
        }
    }

    override suspend fun loadSafeInfo(safe: Solidity.Address): SafeRepository.SafeInfo {
        val responses = jsonRpcApi.post(
            listOf(
                JsonRpcApi.JsonRpcRequest(
                    id = 0,
                    method = "eth_getStorageAt",
                    params = listOf(safe, BigInteger.ZERO.toHexString(), "latest")
                ),
                JsonRpcApi.JsonRpcRequest(
                    id = 1,
                    method = "eth_call",
                    params = listOf(
                        mapOf(
                            "to" to safe,
                            "data" to GnosisSafe.GetOwners.encode()
                        ), "latest"
                    )
                ),
                JsonRpcApi.JsonRpcRequest(
                    id = 2,
                    method = "eth_call",
                    params = listOf(
                        mapOf(
                            "to" to safe,
                            "data" to GnosisSafe.GetThreshold.encode()
                        ), "latest"
                    )
                ),
                JsonRpcApi.JsonRpcRequest(
                    id = 3,
                    method = "eth_call",
                    params = listOf(
                        mapOf(
                            "to" to safe,
                            "data" to GnosisSafe.Nonce.encode()
                        ), "latest"
                    )
                )
            )
        )
        val masterCopy = responses[0].result!!.asEthereumAddress()!!
        val owners = GnosisSafe.GetOwners.decode(responses[1].result!!).param0.items
        val threshold = GnosisSafe.GetThreshold.decode(responses[2].result!!).param0.value
        val nonce = GnosisSafe.Nonce.decode(responses[3].result!!).param0.value
        return SafeRepository.SafeInfo(safe, masterCopy, owners, threshold, nonce)
    }

    override suspend fun loadSafeNonce(safe: Solidity.Address): BigInteger {
        return GnosisSafe.Nonce.decode(
            jsonRpcApi.post(
                JsonRpcApi.JsonRpcRequest(
                    method = "eth_call",
                    params = listOf(
                        mapOf(
                            "to" to safe,
                            "data" to GnosisSafe.Nonce.encode()
                        ), "latest"
                    )
                )
            ).result!!
        ).param0.value
    }

    override suspend fun loadPendingTransactions(safe: Solidity.Address): List<SafeRepository.ServiceSafeTx> =
        transactionServiceApi.loadTransactions(safe.asEthereumAddressChecksumString()).results.map { it.toLocal() }

    override suspend fun loadPendingTransaction(txHash: String): SafeRepository.ServiceSafeTx =
        transactionServiceApi.loadTransaction(txHash).toLocal()

    override suspend fun loadTransactionInformation(safe: Solidity.Address, transaction: SafeRepository.SafeTx) =
        when {
            transaction.to == safe && transaction.data.removeHexPrefix().isBlank() && transaction.value == BigInteger.ZERO -> {// Safe management
                SafeRepository.TransactionInfo(
                    recipient = transaction.to,
                    recipientLabel = transaction.to.asEthereumAddressChecksumString().asMiddleEllipsized(4),
                    assetIcon = "local::settings",
                    assetLabel = "Cancel transaction"
                )
            }
            transaction.to == safe && transaction.value == BigInteger.ZERO -> {// Safe management
                SafeRepository.TransactionInfo(
                    recipient = transaction.to,
                    recipientLabel = transaction.to.asEthereumAddressChecksumString().asMiddleEllipsized(4),
                    assetIcon = "local::settings",
                    assetLabel = "Safe management"
                )
            }
            transaction.data.isSolidityMethod(Compound.Transfer.METHOD_ID) -> { // Token transfer
                val transferArgs = Compound.Transfer.decodeArguments(transaction.data.removeSolidityMethodPrefix(Compound.Transfer.METHOD_ID))
                val tokenInfo = nullOnThrow { loadTokenInfo(transaction.to) }
                val symbol = tokenInfo?.symbol ?: transaction.to.asEthereumAddressChecksumString().asMiddleEllipsized(4)
                SafeRepository.TransactionInfo(
                    recipient = transferArgs.dst,
                    recipientLabel = transferArgs.dst.asEthereumAddressChecksumString().asMiddleEllipsized(4),
                    assetIcon = tokenInfo?.icon,
                    assetLabel = "${transferArgs.amount.value.shiftedString(tokenInfo?.decimals ?: 0)} $symbol",
                    additionalInfo = "Token transfer"
                )
            }
            transaction.data.isSolidityMethod(Compound.Approve.METHOD_ID) -> { // Token transfer
                val approveArgs = Compound.Approve.decodeArguments(transaction.data.removeSolidityMethodPrefix(Compound.Transfer.METHOD_ID))
                val tokenInfo = nullOnThrow { loadTokenInfo(transaction.to) }
                val symbol = tokenInfo?.symbol ?: transaction.to.asEthereumAddressChecksumString().asMiddleEllipsized(4)
                SafeRepository.TransactionInfo(
                    recipient = approveArgs.spender,
                    recipientLabel = approveArgs.spender.asEthereumAddressChecksumString().asMiddleEllipsized(4),
                    assetIcon = tokenInfo?.icon,
                    assetLabel = "Approve ${approveArgs.amount.value.shiftedString(tokenInfo?.decimals ?: 0)} $symbol",
                    additionalInfo = "Token approval"
                )
            }
            else -> // ETH transfer
                SafeRepository.TransactionInfo(
                    recipient = transaction.to,
                    recipientLabel = transaction.to.asEthereumAddressChecksumString().asMiddleEllipsized(4),
                    assetIcon = "local::ethereum",
                    assetLabel = "${transaction.value.shiftedString(18)} ETH",
                    additionalInfo = if (transaction.data.removeHexPrefix().isBlank()) null else "Contract interaction (${transaction.data.length / 2 - 1} bytes)"
                )

        }

    private fun ServiceTransaction.toLocal() =
        SafeRepository.ServiceSafeTx(
            hash = safeTxHash,
            tx = SafeRepository.SafeTx(
                to = to?.asEthereumAddress() ?: Solidity.Address(BigInteger.ZERO),
                value = value.decimalAsBigIntegerOrNull() ?: BigInteger.ZERO,
                data = data ?: "",
                operation = operation.toOperation()
            ),
            execInfo = SafeRepository.SafeTxExecInfo(
                baseGas = baseGas.decimalAsBigIntegerOrNull() ?: BigInteger.ZERO,
                txGas = safeTxGas.decimalAsBigIntegerOrNull() ?: BigInteger.ZERO,
                gasPrice = gasPrice.decimalAsBigIntegerOrNull() ?: BigInteger.ZERO,
                gasToken = gasToken?.asEthereumAddress() ?: Solidity.Address(BigInteger.ZERO),
                refundReceiver = refundReceiver?.asEthereumAddress() ?: Solidity.Address(BigInteger.ZERO),
                nonce = nonce.decimalAsBigInteger()
            ),
            confirmations = confirmations.map { confirmation ->
                confirmation.owner.asEthereumAddress()!! to confirmation.signature
            },
            executed = isExecuted
        )

    override suspend fun confirmSafeTransaction(
        safe: Solidity.Address,
        transaction: SafeRepository.SafeTx,
        execInfo: SafeRepository.SafeTxExecInfo
    ) {
        val hash =
            calculateHash(
                safe,
                transaction.to,
                transaction.value,
                transaction.data,
                transaction.operation,
                execInfo.txGas,
                execInfo.baseGas,
                execInfo.gasPrice,
                execInfo.gasToken,
                execInfo.nonce
            )

        val keyPair = getKeyPair()
        val deviceId = keyPair.address.toAddress()
        val signature = keyPair.sign(hash)

        val confirmation = ServiceTransactionRequest(
            to = transaction.to.asEthereumAddressChecksumString(),
            value = transaction.value.asDecimalString(),
            data = transaction.data,
            operation = transaction.operation.id,
            gasToken = execInfo.gasToken.asEthereumAddressChecksumString(),
            safeTxGas = execInfo.txGas.asDecimalString(),
            baseGas = execInfo.baseGas.asDecimalString(),
            gasPrice = execInfo.gasPrice.asDecimalString(),
            refundReceiver = execInfo.refundReceiver.asEthereumAddressChecksumString(),
            nonce = execInfo.nonce.asDecimalString(),
            safeTxHash = hash.toHexString(),
            sender = deviceId.asEthereumAddressChecksumString(),
            confirmationType = ServiceTransactionRequest.CONFIRMATION,
            signature = signature.toSignatureString()
        )
        transactionServiceApi.confirmTransaction(safe.asEthereumAddressChecksumString(), confirmation)
    }

    private fun ECDSASignature.toSignatureString() =
        r.toString(16).padStart(64, '0').substring(0, 64) +
                s.toString(16).padStart(64, '0').substring(0, 64) +
                v.toString(16).padStart(2, '0')

    private fun String.toECDSASignature(): ECDSASignature {
        require(length == 130)
        val r = BigInteger(substring(0, 64), 16)
        val s = BigInteger(substring(64, 128), 16)
        val v = substring(128, 130).toByte(16)
        return ECDSASignature(r, s).apply { this.v = v }
    }

    private fun Int.toOperation() =
        when (this) {
            0 -> SafeRepository.SafeTx.Operation.CALL
            1 -> SafeRepository.SafeTx.Operation.DELEGATE
            else -> throw IllegalArgumentException("Unsupported operation")
        }

    private fun calculateHash(
        safeAddress: Solidity.Address,
        txTo: Solidity.Address,
        txValue: BigInteger,
        txData: String?,
        txOperation: SafeRepository.SafeTx.Operation,
        txGas: BigInteger,
        dataGas: BigInteger,
        gasPrice: BigInteger,
        gasToken: Solidity.Address,
        txNonce: BigInteger
    ): ByteArray {
        val to = txTo.value.paddedHexString()
        val value = txValue.paddedHexString()
        val data = Sha3Utils.keccak(txData?.hexToByteArray() ?: ByteArray(0)).toHex().padStart(64, '0')
        val operationString = txOperation.id.toBigInteger().paddedHexString()
        val gasPriceString = gasPrice.paddedHexString()
        val txGasString = txGas.paddedHexString()
        val dataGasString = dataGas.paddedHexString()
        val gasTokenString = gasToken.value.paddedHexString()
        val refundReceiverString = BigInteger.ZERO.paddedHexString()
        val nonce = txNonce.paddedHexString()
        return hash(
            safeAddress,
            to,
            value,
            data,
            operationString,
            txGasString,
            dataGasString,
            gasPriceString,
            gasTokenString,
            refundReceiverString,
            nonce
        )
    }

    private fun hash(safeAddress: Solidity.Address, vararg parts: String): ByteArray {
        val initial = StringBuilder().append(ERC191_BYTE).append(ERC191_VERSION).append(domainHash(safeAddress)).append(valuesHash(parts))
        return Sha3Utils.keccak(initial.toString().hexToByteArray())
    }

    private fun domainHash(safeAddress: Solidity.Address) =
        Sha3Utils.keccak(
            ("0x035aff83d86937d35b32e04f0ddc6ff469290eef2f1b692d8a815c89404d4749" +
                    safeAddress.value.paddedHexString()).hexToByteArray()
        ).toHex()

    private fun valuesHash(parts: Array<out String>) =
        parts.fold(StringBuilder().append(getTypeHash())) { acc, part ->
            acc.append(part)
        }.toString().run {
            Sha3Utils.keccak(hexToByteArray()).toHex()
        }

    private fun BigInteger?.paddedHexString(padding: Int = 64) = (this?.toString(16) ?: "").padStart(padding, '0')

    private fun getTypeHash() = "0xbb8310d486368db6bd6f849402fdd73ad53d316b5a4b2644ad6efe0f941286d8"

    private fun ByteArray.toAddress() = Solidity.Address(this.asBigInteger())

    companion object {
        private const val ERC191_BYTE = "19"
        private const val ERC191_VERSION = "01"

        private const val ACC_PREF_NAME = "AccountRepositoryImpl_Preferences"

        private const val PREF_KEY_APP_MNEMONIC = "accounts.string.app_menmonic"
        private const val PREF_KEY_SAFE_ADDRESS = "accounts.string.safe_address"

        private const val ENC_PASSWORD = "ThisShouldNotBeHardcoded"
    }
}
