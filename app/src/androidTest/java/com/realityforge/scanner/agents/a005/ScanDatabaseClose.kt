package com.realityforge.scanner.agents.a005

import com.realityforge.scanner.data.ScanDatabase

/** Android RoomDatabase is not a Kotlin Closeable on this dependency baseline. */
internal inline fun <T> ScanDatabase.use(block: (ScanDatabase) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }
