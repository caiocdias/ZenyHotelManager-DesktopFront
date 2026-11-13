package com.zeny.desktop.seed.sdk

import com.zeny.desktop.core.config.LocalSettings
import java.util.UUID

/** Preferências só em memória: o seed nunca toca as preferências do usuário do sistema operacional. */
class MemorySettings : LocalSettings {
    override var serverUrl: String? = null
    override var lastLogin: String? = null
    override val deviceId: String = UUID.randomUUID().toString()
}
