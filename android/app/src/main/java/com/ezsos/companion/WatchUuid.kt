package com.ezsos.companion

import java.util.UUID

/** Must match package.json pebble.uuid exactly. */
object WatchUuid {
    const val STRING = "cf79bb9f-ab43-4848-81b6-d1c1ae6a9226"
    val value: UUID = UUID.fromString(STRING)
}
