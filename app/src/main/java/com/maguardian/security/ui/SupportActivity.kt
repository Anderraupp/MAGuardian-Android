package com.maguardian.security.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.maguardian.security.R

class SupportActivity : GuardianPageActivity() {
    override val pageTitle = "Atendimento"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content.addView(FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(80)).apply { bottomMargin = dp(12) }
            addView(ImageView(this@SupportActivity).apply {
                setImageResource(R.drawable.guardian_waves)
                scaleType = ImageView.ScaleType.FIT_XY
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = FrameLayout.LayoutParams(-1, dp(140), Gravity.CENTER)
            })
            addView(ImageView(this@SupportActivity).apply {
                setImageResource(R.drawable.logo); contentDescription = "Logo M&A Guardian"
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER)
            })
        })
        content.addView(label("Como podemos ajudar?", true).apply { gravity = Gravity.CENTER })
        content.addView(label("Conte com nossa equipe para tirar dúvidas e receber orientações sobre o aplicativo.").apply { gravity = Gravity.CENTER })
        content.addView(label("Fale com Anderson ou Vanessa").apply { gravity = Gravity.CENTER })
        fun channel(title: String, address: String, icon: Int, button: String, click: () -> Unit) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = getDrawable(R.drawable.guardian_card)
                setPadding(dp(14), dp(14), dp(14), dp(4))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(4) }
            }
            card.addView(label(title, true).apply { setCompoundDrawablesWithIntrinsicBounds(icon, 0, 0, 0); compoundDrawablePadding = dp(10) })
            card.addView(label(address).apply { setTextIsSelectable(true) })
            card.addView(action(button, click).apply {
                if (title == "WhatsApp") {
                    setBackgroundResource(R.drawable.guardian_subscribe)
                    setTextColor(android.graphics.Color.WHITE)
                }
            })
            content.addView(card)
        }
        val whatsapp = getString(R.string.guardian_support_whatsapp)
        val email = getString(R.string.guardian_support_email)
        channel("WhatsApp", getString(R.string.guardian_support_whatsapp_display), R.drawable.guardian_ic_phone, "Conversar no WhatsApp  →") {
            GuardianExternal.web(this, "https://wa.me/$whatsapp")
        }
        channel("E-mail", email, R.drawable.guardian_ic_mail, "Enviar e-mail  →") {
            GuardianExternal.open(this, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email?subject=${Uri.encode("M&A Guardian — atendimento")}")), email)
        }
        channel("Instagram", "@meacelulareseacessorios", R.drawable.guardian_ic_instagram, "Visitar Instagram  →") {
            GuardianExternal.web(this, "https://www.instagram.com/meacelulareseacessorios/")
        }
        content.addView(label("Para agilizar o atendimento, descreva sua dúvida e, se possível, envie uma captura da tela.").apply {
            textSize = 12f; setPadding(0, dp(14), 0, dp(8))
        })
    }
}
