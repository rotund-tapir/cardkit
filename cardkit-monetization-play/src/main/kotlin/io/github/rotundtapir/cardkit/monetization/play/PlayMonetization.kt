// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.cardkit.monetization.play

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.ProductDetails
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import io.github.rotundtapir.cardkit.monetization.Monetization
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ad-supported monetization for Google Play builds: a banner slot, occasional interstitials, and a
 * one-time "remove ads" in-app purchase (Google Play Billing).
 *
 * Ad unit ids and the product id are supplied via [Config] so the library carries no hard-coded
 * account details. Use Google's official test ad unit ids during development.
 *
 * Startup is billing-first: the `remove_ads` entitlement is checked before consent is gathered or
 * the Mobile Ads SDK is touched, so a paying user's device never talks to ad servers at all. The
 * entitlement is cached in [android.content.SharedPreferences] (a cached purchase skips the whole
 * ads path outright, including offline); with no cache, consent waits up to
 * [ENTITLEMENT_WAIT_MILLIS] for Play Billing's verdict — "owned" cancels the ads path (covers
 * reinstalls), "not owned" or the timeout lets consent proceed, so non-payers are never held up
 * by a slow or dead billing connection. A definitive "not owned" also clears a stale cache
 * (refund/revocation) and re-opens the consent path.
 *
 * Ads are then gated behind Google's User Messaging Platform: the consent state is refreshed
 * (showing the GDPR consent form to EEA/UK users when required) and the Mobile Ads SDK is only
 * initialised — and ads only requested — once [ConsentInformation.canRequestAds] holds. Consent
 * failures (offline, misconfiguration) never block the app; ads simply stay off.
 */
