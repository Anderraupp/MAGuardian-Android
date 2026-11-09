package com.maguardian.security.data

/**
 * A declared permission describes capability, not observed malicious behaviour.
 * Keep these reviews separate from alerts and count installed packages only once.
 */
internal class ManualScanReport {
    data class Finding(
        val packageName: String,
        val appName: String,
        val reason: String,
        val permissionOnly: Boolean = false
    )

    private val findings = linkedMapOf<String, Finding>()

    fun record(finding: Finding) {
        val existing = findings[finding.packageName]
        if (existing == null || (existing.permissionOnly && !finding.permissionOnly)) {
            findings[finding.packageName] = finding
        }
    }

    val alertCount: Int get() = findings.values.count { !it.permissionOnly }
    val permissionReviewCount: Int get() = findings.values.count { it.permissionOnly }

    fun diagnostics(): String = buildString {
        append("Relatório local do APK de teste. Nada é enviado.\n")
        append("Apps sinalizados: $alertCount\n")
        append("Revisões de permissões, fora da contagem de ameaças: $permissionReviewCount\n\n")
        append("Indícios não confirmam, por si só, que um app está infectado.\n\n")
        if (findings.isEmpty()) append("Nenhum indício encontrado nesta análise.")
        findings.values.forEach {
            append(if (it.permissionOnly) "PERMISSÕES — NÃO É CONFIRMAÇÃO DE AMEAÇA\n" else "SINALIZADO PARA REVISÃO\n")
            append("${it.appName}\n${it.packageName}\nMotivo: ${it.reason}\n\n")
        }
    }

    companion object {
        fun canArchiveLegacyPermissionReview(
            packageName: String,
            status: String,
            description: String,
            reassessedWithoutAlert: Set<String>
        ): Boolean = status == "detected" &&
            packageName in reassessedWithoutAlert &&
            isLegacyPermissionOnly(description)

        // These descriptions were emitted by the older permission-only rule.
        // Only archive them after this same installed package is reassessed and
        // neither the database nor the other existing checks identify an alert.
        fun isLegacyPermissionOnly(description: String): Boolean =
            description.startsWith("App com permissão de overlay (SYSTEM_ALERT_WINDOW) e início automático") ||
            description.startsWith("App com permissão de overlay e acesso à localização") ||
            description.startsWith("App com permissão para exibir janelas sobre outros apps (SYSTEM_ALERT_WINDOW)")
    }
}
