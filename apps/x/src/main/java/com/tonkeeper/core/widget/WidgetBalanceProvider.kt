package com.tonkeeper.core.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.widget.RemoteViews
import com.tonapps.tonkeeperx.R
import com.tonkeeper.App
import com.tonkeeper.api.account.AccountRepository
import com.tonkeeper.api.shortAddress
import com.tonkeeper.core.Coin
import com.tonkeeper.core.currency.ton
import com.tonkeeper.core.formatter.CurrencyFormatter
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ton.SupportedCurrency
import ton.SupportedTokens


class WidgetBalanceProvider: Widget() {

    companion object {
        fun requestUpdate(context: Context = App.instance) {
            update(context, WidgetBalanceProvider::class.java)
        }
    }

    private data class Balance(
        val tonBalance: String,
        val currencyBalance: String,
        val walletAddress: String
    )

    private val accountRepository = AccountRepository()

    @OptIn(DelicateCoroutinesApi::class)
    override fun update(context: Context, manager: AppWidgetManager, id: Int) {
        scope.launch(Dispatchers.IO) {
            val wallet = App.walletManager.getWalletInfo()
            if (wallet == null) {
                displayPlaceholderData(context, manager, id)
                return@launch
            }

            var response = accountRepository.getFromCloud(wallet.accountId, wallet.testnet)
            if (response == null) {
                response = accountRepository.get(wallet.accountId, wallet.testnet)
            }
            val account = response?.data ?: return@launch

            val tonInCurrency = wallet.ton(account.balance)
                .to(currency)

            val amount = Coin.toCoins(account.balance)

            val balance = Balance(
                tonBalance = CurrencyFormatter.format(SupportedCurrency.TON.code, amount),
                currencyBalance = CurrencyFormatter.formatFiat(tonInCurrency),
                walletAddress = wallet.address.shortAddress
            )

            displayData(context, manager, id, balance)
        }
    }

    private suspend fun displayPlaceholderData(
        context: Context,
        manager: AppWidgetManager,
        id: Int
    ) = withContext(Dispatchers.Main) {
        val removeView = RemoteViews(context.packageName, R.layout.widget_balance)
        removeView.setOnClickPendingIntent(R.id.content, defaultPendingIntent)
        removeView.setTextViewText(R.id.address, context.getString(R.string.widget_placeholder))
        manager.updateAppWidget(id, removeView)
    }

    private suspend fun displayData(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        balance: Balance
    ) = withContext(Dispatchers.Main) {
        val removeView = RemoteViews(context.packageName, R.layout.widget_balance)
        removeView.setOnClickPendingIntent(R.id.content, defaultPendingIntent)
        removeView.setTextViewText(R.id.token, balance.tonBalance)
        removeView.setTextViewText(R.id.currency, balance.currencyBalance)
        removeView.setTextViewText(R.id.address, balance.walletAddress)
        manager.updateAppWidget(id, removeView)
    }


}