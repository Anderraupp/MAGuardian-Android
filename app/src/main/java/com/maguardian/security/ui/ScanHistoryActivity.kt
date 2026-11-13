package com.maguardian.security.ui

import android.os.Bundle
import android.view.View
import com.maguardian.security.data.ActivityJournal
import com.maguardian.security.util.PrefsHelper
import java.text.DateFormat
import java.util.Date

class ScanHistoryActivity : GuardianPageActivity() {
    override val pageTitle = "Histórico de análises"
    private var offset = 0
    private var detailsAllowed = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        detailsAllowed = PrefsHelper.hasFullAccess(this)
        content.addView(label("Eventos reais registrados a partir desta atualização. Os registros anteriores de ameaças continuam sujeitos às regras já existentes do aplicativo."))
        appendPage()
    }
    override fun onResume() {
        super.onResume()
        val allowed = PrefsHelper.hasFullAccess(this)
        if (allowed != detailsAllowed) {
            detailsAllowed = allowed
            offset = 0
            content.removeAllViews()
            content.addView(label("Eventos reais registrados a partir desta atualização."))
            appendPage()
        }
    }
    private fun appendPage() {
        try {
            val events = ActivityJournal.page(this, offset)
            if (offset == 0 && events.isEmpty()) content.addView(label("Nenhuma análise ou ocorrência registrada neste histórico."))
            val unlocked = PrefsHelper.hasFullAccess(this)
            for (event in events) {
                val title = when (event.kind) {
                    "scan" -> "Análise ${event.source} concluída"
                    "removed" -> "Remoção confirmada"
                    else -> "Aplicativo sinalizado"
                }
                val name = if (event.kind == "scan" || !unlocked || event.name.isBlank()) "" else "\n${event.name}"
                content.addView(label("$title\n${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(event.time))}$name").apply {
                    background = getDrawable(com.maguardian.security.R.drawable.guardian_card)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                })
            }
            offset += events.size
            if (events.size == 100) {
                val more = action("Carregar mais") {}
                more.setOnClickListener { more.visibility = View.GONE; appendPage() }
                content.addView(more)
            }
            if (ActivityJournal.incomplete(this)) content.addView(label("Alguns eventos não puderam ser registrados. Este histórico pode estar incompleto."))
        } catch (_: Exception) {
            content.addView(label("Não foi possível ler o histórico local."))
        }
    }
}
