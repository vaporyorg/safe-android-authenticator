package io.gnosis.safe.authenticator.repositories

import android.content.Context
import io.gnosis.safe.authenticator.GnosisSafe
import io.gnosis.safe.authenticator.R
import io.gnosis.safe.authenticator.data.JsonRpcApi
import io.gnosis.safe.authenticator.data.TransactionServiceApi
import io.gnosis.safe.authenticator.data.models.ServiceTransactionRequest
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
import pm.gnosis.svalinn.common.utils.edit
import pm.gnosis.svalinn.security.EncryptionManager
import pm.gnosis.utils.*
import java.math.BigInteger
import java.nio.charset.Charset

interface SafeRepository {

    data class SafeInfo(
        val address: Solidity.Address,
        val masterCopy: Solidity.Address,
        val owners: List<Solidity.Address>,
        val threshold: BigInteger
    )

    data class PendingSafeTx(
        val hash: String,
        val tx: SafeTx,
        val execInfo: SafeTxExecInfo,
        val confirmations: List<Pair<Solidity.Address, String?>>
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
}

class SafeRepositoryImpl(
    context: Context,
    private val bip39: Bip39,
    private val encryptionManager: EncryptionManager,
    private val jsonRpcApi: JsonRpcApi,
    private val transactionServiceApi: TransactionServiceApi
) : SafeRepository {

    private val accountPrefs = context.getSharedPreferences(ACC_PREF_NAME, Context.MODE_PRIVATE)

    fun isInitialized(): Boolean =
        accountPrefs.getString(PREF_KEY_APP_MNEMONIC, null) != null

    private suspend fun enforceEncryption() {
        if (!encryptionManager.initialized().await() && !encryptionManager.setupPassword(ENC_PASSWORD.toByteArray()).await())
            throw RuntimeException("Could not setup encryption")
    }

    suspend fun loadDeviceId(): Solidity.Address {
        return getKeyPair().address.toAddress()
    }

    suspend fun loadMnemonic(): String {
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

    private suspend fun getKeyPair(): KeyPair {
        val seed = bip39.mnemonicToSeed(loadMnemonic())
        val hdNode = KeyGenerator.masterNode(ByteString.of(*seed))
        return hdNode.derive(KeyGenerator.BIP44_PATH_ETHEREUM).deriveChild(0).keyPair
    }

    suspend fun loadSafeInfo(): SafeRepository.SafeInfo {
        val safeAddress = "0x0".asEthereumAddress()!!
        val responses = jsonRpcApi.post(
            listOf(
                JsonRpcApi.JsonRpcRequest(
                    id = 0,
                    method = "eth_getStorageAt",
                    params = listOf(safeAddress, BigInteger.ZERO.toHexString(), "latest")
                ),
                JsonRpcApi.JsonRpcRequest(
                    id = 1,
                    method = "eth_call",
                    params = listOf(
                        mapOf(
                            "to" to safeAddress,
                            "data" to GnosisSafe.GetOwners.encode()
                        ), "latest"
                    )
                ),
                JsonRpcApi.JsonRpcRequest(
                    id = 2,
                    method = "eth_call",
                    params = listOf(
                        mapOf(
                            "to" to safeAddress,
                            "data" to GnosisSafe.GetThreshold.encode()
                        ), "latest"
                    )
                )
            )
        )
        val masterCopy = responses[0].result!!.asEthereumAddress()!!
        val owners = GnosisSafe.GetOwners.decode(responses[1].result!!).param0.items
        val threshold = GnosisSafe.GetThreshold.decode(responses[2].result!!).param0.value
        return SafeRepository.SafeInfo(safeAddress, masterCopy, owners, threshold)
    }

    private suspend fun loadSafeNonce(safeAddress: Solidity.Address): BigInteger {
        return GnosisSafe.Nonce.decode(
            jsonRpcApi.post(
                JsonRpcApi.JsonRpcRequest(
                    method = "eth_call",
                    params = listOf(
                        mapOf(
                            "to" to safeAddress,
                            "data" to GnosisSafe.Nonce.encode()
                        ), "latest"
                    )
                )
            ).result!!
        ).param0.value
    }

    suspend fun loadPendingTransactions(): List<SafeRepository.PendingSafeTx> {
        val safeAddress = "0x0".asEthereumAddress()!!
        val currentNonce = loadSafeNonce(safeAddress)
        val transactions = transactionServiceApi.loadTransactions(safeAddress.asEthereumAddressChecksumString())
        return transactions.results.mapNotNull {
            val nonce = it.nonce.decimalAsBigIntegerOrNull()
            if (it.isExecuted || nonce == null || nonce < currentNonce) return@mapNotNull null
            SafeRepository.PendingSafeTx(
                hash = it.safeTxHash,
                tx = SafeRepository.SafeTx(
                    to = it.to?.asEthereumAddress() ?: Solidity.Address(BigInteger.ZERO),
                    value = it.value.decimalAsBigIntegerOrNull() ?: BigInteger.ZERO,
                    data = it.data ?: "",
                    operation = it.operation.toOperation()
                ),
                execInfo = SafeRepository.SafeTxExecInfo(
                    baseGas = it.baseGas.decimalAsBigIntegerOrNull() ?: BigInteger.ZERO,
                    txGas = it.safeTxGas.decimalAsBigIntegerOrNull() ?: BigInteger.ZERO,
                    gasPrice = it.gasPrice.decimalAsBigIntegerOrNull() ?: BigInteger.ZERO,
                    gasToken = it.gasToken?.asEthereumAddress() ?: Solidity.Address(BigInteger.ZERO),
                    refundReceiver = it.refundReceiver?.asEthereumAddress() ?: Solidity.Address(BigInteger.ZERO),
                    nonce = nonce
                ),
                confirmations = it.confirmations.map { confirmation ->
                    confirmation.owner.asEthereumAddress()!! to confirmation.signature
                }
            )
        }
    }

    suspend fun confirmSafeTransaction(
        transaction: SafeRepository.SafeTx,
        execInfo: SafeRepository.SafeTxExecInfo,
        confirmations: List<Pair<Solidity.Address, String?>>?
    ) {
        val safeAddress = "0x0".asEthereumAddress()!!
        val hash =
            calculateHash(
                safeAddress,
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
        transactionServiceApi.confirmTransaction(safeAddress.asEthereumAddressChecksumString(), confirmation)
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

        private const val ENC_PASSWORD = "ThisShouldNotBeHardcoded"
    }
}