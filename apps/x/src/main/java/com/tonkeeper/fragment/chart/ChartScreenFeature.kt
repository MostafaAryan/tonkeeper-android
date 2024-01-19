package com.tonkeeper.fragment.chart

import androidx.lifecycle.viewModelScope
import com.tonkeeper.App
import com.tonkeeper.api.Tonapi
import com.tonkeeper.api.account.AccountRepository
import com.tonkeeper.api.chart.ChartHelper
import com.tonkeeper.api.chart.ChartPeriod
import com.tonkeeper.api.withRetry
import com.tonkeeper.api.withTON
import com.tonkeeper.core.Coin
import com.tonkeeper.core.formatter.CurrencyFormatter
import com.tonkeeper.core.currency.CurrencyManager
import com.tonkeeper.core.currency.ton
import com.tonkeeper.core.history.HistoryHelper
import com.tonkeeper.core.history.list.item.HistoryItem
import core.QueueScope
import io.tonapi.models.AccountEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import ton.SupportedCurrency
import ton.SupportedTokens
import ton.wallet.Wallet
import uikit.mvi.AsyncState
import uikit.mvi.UiFeature

class ChartScreenFeature: UiFeature<ChartScreenState, ChartScreenEffect>(ChartScreenState()) {

    private val currencyManager = CurrencyManager.getInstance()
    private val currency: SupportedCurrency
        get() = App.settings.currency

    private val accountRepository = AccountRepository()
    private val accountsApi = Tonapi.accounts
    private val queueScope = QueueScope(viewModelScope.coroutineContext)
    private var lastLt: Long? = null

    fun load() {
        queueScope.submit {
            val wallet = App.walletManager.getWalletInfo() ?: return@submit
            val accountId = wallet.accountId

            val accountDeferred = async { accountRepository.get(accountId, wallet.testnet)?.data }
            val chartDeferred = async { ChartHelper.getEntity(uiState.value.chartPeriod) }
            val historyItemsDeferred = async { getEvents(wallet) }

            val account = accountDeferred.await() ?: return@submit
            val chart = chartDeferred.await()
            val historyItems = historyItemsDeferred.await()

            val balance = Coin.toCoins(account.balance)
            val currencyBalance = wallet.ton(balance).to(currency)

            val rate = currencyManager.getRate(accountId, wallet.testnet, SupportedTokens.TON, currency)
            val rate24h = currencyManager.getRate24h(accountId, wallet.testnet, SupportedTokens.TON, currency)

            updateUiState {
                it.copy(
                    walletType = wallet.type,
                    asyncState = AsyncState.Default,
                    balance = CurrencyFormatter.format(SupportedCurrency.TON.code, balance),
                    currencyBalance = CurrencyFormatter.formatFiat(currencyBalance),
                    rateFormat = CurrencyFormatter.formatRate(currency.code, rate),
                    rate24h = rate24h,
                    historyItems = historyItems,
                    chart = chart
                )
            }
        }
    }

    fun loadMore() {
        queueScope.submit {
            val lt = lastLt ?: return@submit
            if (lt == 0L) {
                return@submit
            }

            updateUiState {
                it.copy(
                    historyItems = HistoryHelper.withLoadingItem(it.historyItems)
                )
            }

            val wallet = App.walletManager.getWalletInfo() ?: return@submit
            val historyItems = getEvents(wallet, lt)
            val items = HistoryHelper.removeLoadingItem(uiState.value.historyItems) + historyItems

            updateUiState {
                it.copy(
                    walletType = wallet.type,
                    historyItems = items,
                    loadedAll = historyItems.isEmpty()
                )
            }
        }
    }

    fun loadChart(period: ChartPeriod) {
        queueScope.submit {
            val chart = ChartHelper.getEntity(period)

            updateUiState {
                it.copy(
                    chart = chart,
                    chartPeriod = period
                )
            }
        }
    }

    private suspend fun getEvents(
        wallet: Wallet,
        beforeLt: Long? = null
    ): List<HistoryItem> = withContext(Dispatchers.IO) {
        val accountId = wallet.accountId
        val events = getAccountEvent(accountId, wallet.testnet, beforeLt)?.events ?: return@withContext emptyList()
        lastLt = events.lastOrNull()?.lt
        HistoryHelper.mapping(wallet, events.filter {
            it.withTON
        })
    }

    private suspend fun getAccountEvent(
        accountId: String,
        testnet: Boolean,
        beforeLt: Long?,
    ): AccountEvents? {
        return withRetry {
            accountsApi.get(testnet).getAccountEvents(accountId = accountId, limit = 100, subjectOnly = true, beforeLt = beforeLt)
        }
    }

    override fun onCleared() {
        super.onCleared()
        queueScope.cancel()
    }
}