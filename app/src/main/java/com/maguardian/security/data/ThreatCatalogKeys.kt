package com.maguardian.security.data

/**
 * Public trust anchors embedded in the APK. Generated from trusted-keys.json
 * during operator provisioning, never obtained from the download server.
 * Empty until the operator provisions an independent production signing key.
 */
object ThreatCatalogKeys {
    data class Key(val spkiBase64: String, val minimumVersion: Int,
                   val maximumVersion: Int, val revoked: Boolean)
    val trusted: Map<String, Key> = emptyMap()
}
