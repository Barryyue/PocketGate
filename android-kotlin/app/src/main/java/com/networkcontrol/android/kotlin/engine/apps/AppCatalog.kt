package com.networkcontrol.android.kotlin.engine.apps

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.networkcontrol.android.kotlin.core.model.AppIdentity
import com.networkcontrol.android.kotlin.core.model.CodeSignature
import com.networkcontrol.android.kotlin.core.model.InstalledApp
import java.security.MessageDigest

class AppCatalog(private val context: Context) {
    data class AppListVisibility(
        val installedCount: Int,
        val visibleKnownPackages: Int,
        val launcherCount: Int,
        val androidPackageVisible: Boolean,
        val oemPermissionName: String?,
        val oemPermissionGranted: Boolean,
        val restricted: Boolean,
    )

    private val appContext = context.applicationContext
    private var snapshot: Snapshot? = null
    private val lock = Any()
    var onChange: (() -> Unit)? = null

    private val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            invalidate()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(receiver, filter)
            }
        }
    }

    fun getApps(): List<InstalledApp> = current().apps

    fun find(packageName: String): InstalledApp? = current().byPackage[packageName]

    fun packagesForUid(uid: Int): List<String> {
        current().byUid[uid]?.let { return it }
        // 應用分身（如 vivo / MIUI user 999）與主應用共用 appId = uid % 100_000
        if (uid >= 100_000) current().byUid[uid % 100_000]?.let { return it }
        return runCatching {
            appContext.packageManager.getPackagesForUid(uid)?.sorted().orEmpty()
        }.getOrDefault(emptyList())
    }

    fun invalidate() {
        synchronized(lock) { snapshot = null }
        onChange?.invoke()
    }

    /** 偵測 Android 11+ 的套件可見性（部分廠商 ROM 另有「取得應用程式列表」權限）。 */
    fun visibility(): AppListVisibility {
        val pm = appContext.packageManager
        val installed = runCatching { pm.getInstalledPackages(0).size }.getOrDefault(0)
        val known = listOf(
            "com.android.settings",
            "com.android.systemui",
            "com.android.launcher3",
            "com.google.android.gms",
            "com.android.vending",
        )
        val visibleKnown = known.count { pkg ->
            runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess
        }
        val androidVisible = runCatching { pm.getApplicationInfo("android", 0) }.isSuccess
        val launcherCount = runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .distinct()
                .size
        }.getOrDefault(0)

        val oemPermission = listOf(
            "com.android.permission.GET_INSTALLED_APPS",
            "com.samsung.android.permission.GET_APP_LIST",
        ).firstOrNull { name ->
            runCatching { pm.getPermissionInfo(name, 0) }.isSuccess
        }
        val oemGranted = if (oemPermission == null) {
            true
        } else {
            runCatching {
                pm.checkPermission(oemPermission, appContext.packageName) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        }

        if (android.os.Build.VERSION.SDK_INT < 30) {
            return AppListVisibility(
                installed,
                visibleKnown,
                launcherCount,
                androidVisible,
                oemPermission,
                oemGranted,
                restricted = false,
            )
        }
        val restricted = (oemPermission != null && !oemGranted) ||
            !androidVisible ||
            launcherCount > installed + 10
        return AppListVisibility(
            installed,
            visibleKnown,
            launcherCount,
            androidVisible,
            oemPermission,
            oemGranted,
            restricted,
        )
    }

    fun oemAppListPermission(): String? = visibility().oemPermissionName

    fun dispose() {
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    fun toIdentity(app: InstalledApp): AppIdentity = AppIdentity(
        path = appPath(app.packageName),
        displayName = app.label,
        signature = CodeSignature(
            signerId = app.signerSha256.orEmpty(),
            isPlatformBinary = app.isSystem,
        ),
    )

    private fun current(): Snapshot = synchronized(lock) {
        snapshot ?: load().also { snapshot = it }
    }

    private fun load(): Snapshot {
        val pm = appContext.packageManager
        val packages: List<PackageInfo> = if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(
                    (PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES).toLong(),
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES)
        }

        val apps = ArrayList<InstalledApp>(packages.size)
        packages.forEach { info ->
            val name = info.packageName ?: return@forEach
            val appInfo = info.applicationInfo ?: return@forEach
            if (info.requestedPermissions?.contains(android.Manifest.permission.INTERNET) != true) return@forEach
            val label = runCatching { appInfo.loadLabel(pm)?.toString() }.getOrNull()
            apps += InstalledApp(
                packageName = name,
                uid = appInfo.uid,
                label = label?.takeIf { it.isNotBlank() } ?: name,
                isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                versionCode = info.longVersionCode,
                lastUpdated = info.lastUpdateTime,
                signerSha256 = signerOf(info),
            )
        }
        return Snapshot(apps)
    }

    private fun signerOf(info: PackageInfo): String? {
        val signers = info.signingInfo?.apkContentsSigners ?: return null
        if (signers.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        return signers
            .map { signature ->
                digest.reset()
                digest.digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
            }
            .sorted()
            .joinToString(",")
    }

    private class Snapshot(apps: List<InstalledApp>) {
        val apps: List<InstalledApp> = apps
        val byPackage: Map<String, InstalledApp> = apps.associateBy { it.packageName }
        val byUid: Map<Int, List<String>> = apps.groupBy { it.uid }
            .mapValues { (_, value) -> value.map { it.packageName }.sorted() }
    }

    companion object {
        fun appPath(packageName: String): String = "android-app://$packageName"
    }
}
