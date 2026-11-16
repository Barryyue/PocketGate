package com.networkcontrol.android.kotlin.engine.vpn

import java.net.Inet4Address
import java.net.InetAddress
import java.util.TreeMap

/**
 * hev 映射 DNS 的「假位址 → 網域」對照，以及 hev 重啟後的映射重放。
 *
 * 問題：每次重建隧道（切換 UDP、修改轉發設定、更新應用後重新啟動…）hev 都會重啟，映射表清空、
 * 從網段開頭重新分配。應用（Chrome 用系統解析器時固定緩存 60 秒）與既有連線仍然使用舊的假位址，
 * hev 不認得它（或已經分給別的網域），只能拿假位址去叫代理連線，連線卡住直到逾時。
 *
 * 做法：hev 按查詢順序從網段第一個位址依序分配（實測 `100.64.0.0`、`.1`、`.2`…），所以 hev 啟動時把已知網域
 * 按原位址順序重新查詢一遍，就能得到完全相同的映射。重放期間：
 * - 發往「還沒恢復」的假位址的新連線先丟棄（應用約 1 秒後重送 SYN）；
 * - 重放結束後，發往 hev 不認識的假位址的新連線直接拒絕，應用立即失敗並重新解析，而不是卡住數十秒。
 *
 * 只處理 IPv4（hev 的映射網段是 IPv4）。執行緒：所有方法以物件鎖同步。
 */
class FakeIpMappings(
    network: InetAddress = InetAddress.getByName(ForwardingDefaults.MAP_NETWORK),
    netmask: InetAddress = InetAddress.getByName(ForwardingDefaults.MAP_NETMASK),
    private val capacity: Int = ForwardingDefaults.MAP_CACHE_SIZE,
) {
    enum class State {
        /** 不是映射網段的位址，或尚未開始追蹤（沒有經過 hev 啟動）。 */
        NotFake,

        /** hev 目前認得的映射位址。 */
        Known,

        /** 重放中、尚未恢復的位址：暫時丟棄新連線。 */
        Pending,

        /** hev 不認得的映射位址：拒絕新連線。 */
        Unknown,
    }

    private val base = toLong(network)
    private val mask = toLong(netmask)

    /** 位址（無號 32 位）→ 網域；跨 hev 重啟保留，作為重放的依據。 */
    private val known = TreeMap<Long, String>()

    /** 本次 hev 已確認的位址；只有 [sessionActive] 時才用來判斷 [State.Unknown]。 */
    private val confirmed = HashSet<Long>()

    /** 重放中尚未確認的位址 → 預期網域。 */
    private val pending = HashMap<Long, String>()
    private var sessionActive = false

    @get:Synchronized
    val replaying: Boolean get() = pending.isNotEmpty()

    fun isFake(address: InetAddress): Boolean = address is Inet4Address && isFakeLong(toLong(address))

    /** 記錄 hev 映射 DNS 回覆中的 A 記錄。@return 若是重放的回覆但位址與預期不同，回傳 false（重放已中止）。 */
    @Synchronized
    fun record(name: String, address: InetAddress): Boolean {
        if (address !is Inet4Address) return true
        val key = toLong(address)
        if (!isFakeLong(key)) return true
        // 位址被 hev 重新分配（快取已滿時）：舊網域讓出這個位址
        known[key] = name
        while (known.size > capacity) known.pollFirstEntry()
        confirmed += key
        val expected = pending.remove(key)
        if (expected != null && !expected.equals(name, ignoreCase = true)) {
            abortLocked()
            return false
        }
        return true
    }

    /**
     * hev 剛啟動：回傳要按順序重新查詢的網域（只取從網段開頭起連續的一段，有缺口就無法保證位址相同）。
     * 呼叫後即開始新的追蹤期。
     */
    @Synchronized
    fun beginSession(): List<String> {
        sessionActive = true
        confirmed.clear()
        pending.clear()
        val plan = ArrayList<String>()
        // hev 從網段第一個位址（100.64.0.0）開始分配
        var next = base
        for ((key, name) in known) {
            if (key != next) break
            plan += name
            pending[key] = name
            next++
        }
        // 缺口之後的舊映射無法恢復，不再當作已知
        known.tailMap(next, true).clear()
        return plan
    }

    /** 重放的查詢被放棄（多次重送仍無回覆）：中止重放，未恢復的位址改為 [State.Unknown]。 */
    @Synchronized
    fun abort() = abortLocked()

    @Synchronized
    fun state(address: InetAddress): State {
        if (address !is Inet4Address) return State.NotFake
        val key = toLong(address)
        if (!isFakeLong(key) || !sessionActive) return State.NotFake
        return when {
            key in confirmed -> State.Known
            key in pending -> State.Pending
            else -> State.Unknown
        }
    }

    private fun abortLocked() {
        pending.keys.forEach { known.remove(it) }
        pending.clear()
    }

    private fun isFakeLong(key: Long): Boolean = (key and mask) == (base and mask)

    private fun toLong(address: InetAddress): Long {
        val bytes = address.address
        return ((bytes[0].toLong() and 0xFF) shl 24) or ((bytes[1].toLong() and 0xFF) shl 16) or
            ((bytes[2].toLong() and 0xFF) shl 8) or (bytes[3].toLong() and 0xFF)
    }

    companion object {
        /** 重放查詢使用的來源埠（hev 的回覆不寫回虛擬網卡）。 */
        const val REPLAY_PORT = 7

        /** 組一個 A 記錄的 DNS 查詢（RD=1）。網域不合法時回傳 null。 */
        fun buildQuery(name: String, transaction: Int): ByteArray? {
            val labels = name.trimEnd('.').split('.')
            if (labels.any { it.isEmpty() || it.length > 63 }) return null
            val out = java.io.ByteArrayOutputStream(name.length + 18)
            out.write(transaction ushr 8 and 0xFF)
            out.write(transaction and 0xFF)
            out.write(0x01); out.write(0x00) // RD
            out.write(0); out.write(1) // QDCOUNT
            repeat(6) { out.write(0) }
            for (label in labels) {
                val bytes = label.toByteArray(Charsets.US_ASCII)
                out.write(bytes.size)
                out.write(bytes)
            }
            out.write(0)
            out.write(0); out.write(1) // A
            out.write(0); out.write(1) // IN
            return out.toByteArray().takeIf { it.size <= 512 }
        }
    }
}
