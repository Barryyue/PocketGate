package com.networkcontrol.android.kotlin.data.repository

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.networkcontrol.android.kotlin.core.model.ActivityBucket
import com.networkcontrol.android.kotlin.core.model.ActivitySummary
import com.networkcontrol.android.kotlin.core.model.ActivitySummaryQuery
import com.networkcontrol.android.kotlin.core.model.ActivityTopItem
import com.networkcontrol.android.kotlin.core.model.AppActivity
import com.networkcontrol.android.kotlin.core.model.AppTraffic
import com.networkcontrol.android.kotlin.core.model.ConnectionEvent
import com.networkcontrol.android.kotlin.core.model.Direction
import com.networkcontrol.android.kotlin.core.model.L4Protocol
import com.networkcontrol.android.kotlin.core.model.LogEntry
import com.networkcontrol.android.kotlin.core.model.LogQuery
import com.networkcontrol.android.kotlin.core.model.NetworkKey
import com.networkcontrol.android.kotlin.core.model.NetworkKind
import com.networkcontrol.android.kotlin.core.model.TrafficBucket
import com.networkcontrol.android.kotlin.core.model.TrafficSummary
import com.networkcontrol.android.kotlin.core.model.Verdict
import com.networkcontrol.android.kotlin.core.model.VerdictAction
import com.networkcontrol.android.kotlin.core.model.VerdictReason
import com.networkcontrol.android.kotlin.core.storage.ActivityStore
import com.networkcontrol.android.kotlin.core.storage.TrafficStore
import com.networkcontrol.android.kotlin.data.db.ActivityDatabase
import com.networkcontrol.android.kotlin.data.db.inTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress

class SqliteActivityStore(private val db: ActivityDatabase) : ActivityStore {
    override suspend fun write(event: ConnectionEvent) = withContext(Dispatchers.IO) {
        val minute = event.timestamp / 60_000 * 60_000
        val appId = event.appId ?: -1L
        val appPath = event.appPath
        val values = ContentValues().apply {
            put("minute_ts", minute)
            put("app_id", appId)
            put("app_path", appPath)
            put("app_name", event.appName)
            put("direction", event.direction.code)
            put("protocol", event.protocol.number)
            put("remote_ip", event.remote.address.hostAddress)
            put("remote_port", event.remote.port)
            put("local_ip", event.local.address.hostAddress)
            put("local_port", event.local.port)
            put("hostname", event.hostname)
            put("verdict", event.verdict.action.code)
            put("reason", event.verdict.reason.code)
            put("rule_id", event.verdict.ruleId)
            put("first_ts", event.timestamp)
            put("last_ts", event.timestamp)
        }
        db.writableDatabase.inTransaction {
            val selection =
                "minute_ts=? AND app_id=? AND ifnull(app_path,'')=? AND direction=? AND protocol=? AND remote_ip=? AND remote_port=? AND verdict=? AND reason=?"
            val args = arrayOf<Any?>(
                minute,
                appId,
                appPath.orEmpty(),
                event.direction.code,
                event.protocol.number,
                event.remote.address.hostAddress,
                event.remote.port,
                event.verdict.action.code,
                event.verdict.reason.code,
            )
            val existingId = rawQuery("SELECT id FROM connection_log WHERE $selection LIMIT 1;", args.map { it.toString() }.toTypedArray()).use {
                if (it.moveToFirst()) it.getLong(0) else null
            }
            if (existingId == null) {
                values.put("count", 1)
                insert("connection_log", null, values)
            } else {
                val update = ContentValues().apply {
                    put("count", 1)
                    put("last_ts", event.timestamp)
                    put("local_ip", event.local.address.hostAddress)
                    put("local_port", event.local.port)
                    put("hostname", event.hostname)
                    put("rule_id", event.verdict.ruleId)
                }
                execSQL(
                    """
                    UPDATE connection_log SET count = count + 1, last_ts = MAX(last_ts, ?),
                        local_ip = ?, local_port = ?, hostname = COALESCE(?, hostname),
                        rule_id = COALESCE(?, rule_id)
                    WHERE id = ?;
                    """.trimIndent(),
                    arrayOf<Any?>(
                        event.timestamp,
                        event.local.address.hostAddress,
                        event.local.port,
                        event.hostname,
                        event.verdict.ruleId,
                        existingId,
                    ),
                )
                update.clear()
            }
        }
    }

    override suspend fun query(query: LogQuery): List<LogEntry> = withContext(Dispatchers.IO) {
        val sql = StringBuilder(
            """
            SELECT id, first_ts, last_ts, app_id, app_path, app_name, direction, protocol,
                   remote_ip, remote_port, local_ip, local_port, hostname, verdict, reason, rule_id, count
            FROM connection_log WHERE 1 = 1
            """.trimIndent(),
        )
        val args = mutableListOf<String>()
        query.from?.let { sql.append(" AND last_ts >= ?"); args += it.toString() }
        query.to?.let { sql.append(" AND first_ts <= ?"); args += it.toString() }
        query.appId?.let { sql.append(" AND app_id = ?"); args += it.toString() }
        query.action?.let { sql.append(" AND verdict = ?"); args += it.code.toString() }
        query.reason?.let { sql.append(" AND reason = ?"); args += it.code.toString() }
        query.keyword?.trim()?.takeIf { it.isNotEmpty() }?.let {
            sql.append(" AND (app_path LIKE ? ESCAPE '\\' OR app_name LIKE ? ESCAPE '\\' OR hostname LIKE ? ESCAPE '\\' OR remote_ip LIKE ? ESCAPE '\\')")
            val escaped = "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            repeat(4) { args += escaped }
        }
        query.beforeId?.let { sql.append(" AND id < ?"); args += it.toString() }
        sql.append(" ORDER BY id DESC LIMIT ?;")
        args += query.limit.coerceIn(1, 5000).toString()

        db.readableDatabase.rawQuery(sql.toString(), args.toTypedArray()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val appId = cursor.getLong(3)
                    add(
                        LogEntry(
                            id = cursor.getLong(0),
                            firstTs = cursor.getLong(1),
                            lastTs = cursor.getLong(2),
                            appId = if (appId < 0) null else appId,
                            appPath = cursor.textAt(4),
                            appName = cursor.textAt(5),
                            direction = Direction.fromCode(cursor.getInt(6)),
                            protocol = L4Protocol.fromNumber(cursor.getInt(7)),
                            remoteIp = cursor.getString(8),
                            remotePort = cursor.getInt(9),
                            hostname = cursor.textAt(12),
                            verdict = Verdict(
                                VerdictAction.fromCode(cursor.getInt(13)),
                                VerdictReason.fromCode(cursor.getInt(14)),
                                cursor.textAt(15),
                            ),
                            count = cursor.getInt(16),
                            local = if (cursor.isNull(10) || cursor.isNull(11)) {
                                null
                            } else {
                                runCatching {
                                    InetSocketAddress(
                                        InetAddress.getByName(cursor.getString(10)),
                                        cursor.getInt(11),
                                    )
                                }.getOrNull()
                            },
                        ),
                    )
                }
            }
        }
    }

    override suspend fun summarize(query: ActivitySummaryQuery): ActivitySummary = withContext(Dispatchers.IO) {
        val bucketMs = query.bucketMillis.coerceAtLeast(60_000)
        val from = query.from / 60_000 * 60_000
        val to = maxOf(from, query.to)
        val bucketCount = (((to - from + bucketMs - 1) / bucketMs).toInt()).coerceIn(1, 10_000)
        val top = query.topCount.coerceIn(0, 100)
        val database = db.readableDatabase

        val allowed = LongArray(bucketCount)
        val blocked = LongArray(bucketCount)
        database.rawQuery(
            """
            SELECT (minute_ts - ?) / ?, verdict, SUM(count)
            FROM connection_log WHERE minute_ts >= ? AND minute_ts < ? GROUP BY 1, 2;
            """.trimIndent(),
            arrayOf(from.toString(), bucketMs.toString(), from.toString(), to.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val index = cursor.getLong(0).toInt()
                if (index in 0 until bucketCount) {
                    if (cursor.getInt(1) == VerdictAction.Allow.code) {
                        allowed[index] += cursor.getLong(2)
                    } else {
                        blocked[index] += cursor.getLong(2)
                    }
                }
            }
        }

        var appCount = 0
        var destinationCount = 0
        database.rawQuery(
            """
            SELECT COUNT(DISTINCT app_id || ':' || CASE WHEN app_id < 0 THEN COALESCE(app_path, '') ELSE '' END),
                   COUNT(DISTINCT COALESCE(hostname, remote_ip))
            FROM connection_log WHERE minute_ts >= ? AND minute_ts < ?;
            """.trimIndent(),
            arrayOf(from.toString(), to.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                appCount = cursor.getInt(0)
                destinationCount = cursor.getInt(1)
            }
        }

        val topApps = mutableListOf<ActivityTopItem>()
        if (top > 0) {
            database.rawQuery(
                """
                SELECT app_id, CASE WHEN app_id < 0 THEN COALESCE(app_path, '') ELSE '' END AS k,
                       MAX(app_name), MAX(app_path),
                       SUM(CASE WHEN verdict = 0 THEN count ELSE 0 END),
                       SUM(CASE WHEN verdict = 0 THEN 0 ELSE count END)
                FROM connection_log WHERE minute_ts >= ? AND minute_ts < ?
                GROUP BY app_id, k ORDER BY SUM(count) DESC LIMIT ?;
                """.trimIndent(),
                arrayOf(from.toString(), to.toString(), top.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val appId = cursor.getLong(0)
                    val name = cursor.textAt(2)
                    val path = cursor.textAt(3)
                    topApps += ActivityTopItem(
                        key = if (appId >= 0) "app:$appId" else "path:${path.orEmpty()}",
                        label = name?.takeIf { it.isNotBlank() } ?: path.orEmpty(),
                        appId = if (appId >= 0) appId else null,
                        allowed = cursor.getLong(4),
                        blocked = cursor.getLong(5),
                        path = path,
                    )
                }
            }
        }

        val topDestinations = mutableListOf<ActivityTopItem>()
        if (top > 0) {
            database.rawQuery(
                """
                SELECT COALESCE(hostname, remote_ip) AS d,
                       SUM(CASE WHEN verdict = 0 THEN count ELSE 0 END),
                       SUM(CASE WHEN verdict = 0 THEN 0 ELSE count END)
                FROM connection_log WHERE minute_ts >= ? AND minute_ts < ?
                GROUP BY d ORDER BY SUM(count) DESC LIMIT ?;
                """.trimIndent(),
                arrayOf(from.toString(), to.toString(), top.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val label = cursor.getString(0)
                    topDestinations += ActivityTopItem(
                        key = label,
                        label = label,
                        appId = null,
                        allowed = cursor.getLong(1),
                        blocked = cursor.getLong(2),
                    )
                }
            }
        }

        ActivitySummary(
            allowed = allowed.sum(),
            blocked = blocked.sum(),
            appCount = appCount,
            destinationCount = destinationCount,
            timeline = List(bucketCount) {
                ActivityBucket(from + it * bucketMs, allowed[it], blocked[it])
            },
            topApps = topApps,
            topDestinations = topDestinations,
        )
    }

    override suspend fun listAppActivity(from: Long, to: Long, limit: Int): List<AppActivity> =
        withContext(Dispatchers.IO) {
            db.readableDatabase.rawQuery(
                """
                SELECT app_id, CASE WHEN app_id < 0 THEN COALESCE(app_path, '') ELSE '' END AS k,
                       MAX(app_path), MAX(app_name),
                       SUM(CASE WHEN verdict = 0 THEN count ELSE 0 END),
                       SUM(CASE WHEN verdict = 0 THEN 0 ELSE count END),
                       MAX(last_ts)
                FROM connection_log WHERE minute_ts >= ? AND minute_ts < ?
                GROUP BY app_id, k ORDER BY MAX(last_ts) DESC LIMIT ?;
                """.trimIndent(),
                arrayOf((from / 60_000 * 60_000).toString(), to.toString(), limit.coerceIn(1, 5000).toString()),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val appId = cursor.getLong(0)
                        add(
                            AppActivity(
                                appId = if (appId >= 0) appId else null,
                                appPath = cursor.textAt(2),
                                appName = cursor.textAt(3),
                                allowed = cursor.getLong(4),
                                blocked = cursor.getLong(5),
                                lastSeen = cursor.getLong(6),
                            ),
                        )
                    }
                }
            }
        }

    override suspend fun countBlockedSince(since: Long): Long = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(count), 0) FROM connection_log WHERE verdict = 1 AND last_ts >= ?;",
            arrayOf(since.toString()),
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }

    override suspend fun applyRetention(cutoff: Long) {
        withContext(Dispatchers.IO) {
            db.writableDatabase.delete("connection_log", "last_ts < ?", arrayOf(cutoff.toString()))
        }
    }
}

