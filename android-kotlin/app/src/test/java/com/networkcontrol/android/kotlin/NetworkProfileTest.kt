package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.core.model.AutoActivateCondition
import com.networkcontrol.android.kotlin.core.model.NetworkContext
import com.networkcontrol.android.kotlin.core.model.NetworkKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkProfileTest {
    @Test
    fun wifiConditionMatchesKindAndSsid() {
        val condition = AutoActivateCondition(kind = NetworkKind.Wifi, ssids = listOf("Office"))
        assertTrue(condition.matches(NetworkContext(kind = NetworkKind.Wifi, ssid = "Office")))
        assertFalse(condition.matches(NetworkContext(kind = NetworkKind.Wifi, ssid = "Home")))
        assertFalse(condition.matches(NetworkContext(kind = NetworkKind.Cellular)))
    }

    @Test
    fun simConditionMatchesSubscription() {
        val condition = AutoActivateCondition(
            kind = NetworkKind.Cellular,
            subscriptionIds = listOf(2),
        )
        assertTrue(
            condition.matches(
                NetworkContext(kind = NetworkKind.Cellular, subscriptionId = 2, simSlotIndex = 1),
            ),
        )
        assertFalse(
            condition.matches(
                NetworkContext(kind = NetworkKind.Cellular, subscriptionId = 1, simSlotIndex = 0),
            ),
        )
        assertFalse(condition.matches(NetworkContext(kind = NetworkKind.Cellular)))
    }

    @Test
    fun emptyConditionNeverAutoSwitches() {
        assertFalse(AutoActivateCondition().matches(NetworkContext(kind = NetworkKind.Wifi)))
    }

    @Test
    fun fingerprintIncludesSim() {
        val first = NetworkContext(kind = NetworkKind.Cellular, subscriptionId = 1)
        val second = NetworkContext(kind = NetworkKind.Cellular, subscriptionId = 2)
        assertTrue(first.fingerprint != second.fingerprint)
    }
}
