package com.networkcontrol.android.kotlin.data.db

import android.database.sqlite.SQLiteDatabase

internal fun SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> Unit) {
    beginTransaction()
    try {
        block()
        setTransactionSuccessful()
    } finally {
        endTransaction()
    }
}