class SqliteTrafficStore(private val db: ActivityDatabase) : TrafficStore {
    private var lastCleanup = 0L

    override suspend fun add(
        appId: Long,
        minute: Long,
        bytesIn: Long,
        bytesOut: Long,
        networkKind: NetworkKind,
        subscriptionId: Int?,
    ) =
        withContext(Dispatchers.IO) {
            if (bytesIn == 0L && bytesOut == 0L) return@withContext
            val database = db.writableDatabase
            val networkId = subscriptionId ?: -1
            database.inTransaction {
                listOf(
                    "traffic_minute" to 60_000L,
                    "traffic_hour" to 3_600_000L,
                    "traffic_day" to 86_400_000L,
                ).forEach { (table, unit) ->
                    val ts = minute / unit * unit
                    insertWithOnConflict(
                        table,
                        null,
                        ContentValues().apply {
                            put("app_id", appId)
                            put("ts", ts)
                            put("bytes_in", 0)
                            put("bytes_out", 0)
                        },
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )
                    execSQL(
                        "UPDATE $table SET bytes_in = bytes_in + ?, bytes_out = bytes_out + ? WHERE app_id = ? AND ts = ?;",
                        arrayOf<Any?>(bytesIn, bytesOut, appId, ts),
                    )
                    insertWithOnConflict(
                        "traffic_network",
                        null,
                        ContentValues().apply {
                            put("app_id", appId)
                            put("ts", ts)
                            put("unit", unit)
                            put("network_kind", networkKind.code)
                            put("subscription_id", networkId)
                            put("bytes_in", 0)
                            put("bytes_out", 0)
                        },
                        SQLiteDatabase.CONFLICT_IGNORE,
                    )
                    execSQL(
                        """
                        UPDATE traffic_network SET bytes_in = bytes_in + ?, bytes_out = bytes_out + ?
                        WHERE app_id = ? AND ts = ? AND unit = ? AND network_kind = ? AND subscription_id = ?;
                        """.trimIndent(),
                        arrayOf<Any?>(bytesIn, bytesOut, appId, ts, unit, networkKind.code, networkId),
                    )
                }
            }
            val now = System.currentTimeMillis()
            if (now - lastCleanup >= 3_600_000L) {
                lastCleanup = now
                database.delete("traffic_minute", "ts < ?", arrayOf((now - 3 * 86_400_000L).toString()))
                database.delete(
                    "traffic_network",
                    "unit = ? AND ts < ?",
                    arrayOf("60000", (now - 3 * 86_400_000L).toString()),
                )
            }
        }

