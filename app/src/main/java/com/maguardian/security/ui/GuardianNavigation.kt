package com.maguardian.security.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.maguardian.security.R

/** The external actions are user initiated; no form is submitted automatically. */
internal object GuardianNavigation {
    fun bind(activity: AppCompatActivity, subscribe: (() -> Unit)? = null) {
        val nav = activity.findViewById<BottomNavigationView>(R.id.guardianBottomNav)
        // ScreenInsets owns the safe area once at the outer root; do not pad twice.
        ViewCompat.setOnApplyWindowInsetsListener(nav) { _, insets -> insets }
        nav.selectedItemId = if (activity is SubscriptionActivity)
            R.id.navGuardianSubscription else R.id.navGuardianHome
        fun navigate(id: Int) {
            when (id) {
                R.id.navGuardianHome -> {
                    if (activity is MainActivity) {
                        activity.findViewById<ScrollView>(R.id.guardianScrollContent).smoothScrollTo(0, 0)
                    } else {
                        activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        })
                    }
                }
                R.id.navGuardianAbout -> open(activity, Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://guardian.meacelulareseacessorios.com.br/")))
                R.id.navGuardianContact -> contact(activity)
                R.id.navGuardianSubscription -> if (activity !is SubscriptionActivity) {
                    if (subscribe != null) subscribe()
                    else activity.startActivity(Intent(activity, SubscriptionActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    })
                } else {
                    activity.findViewById<ScrollView>(R.id.guardianScrollContent).smoothScrollTo(0, 0)
                }
            }
        }
        nav.setOnItemSelectedListener { navigate(it.itemId); false }
        nav.setOnItemReselectedListener { navigate(it.itemId) }
    }

    private fun contact(activity: AppCompatActivity) {
        AlertDialog.Builder(activity)
            .setTitle("Fale com o M&A Guardian")
            .setItems(arrayOf("WhatsApp", "E-mail")) { _, which ->
                if (which == 0) {
                    val number = activity.getString(R.string.guardian_support_whatsapp)
                    if (number.isBlank()) {
                        Toast.makeText(activity, "WhatsApp de atendimento ainda não configurado.", Toast.LENGTH_LONG).show()
                    } else open(activity, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number")))
                } else {
                    val email = activity.getString(R.string.guardian_support_email)
                    val intent = Intent(Intent.ACTION_SENDTO,
                        Uri.parse("mailto:$email?subject=${Uri.encode("M&A Guardian — atendimento")}"))
                    try {
                        activity.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        AlertDialog.Builder(activity)
                            .setTitle("E-mail de atendimento")
                            .setMessage("Envie sua mensagem para $email. Nenhum aplicativo de e-mail foi encontrado.")
                            .setPositiveButton("Copiar endereço") { _, _ ->
                                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Atendimento M&A Guardian", email))
                                Toast.makeText(activity, "Endereço copiado.", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("Voltar", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Voltar", null)
            .show()
    }

    private fun open(activity: AppCompatActivity, intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "Nenhum aplicativo disponível para abrir este endereço.", Toast.LENGTH_LONG).show()
        }
    }
}
