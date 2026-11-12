package com.maguardian.security.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.maguardian.security.R
import com.maguardian.security.util.AppNoticeCenter

object GuardianNotices {
    fun bind(activity: AppCompatActivity, subscribe: () -> Unit) {
        activity.findViewById<View>(R.id.btnNotifications).setOnClickListener {
            val notices = AppNoticeCenter.notices(activity)
            AppNoticeCenter.markSeen(activity, notices)
            refresh(activity)
            val density = activity.resources.displayMetrics.density
            val surface = MaterialShapeDrawable(ShapeAppearanceModel.builder()
                .setAllCornerSizes(24f * density).build()).apply {
                fillColor = ColorStateList.valueOf(GuardianAppearance.color(activity, R.color.guardian_card))
                setStroke(density, GuardianAppearance.color(activity, R.color.guardian_line))
            }
            val builder = MaterialAlertDialogBuilder(activity).setBackground(surface)
                .setTitle("Notificações").setNegativeButton("Fechar", null)
            if (notices.isEmpty()) {
                builder.setMessage("Você está em dia. Nenhuma notificação no momento.")
            } else {
                builder.setItems(notices.map { "${it.title}\n${it.body}" }.toTypedArray()) { _, which ->
                    when (notices[which].destination) {
                        AppNoticeCenter.Destination.SUBSCRIPTION -> subscribe()
                        AppNoticeCenter.Destination.PLAY_STORE -> openPlayStore(activity)
                    }
                }
            }
            builder.show()
        }
        refresh(activity)
    }

    fun refresh(activity: AppCompatActivity) {
        val count = AppNoticeCenter.unreadCount(activity)
        activity.findViewById<TextView>(R.id.tvNotificationBadge).apply {
            text = count.toString()
            visibility = if (count > 0) View.VISIBLE else View.GONE
        }
        activity.findViewById<View>(R.id.btnNotifications).contentDescription =
            if (count > 0) "Notificações, $count não lidas" else "Notificações"
    }

    private fun openPlayStore(activity: AppCompatActivity) {
        // Keep the installed app's actual package, including the isolated test package.
        val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${activity.packageName}"))
            .setPackage("com.android.vending")
        try {
            activity.startActivity(store)
        } catch (_: ActivityNotFoundException) {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=${activity.packageName}")))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(activity, "Não foi possível abrir a Play Store neste aparelho.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