    override suspend fun summarize(from: Long, to: Long, bucketMillis: Long, topCount: Int): TrafficSummary =
        withContext(Dispatchers.IO) {
            val table = tableFor(from, to)
            val unit = if (table == "traffic_minute") 60_000L else 3_600_000L
            val bucket = bucketMillis.coerceAtLeast(unit)
            val safeTo = maxOf(from, to)
            val bucketCount = (((safeTo - from + bucket - 1) / bucket).toInt()).coerceIn(1, 10_000)
            val bytesIn = LongArray(bucketCount)
            val bytesOut = LongArray(bucketCount)
            val database = db.readableDatabase
            database.rawQuery(
                "SELECT (ts - ?) / ?, SUM(bytes_in), SUM(bytes_out) FROM $table WHERE ts >= ? AND ts < ? GROUP BY 1;",
                arrayOf(from.toString(), bucket.toString(), from.toString(), safeTo.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val index = cursor.getLong(0).toInt()
                    if (index in 0 until bucketCount) {
                        bytesIn[index] += cursor.getLong(1)
                        bytesOut[index] += cursor.getLong(2)
                    }
                }
            }

            val top = mutableListOf<AppTraffic>()
            if (topCount > 0) {
                database.rawQuery(
                    "SELECT app_id, SUM(bytes_in), SUM(bytes_out) FROM $table WHERE ts >= ? AND ts < ? GROUP BY app_id ORDER BY SUM(bytes_in) + SUM(bytes_out) DESC LIMIT ?;",
                    arrayOf(from.toString(), safeTo.toString(), topCount.coerceIn(1, 100).toString()),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        top += AppTraffic(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))
                    }
                }
            }

            TrafficSummary(
                bytesIn = bytesIn.sum(),
                bytesOut = bytesOut.sum(),
                timeline = List(bucketCount) { TrafficBucket(from + it * bucket, bytesIn[it], bytesOut[it]) },
                topApps = top,
            )
        }

    override suspend fun byApp(from: Long, to: Long): Map<Long, AppTraffic> = withContext(Dispatchers.IO) {
        val table = tableFor(from, to)
        db.readableDatabase.rawQuery(
            "SELECT app_id, SUM(bytes_in), SUM(bytes_out) FROM $table WHERE ts >= ? AND ts < ? GROUP BY app_id;",
            arrayOf(from.toString(), to.toString()),
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(cursor.getLong(0), AppTraffic(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2)))
                }
            }
        }
    }

    override suspend fun byNetwork(from: Long, to: Long): Map<NetworkKey, Pair<Long, Long>> =
        withContext(Dispatchers.IO) {
            val unit = if (to - from <= 2 * 86_400_000L) 60_000L else 3_600_000L
            db.readableDatabase.rawQuery(
                """
                SELECT network_kind, subscription_id, SUM(bytes_in), SUM(bytes_out)
                FROM traffic_network WHERE ts >= ? AND ts < ? AND unit = ?
                GROUP BY network_kind, subscription_id;
                """.trimIndent(),
                arrayOf(from.toString(), to.toString(), unit.toString()),
            ).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        val kind = NetworkKind.fromCode(cursor.getInt(0))
                        val subscription = cursor.getInt(1).takeIf { it >= 0 }
                        put(NetworkKey(kind, subscription), cursor.getLong(2) to cursor.getLong(3))
                    }
                }
            }
        }

    private fun tableFor(from: Long, to: Long): String =
        if (to - from <= 2 * 86_400_000L && from >= System.currentTimeMillis() - 3 * 86_400_000L) {
            "traffic_minute"
        } else {
            "traffic_hour"
        }
}

private fun Cursor.textAt(index: Int): String? = if (isNull(index)) null else getString(index)
