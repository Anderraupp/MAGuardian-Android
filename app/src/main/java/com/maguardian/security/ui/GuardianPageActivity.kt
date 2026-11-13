package com.maguardian.security.ui

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import com.maguardian.security.R

abstract class GuardianPageActivity : AppCompatActivity() {
    protected lateinit var content: LinearLayout
    protected abstract val pageTitle: String
    override fun onCreate(savedInstanceState: Bundle?) {
        GuardianAppearance.apply(this)
        super.onCreate(savedInstanceState)
        ScreenInsets.configure(window)
        setContentView(R.layout.activity_auxiliary)
        ScreenInsets.apply(this, GuardianAppearance.isLight(this))
        content = findViewById(R.id.pageContent)
        findViewById<Button>(R.id.btnPageBack).apply { text = "←  $pageTitle"; setOnClickListener { finish() } }
        GuardianNavigation.bind(this)
    }
    override fun onResume() {
        super.onResume()
        GuardianAppearance.refreshIfChanged(this)
    }
    protected fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    protected fun label(text: String, heading: Boolean = false): TextView =
        TextView(this, null, 0, if (heading) R.style.GuardianHeading else R.style.GuardianText).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        }
    protected fun action(text: String, block: () -> Unit): Button =
        androidx.appcompat.widget.AppCompatButton(this, null, 0).apply {
            this.text = text; isAllCaps = false; textSize = 14f
            minHeight = dp(48); gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setTextColor(GuardianAppearance.color(this@GuardianPageActivity, R.color.guardian_button_text))
            background = androidx.core.content.ContextCompat.getDrawable(this@GuardianPageActivity, R.drawable.guardian_primary)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            setOnClickListener { block() }
        }
}
