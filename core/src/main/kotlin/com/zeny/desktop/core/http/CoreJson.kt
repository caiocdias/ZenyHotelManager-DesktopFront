package com.zeny.desktop.core.http

import kotlinx.serialization.json.Json

/** O mesmo `Json` no `ContentNegotiation` e no `HttpClientOgen.Base`. */
val CoreJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
