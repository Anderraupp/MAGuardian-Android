package com.maguardian.security.data

import org.junit.Assert.*
import org.junit.Test

class ManualScanReportTest {
    private fun finding(pkg: String = "example.app", review: Boolean = false) =
        ManualScanReport.Finding(pkg, "Aplicativo", "Motivo de teste", review)

    @Test fun multipleRulesDoNotMultiplyOneInstalledApp() {
        val report = ManualScanReport()
        repeat(4) { report.record(finding()) }
        assertEquals(1, report.alertCount)
    }

    @Test fun permissionsAloneAreNotCountedAsThreats() {
        val report = ManualScanReport()
        report.record(finding(review = true))
        assertEquals(0, report.alertCount)
        assertEquals(1, report.permissionReviewCount)
    }

    @Test fun strongerEvidenceReplacesPermissionReview() {
        val report = ManualScanReport()
        report.record(finding(review = true))
        report.record(finding())
        report.record(finding(review = true))
        assertEquals(1, report.alertCount)
        assertEquals(0, report.permissionReviewCount)
    }

    @Test fun distinctInstalledPackagesRemainSeparate() {
        val report = ManualScanReport()
        report.record(finding("example.one"))
        report.record(finding("example.two"))
        report.record(finding("example.three", review = true))
        assertEquals(2, report.alertCount)
        assertEquals(1, report.permissionReviewCount)
    }

    @Test fun oldPermissionWarningsAreRecognizedButBehaviourAlertsAreNot() {
        assertTrue(ManualScanReport.isLegacyPermissionOnly(
            "App com permissão de overlay (SYSTEM_ALERT_WINDOW) e início automático no boot — padrão de adware"))
        assertTrue(ManualScanReport.isLegacyPermissionOnly(
            "App com permissão de overlay e acesso à localização — pode exibir anúncios"))
        assertTrue(ManualScanReport.isLegacyPermissionOnly(
            "App com permissão para exibir janelas sobre outros apps (SYSTEM_ALERT_WINDOW) — pode ser fonte de pop-ups"))
        assertFalse(ManualScanReport.isLegacyPermissionOnly("Pop-up observado sobre outro aplicativo"))
        assertFalse(ManualScanReport.isLegacyPermissionOnly("Correspondência de pacote e SHA-256"))
    }

    @Test fun diagnosticDistinguishesReviewsWithoutClaimingInfection() {
        val report = ManualScanReport()
        report.record(finding(review = true))
        assertTrue(report.diagnostics().contains("fora da contagem de ameaças: 1"))
        assertTrue(report.diagnostics().contains("example.app"))
        assertTrue(report.diagnostics().contains("não confirmam"))
    }

    @Test fun archivesOnlyReassessedActivePermissionWarnings() {
        val oldReason = "App com permissão de overlay e acesso à localização — pode exibir anúncios"
        assertTrue(ManualScanReport.canArchiveLegacyPermissionReview(
            "example.app", "detected", oldReason, setOf("example.app")))
        for (status in listOf("kept", "removed", "dismissed")) {
            assertFalse(ManualScanReport.canArchiveLegacyPermissionReview(
                "example.app", status, oldReason, setOf("example.app")))
        }
        // Not reassessed, or still identified by a database/other existing check.
        assertFalse(ManualScanReport.canArchiveLegacyPermissionReview(
            "example.app", "detected", oldReason, emptySet()))
        assertFalse(ManualScanReport.canArchiveLegacyPermissionReview(
            "example.app", "detected", "Pop-up observado sobre outro app", setOf("example.app")))
    }
}
