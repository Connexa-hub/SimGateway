package com.connexa.simgateway

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Small programmatic design system: cards, buttons, badges, icons. Colors come from night-aware resources. */
class Ui(private val ctx: Context) {

    enum class Kind { PRIMARY, SECONDARY, CALL, DANGER, GHOST }

    private val density = ctx.resources.displayMetrics.density

    fun dp(v: Int): Int = (v * density + 0.5f).toInt()
    fun color(id: Int): Int = ctx.getColor(id)

    fun rounded(fill: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Int = 1): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.RECTANGLE
        g.cornerRadius = dp(radiusDp).toFloat()
        g.setColor(fill)
        if (stroke != null) g.setStroke(dp(strokeDp), stroke)
        return g
    }

    /** Rounded only at the top (rounded-rect sheet sliding up from the bottom). */
    fun roundedTop(fill: Int, radiusDp: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.RECTANGLE
        val r = dp(radiusDp).toFloat()
        g.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        g.setColor(fill)
        return g
    }

    fun clickableBg(fill: Int, radiusDp: Int, stroke: Int? = null, strokeDp: Int = 1): Drawable {
        val content = rounded(fill, radiusDp, stroke, strokeDp)
        val mask = rounded(Color.WHITE, radiusDp)
        return RippleDrawable(ColorStateList.valueOf(color(R.color.sg_ripple)), content, mask)
    }

    fun ovalClickableBg(fill: Int): Drawable {
        val content = oval(fill)
        val mask = oval(Color.WHITE)
        return RippleDrawable(ColorStateList.valueOf(color(R.color.sg_ripple)), content, mask)
    }

    fun oval(fill: Int): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(fill)
        return g
    }

    fun ring(strokeColor: Int, strokeDp: Int = 3): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(Color.TRANSPARENT)
        g.setStroke(dp(strokeDp), strokeColor)
        return g
    }

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    fun text(
        s: CharSequence,
        sp: Float,
        colorId: Int = R.color.sg_text,
        bold: Boolean = false,
        center: Boolean = false
    ): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.setTextColor(color(colorId))
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        if (center) t.gravity = Gravity.CENTER
        t.setLineSpacing(0f, 1.12f)
        return t
    }

    fun icon(res: Int, tintId: Int): ImageView {
        val v = ImageView(ctx)
        v.setImageResource(res)
        v.imageTintList = ColorStateList.valueOf(color(tintId))
        v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        return v
    }

    fun card(padDp: Int = 16): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
        c.background = rounded(color(R.color.sg_surface), 20, color(R.color.sg_outline))
        return c
    }

    fun dot(colorId: Int, sizeDp: Int = 12): View {
        val v = View(ctx)
        v.background = oval(color(colorId))
        v.layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        return v
    }

    fun badge(label: String, colorId: Int): TextView {
        val t = text(label, 12f, colorId, bold = true)
        t.setPadding(dp(10), dp(4), dp(10), dp(4))
        t.background = rounded(withAlpha(color(colorId), 0x26), 12)
        return t
    }

    fun button(
        label: String,
        iconRes: Int?,
        kind: Kind,
        enabled: Boolean = true,
        onClick: () -> Unit
    ): LinearLayout {
        val spec = when (kind) {
            Kind.PRIMARY -> Triple(color(R.color.sg_primary), R.color.sg_on_primary, null as Int?)
            Kind.CALL -> Triple(color(R.color.sg_call), R.color.sg_on_call, null as Int?)
            Kind.DANGER -> Triple(color(R.color.sg_danger_btn), R.color.sg_on_danger, null as Int?)
            Kind.SECONDARY -> Triple(color(R.color.sg_surface), R.color.sg_text, color(R.color.sg_outline) as Int?)
            Kind.GHOST -> Triple(Color.TRANSPARENT, R.color.sg_primary, null as Int?)
        }
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.minimumHeight = dp(54)
        row.setPadding(dp(20), dp(12), dp(20), dp(12))
        row.background = clickableBg(spec.first, 16, spec.third)
        row.isClickable = true
        row.isFocusable = true
        row.contentDescription = label
        if (iconRes != null) {
            val ic = icon(iconRes, spec.second)
            row.addView(ic, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
        }
        row.addView(text(label, 16f, spec.second, bold = true))
        row.isEnabled = enabled
        row.alpha = if (enabled) 1f else 0.45f
        if (enabled) row.setOnClickListener { onClick() }
        return row
    }
}
