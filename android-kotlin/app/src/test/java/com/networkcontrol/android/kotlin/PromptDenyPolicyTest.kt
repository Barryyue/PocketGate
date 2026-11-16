package com.networkcontrol.android.kotlin

import com.networkcontrol.android.kotlin.core.model.RuleLifetime
import com.networkcontrol.android.kotlin.platform.prompt.PromptDenyPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class PromptDenyPolicyTest {
    @Test
    fun denyLastsUntilAppExitByDefault() {
        assertEquals(RuleLifetime.UntilAppExit, PromptDenyPolicy.lifetime(dontAskAgain = false, denyPermanentByDefault = false))
    }

    @Test
    fun dontAskAgainMakesDenyPermanent() {
        assertEquals(RuleLifetime.Permanent, PromptDenyPolicy.lifetime(dontAskAgain = true, denyPermanentByDefault = false))
    }

    @Test
    fun settingMakesDenyPermanentWithoutCheckbox() {
        assertEquals(RuleLifetime.Permanent, PromptDenyPolicy.lifetime(dontAskAgain = false, denyPermanentByDefault = true))
    }
}

class PromptSheetTextTest {
    @Test
    fun messageNamesBothAppsAndTheNetwork() {
        val tw = com.networkcontrol.android.kotlin.ui.UiStrings("zh-TW")
        val text = com.networkcontrol.android.kotlin.platform.prompt.PromptSheetText.message(
            tw, "NetworkControl", "紅魔商城", com.networkcontrol.android.kotlin.core.model.NetworkKind.Wifi,
        )
        assertEquals("NetworkControl已禁止紅魔商城使用Wi-Fi 網路。是否允許此應用程式使用網路？", text)
    }

    @Test
    fun unknownNetworkFallsBackToGenericWording() {
        val en = com.networkcontrol.android.kotlin.ui.UiStrings("en-US")
        val text = com.networkcontrol.android.kotlin.platform.prompt.PromptSheetText.message(en, "NC", "Shop", null)
        assertEquals("NC has blocked Shop from using the network. Allow this app to use the network?", text)
    }
}
