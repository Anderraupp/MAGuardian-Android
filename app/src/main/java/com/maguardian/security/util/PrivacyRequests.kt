package com.maguardian.security.util

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** User-initiated support request only: no deletion or automatic data upload. */
object PrivacyRequests {
    private const val EMAIL = "celulareseacessoriosmea@gmail.com"
    private const val POLICY = "https://sites.google.com/view/politicaprivacidademaguardian#h.m15q7hw5dpdg"

    fun show(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("Solicitar exclusão de conta e dados")
            .setMessage(
                "Envie seu pedido para $EMAIL.\n\n" +
                "Para excluir uma conta administrativa, escreva pelo e-mail cadastrado e informe que deseja excluir a conta do M&A Guardian. " +
                "Para outros dados, descreva quais deseja excluir. Não envie senhas, códigos de acesso ou documentos.\n\n" +
                "O atendimento poderá confirmar sua identidade. Enviar o pedido não apaga dados automaticamente. " +
                "A solicitação não cancela uma assinatura do Google Play nem remove a base compartilhada de ameaças."
            )
            .setPositiveButton("Escrever e-mail") { _, _ ->
                val subject = Uri.encode("M&A Guardian — solicitação de exclusão de conta ou dados")
                try {
                    activity.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$EMAIL?subject=$subject")))
                } catch (_: android.content.ActivityNotFoundException) {
                    copyAddress(activity)
                    Toast.makeText(activity, "Não há app de e-mail disponível. Endereço copiado; envie pelo seu webmail.", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("Copiar e-mail") { _, _ -> copyAddress(activity) }
            .setNegativeButton("Ver instruções") { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(POLICY)))
                } catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(activity, "Não foi possível abrir o navegador. Use o e-mail de atendimento.", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun copyAddress(activity: Activity) {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Atendimento M&A Guardian", EMAIL))
        Toast.makeText(activity, "E-mail de atendimento copiado.", Toast.LENGTH_SHORT).show()
    }
}
