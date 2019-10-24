package io.gnosis.safe.authenticator.ui.transactions

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.liveData
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.gnosis.safe.authenticator.R
import io.gnosis.safe.authenticator.repositories.SafeRepository
import io.gnosis.safe.authenticator.ui.base.BaseActivity
import io.gnosis.safe.authenticator.ui.base.BaseViewModel
import io.gnosis.safe.authenticator.ui.base.LoadingViewModel
import io.gnosis.safe.authenticator.ui.settings.SettingsActivity
import io.gnosis.safe.authenticator.utils.asMiddleEllipsized
import kotlinx.android.synthetic.main.item_pending_tx.view.*
import kotlinx.android.synthetic.main.screen_new_transaction.*
import kotlinx.android.synthetic.main.screen_transactions.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.walleth.khex.toHexString
import pm.gnosis.model.Solidity
import pm.gnosis.utils.*
import pm.gnosis.utils.toHexString
import java.math.BigInteger

@ExperimentalCoroutinesApi
abstract class NewTransactionContract : LoadingViewModel<NewTransactionContract.State>() {
    abstract fun submitTransaction(
        to: String,
        value: String,
        data: String,
        nonce: String
    )

    data class State(
        val loading: Boolean,
        val currentNonce: BigInteger?,
        val done: Boolean,
        override var viewAction: ViewAction?
    ) :
        BaseViewModel.State
}

@ExperimentalCoroutinesApi
class NewTransactionViewModel(
    private val safeRepository: SafeRepository
) : NewTransactionContract() {

    override val state = liveData {
        loadNonce()
        for (event in stateChannel.openSubscription()) emit(event)
    }

    override fun submitTransaction(
        to: String,
        value: String,
        data: String,
        nonce: String
    ) {
        if (currentState().loading) return
        loadingLaunch {
            updateState { copy(loading = true) }
            val safe = safeRepository.loadSafeAddress()
            val toAddress = to.asEthereumAddress()!!
            val weiValue = value.decimalAsBigInteger()
            val dataBytes = data.hexStringToByteArray()
            val nonceValue = nonce.decimalAsBigInteger()
            val tx = SafeRepository.SafeTx(
                toAddress, weiValue, dataBytes.toHexString().addHexPrefix(), SafeRepository.SafeTx.Operation.CALL
            )
            val execInfo = SafeRepository.SafeTxExecInfo(
                BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, "0x0".asEthereumAddress()!!, "0x0".asEthereumAddress()!!, nonceValue
            )
            safeRepository.confirmSafeTransaction(safe, tx, execInfo)
            updateState { copy(loading = false, done = true) }
        }
    }

    private fun loadNonce() {
        safeLaunch {
            val safe = safeRepository.loadSafeAddress()
            val nonce = safeRepository.loadSafeNonce(safe)
            updateState { copy(currentNonce = nonce) }
        }
    }

    override fun onLoadingError(state: State, e: Throwable) = state.copy(loading = false)

    override fun initialState() = State(false, null, false, null)

}

@ExperimentalCoroutinesApi
class NewTransactionActivity : BaseActivity<NewTransactionContract.State, NewTransactionContract>() {
    override val viewModel: NewTransactionContract by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.screen_new_transaction)
        new_transaction_back_btn.setOnClickListener { onBackPressed() }
        new_transaction_submit_btn.setOnClickListener {
            viewModel.submitTransaction(
                new_transaction_recipient_input.text.toString(),
                new_transaction_value_input.text.toString(),
                new_transaction_data_input.text.toString(),
                new_transaction_nonce_input.text.toString()
            )
        }
    }

    override fun updateState(state: NewTransactionContract.State) {
        if (new_transaction_nonce_input.text.toString().isBlank()) {
            new_transaction_nonce_input.setText(state.currentNonce?.toString())
        }
        if (state.done) finish()
    }

    companion object {
        fun createIntent(context: Context) = Intent(context, NewTransactionActivity::class.java)
    }

}