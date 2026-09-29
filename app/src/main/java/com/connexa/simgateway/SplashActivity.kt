package com.connexa.simgateway

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Brand splash shown on cold launch. Purely visual: hands off to MainActivity after a short beat. */
class SplashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)

        val root = FrameLayout(this)
        root.setBackgroundColor(ui.color(R.color.sg_primary))
        setContentView(root)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER

        val logo = FrameLayout(this)
        logo.background = ui.rounded(ui.color(R.color.sg_on_primary), 28)
        logo.addView(
            ui.icon(R.drawable.ic_sim_card, R.color.sg_primary),
            FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.CENTER)
        )
        col.addView(logo, LinearLayout.LayoutParams(ui.dp(96), ui.dp(96)))

        val title = ui.text("LinkSIM", 30f, R.color.sg_on_primary, bold = true, center = true)
        col.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = ui.dp(18) })

        val tagline = ui.text("Your SIM, on any phone", 14f, R.color.sg_on_primary, center = true)
        tagline.alpha = 0.85f
        col.addView(tagline, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = ui.dp(6) })

        root.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        logo.scaleX = 0.5f
        logo.scaleY = 0.5f
        logo.alpha = 0f
        title.alpha = 0f
        tagline.alpha = 0f

        val logoAnim = ObjectAnimator.ofPropertyValuesHolder(
            logo,
            PropertyValuesHolder.ofFloat("scaleX", 0.5f, 1f),
            PropertyValuesHolder.ofFloat("scaleY", 0.5f, 1f),
            PropertyValuesHolder.ofFloat("alpha", 0f, 1f)
        )
        logoAnim.duration = 480
        logoAnim.interpolator = OvershootInterpolator()

        val titleAnim = ObjectAnimator.ofFloat(title, "alpha", 0f, 1f)
        titleAnim.duration = 350
        titleAnim.startDelay = 200

        val tagAnim = ObjectAnimator.ofFloat(tagline, "alpha", 0f, 0.85f)
        tagAnim.duration = 350
        tagAnim.startDelay = 320

        AnimatorSet().apply {
            playTogether(logoAnim, titleAnim, tagAnim)
            start()
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                startActivity(Intent(this, MainActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
            }
        }, 1100)
    }
}
