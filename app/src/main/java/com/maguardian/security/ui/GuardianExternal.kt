package com.maguardian.security.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

internal object GuardianExternal {
    const val ABOUT = "https://guardian.meacelulareseacessorios.com.br/"
    const val TERMS = "https://anderraupp.github.io/MAGuardian-Android/terms.html"
    const val PRIVACY = "https://sites.google.com/view/politicaprivacidademaguardian"
    fun web(activity: AppCompatActivity, url: String) = open(activity, Intent(Intent.ACTION_VIEW, Uri.parse(url)), url)
    fun open(activity: AppCompatActivity, intent: Intent, fallbackText: String) {
        try { activity.startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) {
            AlertDialog.Builder(activity).setTitle("Abrir em outro aplicativo")
                .setMessage("Nenhum aplicativo compatível foi encontrado.\n\n$fallbackText")
                .setPositiveButton("Copiar") { _, _ ->
                    (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("M&A Guardian", fallbackText))
                    Toast.makeText(activity, "Copiado.", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("Voltar", null).show()
        }
    }
}
