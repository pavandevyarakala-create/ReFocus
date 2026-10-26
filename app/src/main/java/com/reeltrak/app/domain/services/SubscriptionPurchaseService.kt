package com.reeltrak.app.domain.services

import com.reeltrak.app.domain.models.SubscriptionPlan
import kotlinx.coroutines.flow.StateFlow

sealed interface CheckoutState {
    data object Idle : CheckoutState
    data class CreatingOrder(val plan: SubscriptionPlan) : CheckoutState
    data class CheckoutInProgress(val orderId: String) : CheckoutState
    data object Verifying : CheckoutState
    data class MandatePending(val message: String) : CheckoutState
    data class Success(val message: String) : CheckoutState
    data class Error(val message: String) : CheckoutState
}

/**
 * Service interface for subscription checkout and payment gateway orchestration.
 */
interface SubscriptionPurchaseService {
    val checkoutState: StateFlow<CheckoutState>

    /**
     * Pre-warms checkout dependencies/assets in background.
     */
    fun preload() {}

    /**
     * Initiates the purchase/checkout flow for a chosen plan.
     */
    suspend fun initiatePurchase(plan: SubscriptionPlan)

    /**
     * Resets checkout state back to Idle.
     */
    fun resetCheckoutState()
}