class PlayMonetization(
    private val activity: Activity,
    private val config: Config,
) : Monetization, PurchasesUpdatedListener {

    data class Config(
        val bannerAdUnitId: String,
        val interstitialAdUnitId: String,
        val removeAdsProductId: String,
        /** Debug-only: force EEA geography so the consent form always shows. */
        val consentDebugGeographyEea: Boolean = false,
        /** Debug-only: UMP test device hashed ids (logged by UMP on first run). */
        val consentTestDeviceHashedIds: List<String> = emptyList(),
    )

    private val appContext = activity.applicationContext

    private val _adsRemoved = MutableStateFlow(false)
    override val adsRemoved: StateFlow<Boolean> = _adsRemoved.asStateFlow()

    override val offersRemoveAds: Boolean = true

    private val consentInformation = UserMessagingPlatform.getConsentInformation(appContext)

    /** Ads may be requested (consent obtained or not required) — gates the banner and interstitials. */
    private val _adsEnabled = MutableStateFlow(false)

    private val _privacyOptionsRequired = MutableStateFlow(false)
    override val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired.asStateFlow()

    private var adsInitialized = false
    private var consentRequested = false

    /** Set by a live grant; a stale in-flight "not owned" query result must not undo it. */
    @Volatile
    private var grantedThisSession = false

    private var interstitial: InterstitialAd? = null
    private var removeAdsProduct: ProductDetails? = null

    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectDelayMillis = INITIAL_RECONNECT_DELAY_MILLIS
    private var disposed = false

    private val billingClient: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    init {
        if (prefs.getBoolean(KEY_REMOVE_ADS_ENTITLED, false)) {
            // Cached entitlement: no consent, no ads SDK. Billing still reconciles below and
            // re-opens the consent path if Play reports the purchase gone (refund/revocation).
            _adsRemoved.value = true
        } else {
            // Entitlement unknown: hold consent for billing's verdict so a reinstalling payer
            // never initialises the ads SDK. The timeout keeps a slow or dead billing connection
            // from delaying consent for everyone else.
            mainHandler.postDelayed(::startConsentIfStillUnpaid, ENTITLEMENT_WAIT_MILLIS)
        }
        connectBilling()
    }

    // --- Consent (UMP) -----------------------------------------------------------------------------

    /** Idempotent: called by the entitlement timeout and by billing's "not owned" verdict. */
    private fun startConsentIfStillUnpaid() {
        if (disposed || consentRequested || _adsRemoved.value) return
        consentRequested = true
        requestConsent(activity)
    }

    private fun requestConsent(activity: Activity) {
        val params = ConsentRequestParameters.Builder().apply {
            if (config.consentDebugGeographyEea || config.consentTestDeviceHashedIds.isNotEmpty()) {
                setConsentDebugSettings(
                    ConsentDebugSettings.Builder(activity).apply {
                        config.consentTestDeviceHashedIds.forEach(::addTestDeviceHashedId)
                        if (config.consentDebugGeographyEea) {
                            setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                        }
                    }.build()
                )
            }
        }.build()

        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                // Shows the form only when consent is required; the callback fires either way.
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { _ ->
                    // Form errors (offline, misconfiguration) must never block the game: proceed,
                    // and ads stay off until canRequestAds() holds on a later launch.
                    updateFromConsentState()
                }
            },
            { _ ->
                updateFromConsentState()
            },
        )
        // Consent persisted from a previous session lets ads start immediately, in parallel with
        // the update above (Google's recommended pattern).
        updateFromConsentState()
    }

    private fun updateFromConsentState() {
        _privacyOptionsRequired.value = consentInformation.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        if (consentInformation.canRequestAds()) initializeAds()
    }

    private fun initializeAds() {
        if (adsInitialized) return // all UMP callbacks arrive on the main thread
        adsInitialized = true
        MobileAds.initialize(appContext) { }
        _adsEnabled.value = true
        loadInterstitial()
    }

    override fun showPrivacyOptionsForm() {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { _ ->
            updateFromConsentState()
        }
    }

    // --- Billing ---------------------------------------------------------------------------------

    private fun connectBilling() {
        // Guard against overlapping attempts (a user tap racing a scheduled reconnect).
        val state = billingClient.connectionState
        if (state == BillingClient.ConnectionState.CONNECTING ||
            state == BillingClient.ConnectionState.CONNECTED
        ) {
            return
        }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    reconnectDelayMillis = INITIAL_RECONNECT_DELAY_MILLIS
                    queryRemoveAdsProduct()
                    refreshPurchases()
                }
            }

            override fun onBillingServiceDisconnected() {
                // Play Billing drops connections routinely (e.g. the Play Store self-updating);
                // without a reconnect, no query or purchase works again for the session.
                scheduleBillingReconnect()
            }
        })
    }

    /** Capped exponential backoff; cancelled by [dispose] so no timer fires on a dead client. */
    private fun scheduleBillingReconnect() {
        if (disposed) return
        mainHandler.postDelayed({ if (!disposed) connectBilling() }, reconnectDelayMillis)
        reconnectDelayMillis = (reconnectDelayMillis * 2).coerceAtMost(MAX_RECONNECT_DELAY_MILLIS)
    }

    private fun queryRemoveAdsProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(config.removeAdsProductId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()
        // Billing 8: the callback carries a QueryProductDetailsResult (fetched list + the ids Play
        // could not resolve) instead of a bare list.
        billingClient.queryProductDetailsAsync(params) { _, result ->
            removeAdsProduct = result.productDetailsList.firstOrNull()
        }
    }

    /** Reconcile [adsRemoved] with purchases already owned (e.g. after reinstall / on another device). */
    private fun refreshPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            // Billing callbacks may arrive off the main thread; UMP and the consent gate are
            // main-thread-only, so marshal before acting on the verdict.
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            purchases.forEach(::grantIfRemoveAds)
            val owned = purchases.any {
                it.isRemoveAds() && it.purchaseState == Purchase.PurchaseState.PURCHASED
            }
            if (!owned) mainHandler.post(::onEntitlementNotOwned)
        }
    }

    /**
     * Play answered definitively: `remove_ads` is not owned. Clears a stale cached entitlement
     * (refund/revocation) and lets the consent → ads path proceed without waiting out the
     * startup timeout.
     */
    private fun onEntitlementNotOwned() {
        if (disposed || grantedThisSession) return
        prefs.edit().putBoolean(KEY_REMOVE_ADS_ENTITLED, false).apply()
        _adsRemoved.value = false
        startConsentIfStillUnpaid()
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode != BillingClient.BillingResponseCode.OK || purchases == null) return
        purchases.forEach(::grantIfRemoveAds)
    }

    /**
     * Grants remove-ads for a PURCHASED [purchase] of the configured product, acknowledging it if
     * needed. Acknowledgement must also happen on the refresh path, not just onPurchasesUpdated:
     * if the original ack failed (network, process death) or a pending purchase completed while
     * the app was closed, Google auto-refunds an unacknowledged purchase after 3 days.
     */
    private fun grantIfRemoveAds(purchase: Purchase) {
        if (!purchase.isRemoveAds() || purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        grantedThisSession = true
        _adsRemoved.value = true
        prefs.edit().putBoolean(KEY_REMOVE_ADS_ENTITLED, true).apply()
        if (!purchase.isAcknowledged) {
            val ack = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient.acknowledgePurchase(ack) { }
        }
    }

    private fun Purchase.isRemoveAds(): Boolean = products.contains(config.removeAdsProductId)

    override fun launchRemoveAdsOrDonate() {
        val product = removeAdsProduct ?: run {
            // Product details never arrived (billing was down when queried). Kick the connection
            // so a later tap can succeed; there is nothing to show for this one.
            connectBilling()
            if (billingClient.isReady) queryRemoveAdsProduct()
            return
        }
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(product)
                        .build()
                )
            )
            .build()
        billingClient.launchBillingFlow(activity, flowParams)
    }

    // --- Ads -------------------------------------------------------------------------------------

    @Composable
    override fun BannerSlot(modifier: Modifier) {
        val removed by adsRemoved.collectAsState()
        val enabled by _adsEnabled.collectAsState()
        if (removed || !enabled) return
        AndroidView(
            modifier = modifier.fillMaxWidth(),
            factory = { ctx ->
                AdView(ctx).apply {
                    setAdSize(AdSize.BANNER)
                    adUnitId = config.bannerAdUnitId
                    loadAd(AdRequest.Builder().build())
                }
            },
            // AdView is WebView-backed; skipping destroy() leaks its native resources for the
            // process lifetime once the slot leaves composition (e.g. remove-ads mid-session).
            onRelease = { it.destroy() },
        )
    }

    private fun loadInterstitial() {
        if (_adsRemoved.value || !_adsEnabled.value) return
        InterstitialAd.load(
            appContext,
            config.interstitialAdUnitId,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitial = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitial = null
                }
            },
        )
    }

    override fun maybeShowInterstitial(onDismissed: () -> Unit) {
        if (_adsRemoved.value || !_adsEnabled.value) {
            onDismissed()
            return
        }
        val ad = interstitial ?: run {
            onDismissed()
            return
        }
        // Exactly-once guard: since GMA v21 a failed show can invoke BOTH
        // onAdFailedToShowFullScreenContent and onAdDismissedFullScreenContent, and callers hold
        // game flow on this continuation (the Monetization contract promises one invocation).
        var fired = false
        fun fireOnDismissed() {
            if (fired) return
            fired = true
            onDismissed()
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = fireOnDismissed()
            override fun onAdFailedToShowFullScreenContent(error: AdError) = fireOnDismissed()
        }
        ad.show(activity)
        interstitial = null
        loadInterstitial() // preload the next one
    }

    override fun dispose() {
        disposed = true
        mainHandler.removeCallbacksAndMessages(null) // no reconnect may fire on a dead client
        interstitial = null
        if (billingClient.isReady) billingClient.endConnection()
    }

    private companion object {
        const val INITIAL_RECONNECT_DELAY_MILLIS = 1_000L
        const val MAX_RECONNECT_DELAY_MILLIS = 60_000L

        /** How long consent waits for billing's entitlement verdict before proceeding. */
        const val ENTITLEMENT_WAIT_MILLIS = 2_000L
        const val PREFS_NAME = "cardkit_monetization_play"
        const val KEY_REMOVE_ADS_ENTITLED = "remove_ads_entitled"
    }
}
