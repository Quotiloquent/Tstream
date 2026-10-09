package com.noapmat.tsream.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.noapmat.tsream.R

/**
 * Google Sans (bundled, OFL) for every TextView. The theme already sets it, but Material components
 * and some OEM skins (e.g. Samsung One UI) replace fonts per widget, so we also apply it explicitly.
 * The weight the widget asked for (regular / medium / bold) is preserved.
 */
object Fonts {
    fun apply(root: View) {
        val family = ResourcesCompat.getFont(root.context, R.font.google_sans) ?: return
        walk(root, family)
    }

    fun mono(ctx: Context): Typeface? = ResourcesCompat.getFont(ctx, R.font.google_sans_code)

    private fun walk(v: View, family: Typeface) {
        if (v is TextView) v.typeface = withWeightOf(v.typeface, family)
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), family)
    }

    private fun withWeightOf(current: Typeface?, family: Typeface): Typeface =
        if (Build.VERSION.SDK_INT >= 28) {
            Typeface.create(family, current?.weight ?: 400, current?.isItalic == true)
        } else {
            Typeface.create(family, if (current?.isBold == true) Typeface.BOLD else Typeface.NORMAL)
        }
}
