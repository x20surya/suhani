package app.suhani.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Design tokens + tiny builders, matching the webapp's look: near-black
 * surface, soft cards, cyan accent, generous spacing, quiet hierarchy.
 */
object Ui {
    const val BG = 0xFF0A0E13.toInt()
    const val CARD = 0xFF131A23.toInt()
    const val CARD_BORDER = 0xFF1F2933.toInt()
    const val FG = 0xFFF2F6FA.toInt()
    const val MUTED = 0xFF7C8B9A.toInt()
    const val ACCENT = 0xFF1FD5F9.toInt()
    const val DANGER = 0xFFFF6B6B.toInt()

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun card(context: Context, radiusDp: Int = 18): GradientDrawable = GradientDrawable().apply {
        setColor(CARD)
        cornerRadius = dp(context, radiusDp).toFloat()
        setStroke(dp(context, 1), CARD_BORDER)
    }

    fun pill(context: Context, fill: Int = CARD, stroke: Int = CARD_BORDER): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(context, 24).toFloat()
            setStroke(dp(context, 1), stroke)
        }

    fun glow(center: Int = 0x331FD5F9): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        gradientType = GradientDrawable.RADIAL_GRADIENT
        colors = intArrayOf(center, 0x00000000)
        gradientRadius = 500f
    }

    fun text(
        context: Context,
        value: String,
        sizeSp: Float,
        color: Int = FG,
        bold: Boolean = false,
        center: Boolean = false,
    ): TextView = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        if (center) gravity = Gravity.CENTER
    }

    fun pillButton(context: Context, label: String, accent: Boolean = false, onClick: () -> Unit): TextView =
        TextView(context).apply {
            text = label
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (accent) 0xFF06222B.toInt() else FG)
            background = pill(context, if (accent) ACCENT else CARD)
            val h = dp(context, 18)
            val v = dp(context, 10)
            setPadding(h, v, h, v)
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener { onClick() }
        }

    fun sectionCard(context: Context, title: String): Pair<LinearLayout, LinearLayout> {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = card(context)
            val p = dp(context, 16)
            setPadding(p, p, p, p)
        }
        outer.addView(
            text(context, title.uppercase(), 12f, MUTED, bold = true).apply {
                letterSpacing = 0.12f
                setPadding(0, 0, 0, dp(context, 10))
            },
        )
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(body)
        return outer to body
    }
}
