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
import kotlinx.android.synthetic.main.screen_transactions.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.koin.androidx.viewmodel.ext.android.viewModel
import pm.gnosis.model.Solidity

@ExperimentalCoroutinesApi
abstract class TransactionsContract : LoadingViewModel<TransactionsContract.State>() {
    abstract fun loadTransactions()

    data class State(
        val loading: Boolean,
        val safe: Solidity.Address?,
        val transactions: List<TransactionMeta>,
        override var viewAction: ViewAction?
    ) :
        BaseViewModel.State

    data class TransactionMeta(
        val hash: String,
        val info: SafeRepository.SafeTx,
        val execInfo: SafeRepository.SafeTxExecInfo,
        val state: State
    ) {
        enum class State {
            EXECUTED,
            CANCELED,
            CONFIRMED,
            PENDING
        }
    }
}

@ExperimentalCoroutinesApi
class TransactionsViewModel(
    private val safeRepository: SafeRepository
) : TransactionsContract() {

    override val state = liveData {
        loadTransactions()
        for (event in stateChannel.openSubscription()) emit(event)
    }

    override fun loadTransactions() {
        if (currentState().loading) return
        loadingLaunch {
            updateState { copy(loading = true) }
            val safe = safeRepository.loadSafeAddress()
            val txs = safeRepository.loadPendingTransactions(safe)
            val deviceId = safeRepository.loadDeviceId()
            val nonce = safeRepository.loadSafeNonce(safe)
            val transactions = txs.map {
                val txState = when {
                    it.executed -> TransactionMeta.State.EXECUTED
                    it.execInfo.nonce < nonce -> TransactionMeta.State.CANCELED
                    it.confirmations.find { (address, _) -> address == deviceId } != null -> TransactionMeta.State.CONFIRMED
                    else -> TransactionMeta.State.PENDING
                }
                TransactionMeta(it.hash, it.tx, it.execInfo, txState)
            }
            updateState { copy(loading = false, safe = safe, transactions = transactions) }
        }
    }

    override fun onLoadingError(state: State, e: Throwable) = state.copy(loading = false)

    override fun initialState() = State(false, null, emptyList(), null)

}

@ExperimentalCoroutinesApi
class TransactionsActivity : BaseActivity<TransactionsContract.State, TransactionsContract>(), TransactionConfirmationDialog.Callback {
    override val viewModel: TransactionsContract by viewModel()
    private val adapter = TransactionAdapter()
    private val layoutManager = LinearLayoutManager(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.screen_transactions)
        transactions_list.adapter = adapter
        transactions_list.layoutManager = layoutManager
        //transactions_back_btn.setOnClickListener { onBackPressed() }
        transactions_refresh.setOnRefreshListener {
            viewModel.loadTransactions()
        }
        transactions_settings_btn.setOnClickListener {
            startActivity(SettingsActivity.createIntent(this))
        }
        transactions_add_tx_btn.setOnClickListener {
            startActivity(NewTransactionActivity.createIntent(this))
        }
    }

    override fun updateState(state: TransactionsContract.State) {
        transactions_refresh.isRefreshing = state.loading
        adapter.safe = state.safe
        adapter.submitList(state.transactions)
    }

    inner class TransactionAdapter : ListAdapter<TransactionsContract.TransactionMeta, ViewHolder>(DiffCallback()) {
        var safe: Solidity.Address? = null
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_pending_tx, parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(safe, getItem(position))
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind(safe: Solidity.Address?, item: TransactionsContract.TransactionMeta) {
            if (item.state != TransactionsContract.TransactionMeta.State.PENDING)
                itemView.setOnClickListener(null)
            else
                itemView.setOnClickListener {
                    safe ?: return@setOnClickListener
                    TransactionConfirmationDialog(this@TransactionsActivity, safe, item.info, item.execInfo).show()
                }
            itemView.pending_tx_target.setAddress(item.info.to)
            itemView.pending_tx_confirmations.text = when(item.state) {
                TransactionsContract.TransactionMeta.State.EXECUTED -> "Executed"
                TransactionsContract.TransactionMeta.State.CANCELED -> "Canceled"
                TransactionsContract.TransactionMeta.State.CONFIRMED -> "Confirmed"
                TransactionsContract.TransactionMeta.State.PENDING -> "Pending"
            }
            itemView.pending_tx_description.text = item.hash.asMiddleEllipsized(6)
        }
    }

    override fun onConfirmed() {
        viewModel.loadTransactions()
    }

    class DiffCallback : DiffUtil.ItemCallback<TransactionsContract.TransactionMeta>() {
        override fun areItemsTheSame(oldItem: TransactionsContract.TransactionMeta, newItem: TransactionsContract.TransactionMeta) =
            oldItem.hash == newItem.hash

        override fun areContentsTheSame(oldItem: TransactionsContract.TransactionMeta, newItem: TransactionsContract.TransactionMeta) =
            oldItem == newItem

    }

    companion object {
        fun createIntent(context: Context) = Intent(context, TransactionsActivity::class.java)
    }

}