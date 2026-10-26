package com.reeltrak.app.data.services

import android.util.Log
import com.reeltrak.app.domain.models.SubscriptionPlan
import com.reeltrak.app.domain.services.CheckoutState
import com.reeltrak.app.domain.services.SubscriptionPurchaseService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MockSubscriptionPurchaseServiceImpl @Inject constructor() : SubscriptionPurchaseService {

    private val _checkoutState = MutableStateFlow<CheckoutState>(CheckoutState.Idle)
    override val checkoutState: StateFlow<CheckoutState> = _checkoutState.asStateFlow()

    override suspend fun initiatePurchase(plan: SubscriptionPlan) {
        Log.d("SubscriptionPurchase", "Purchase initiated for plan: ${plan.displayName}")
        _checkoutState.value = CheckoutState.CreatingOrder(plan)
    }

    override fun resetCheckoutState() {
        _checkoutState.value = CheckoutState.Idle
    }
}
