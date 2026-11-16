package com.networkcontrol.android.kotlin.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 生成 Baseline Profile：冷啟動後依次打開各分頁，涵蓋使用者最常走的路徑。
 * 執行：`ANDROID_SERIAL=emulator-5554 ./gradlew :app:generateReleaseBaselineProfile`
 * （開發機上可能同時連著實機，務必指定模擬器）。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startupAndTabs() = rule.collect(
        packageName = "com.networkcontrol.android.kotlin",
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 10_000)
        // 底部導覽：依序點擊（文字依語言不同，用位置點擊比較穩定）
        val navY = (device.displayHeight * 0.95).toInt()
        val width = device.displayWidth
        for (index in listOf(1, 2, 3, 4, 0)) {
            device.click(width * (2 * index + 1) / 10, navY)
            device.waitForIdle(2_000)
        }
    }
}
