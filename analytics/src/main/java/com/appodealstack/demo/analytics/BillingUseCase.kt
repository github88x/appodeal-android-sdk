package com.appodealstack.demo.analytics

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.android.billingclient.api.*
import java.util.concurrent.atomic.AtomicBoolean

class BillingUseCase(context: Context) {

    private val _purchases: MutableLiveData<List<Purchase>> = MutableLiveData()
    val purchases: LiveData<List<Purchase>> get() = _purchases

    private val knownInappProducts: List<String> = listOf(SKU_COINS)
    private val knownSubscriptionProducts: List<String> = listOf(SKU_INFINITE_ACCESS_MONTHLY)

    fun flow(activity: Activity, product: String) {
        val productDetails: ProductDetails = productDetailsMap[product] ?: return
        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .build()
        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()
        val billingResult = billingClient.launchBillingFlow(activity, billingFlowParams)
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            debug("Flow billing success")
            billingFlowInProcess.set(true)
        } else {
            error("Flow billing failed: ${billingResult.debugMessage}")
        }
    }

    // TODO: 19/05/2022 [glavatskikh]
    private var reconnectMilliseconds = RECONNECT_TIMER_START_MILLISECONDS
    private val billingSetupComplete = AtomicBoolean(false)
    private val billingFlowInProcess = AtomicBoolean(false)

    private val productDetailsMap: MutableMap<String, ProductDetails> = HashMap()
    private val purchaseConsumptionInProcess: MutableSet<Purchase> = HashSet()

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        debug("onPurchasesUpdated: ${billingResult.responseCode} ${billingResult.debugMessage}")
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.let {
                    processPurchaseList(it)
                } ?: error("onPurchasesUpdated: Null Purchase List Returned from OK response!")
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> debug("onPurchasesUpdated: User canceled the purchase")
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> debug("onPurchasesUpdated: The user already owns this item")
            BillingClient.BillingResponseCode.DEVELOPER_ERROR ->
                error(
                    "onPurchasesUpdated: Developer error means that Google Play " +
                            "does not recognize the configuration. If you are just getting started, " +
                            "make sure you have configured the application correctly in the " +
                            "Google Play Console. The SKU product ID must match and the APK you " +
                            "are using must be signed with release keys."
                )
        }
        billingFlowInProcess.set(false)
        _purchases.postValue(purchases)
    }

    private val billingClientStateListener = object : BillingClientStateListener {
        override fun onBillingSetupFinished(billingResult: BillingResult) {
            val responseCode = billingResult.responseCode
            debug("onBillingSetupFinished: $responseCode ${billingResult.debugMessage}")
            if (responseCode == BillingClient.BillingResponseCode.OK) {
                // The billing client is ready. You can query purchases here.
                // This doesn't mean that your app is set up correctly in the console -- it just
                // means that you have a connection to the Billing service.
                reconnectMilliseconds += RECONNECT_TIMER_START_MILLISECONDS
                billingSetupComplete.set(true)
                queryProductDetailsAsync()
                refreshPurchasesAsync()
            } else {
                retryBillingServiceConnectionWithExponentialBackoff()
            }
        }

        override fun onBillingServiceDisconnected() {
            debug("onBillingServiceDisconnected")
            billingSetupComplete.set(false)
            retryBillingServiceConnectionWithExponentialBackoff()
        }
    }

    private val onProductDetailsResponse =
        ProductDetailsResponseListener { billingResult, productDetailsList ->
            val responseCode = billingResult.responseCode
            val debugMessage = billingResult.debugMessage
            debug("onSkuDetailsResponse: $responseCode $debugMessage")
            if (responseCode == BillingClient.BillingResponseCode.OK) {
                if (productDetailsList.isEmpty()) {
                    error(
                        "onSkuDetailsResponse: " +
                                "Found null or empty SkuDetails. " +
                                "Check to see if the SKUs you requested are correctly published " +
                                "in the Google Play Console."
                    )
                } else {
                    for (productDetail: ProductDetails in productDetailsList) {
                        productDetailsMap[productDetail.name] = productDetail
                    }
                }
            }
        }

    private val billingClient = BillingClient.newBuilder(context)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases()
        .build()
        .apply { startConnection(billingClientStateListener) }

    private fun queryProductDetailsAsync() {
        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(SKU_COINS)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            ).build(),
            onProductDetailsResponse
        )
        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(SKU_INFINITE_ACCESS_MONTHLY)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            ).build(),
            onProductDetailsResponse
        )
    }

    private fun refreshPurchasesAsync() {
        val purchasesResponseListener = PurchasesResponseListener { billingResult, purchaseList ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                error("Problem getting purchases: ${billingResult.debugMessage}")
            } else {
                processPurchaseList(purchaseList)
            }
        }
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP)
                .build(),
            purchasesResponseListener
        )
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams
                .newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
            purchasesResponseListener
        )
        debug("Refreshing purchases started.")
    }

    private fun retryBillingServiceConnectionWithExponentialBackoff(): Nothing = TODO()

    private fun processPurchaseList(purchases: List<Purchase>?) {
        purchases
            ?.filter { purchase -> purchase.purchaseState == Purchase.PurchaseState.PURCHASED }
            ?.forEach { purchase ->
                var isConsumable = false
                // TODO: 19/05/2022 [glavatskikh] simple
                for (product: String in purchase.products) {
                    if (knownInappProducts.contains(product)) {
                        isConsumable = true
                    } else {
                        if (isConsumable) {
                            Log.e(
                                TAG,
                                "Purchase cannot contain a mixture of consumable and non-consumable items: ${purchase.products}"
                            )
                            isConsumable = false
                            break
                        }
                    }
                }
                if (isConsumable) {
                    consumePurchase(purchase)
                } else if (!purchase.isAcknowledged) {
                    acknowledgePurchase(purchase)
                }
            } ?: debug("Empty purchase list.")
    }

    private fun consumePurchase(purchase: Purchase) {
        // TODO: 19/05/2022 [glavatskikh] we can remove it
        if (purchaseConsumptionInProcess.contains(purchase)) {
            return
        }
        debug("Start consumption flow.")
        purchaseConsumptionInProcess.add(purchase)
        // TODO: 19/05/2022 [glavatskikh] use coroutines
        billingClient.consumeAsync(
            ConsumeParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
        ) { billingResult: BillingResult, _: String? ->
            purchaseConsumptionInProcess.remove(purchase)
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                debug("Consumption successful. Delivering entitlement.")
            } else {
                error("Error while consuming: ${billingResult.debugMessage}")
            }
            debug("End consumption flow.")
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        debug("Start acknowledge flow.")
        // TODO: 19/05/2022 [glavatskikh] use coroutines
        billingClient.acknowledgePurchase(
            AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
        ) { billingResult: BillingResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                debug("Acknowledge successful.")
            } else {
                error("Error while acknowledge: ${billingResult.debugMessage}")
            }
            debug("End acknowledge flow.")
        }
    }
}

private fun debug(message: String) = Log.d(TAG, message)
private fun error(message: String) = Log.e(TAG, message)

private const val TAG = "BillingClient"

private val handler = Handler(Looper.getMainLooper())
private const val RECONNECT_TIMER_START_MILLISECONDS = 1000L
private const val RECONNECT_TIMER_MAX_TIME_MILLISECONDS = 1000L * 60L * 15L