package com.screentocopy.core.action

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 🎨 Floating Action Bar (Overlay)
 * - Lightweight View (tek seviye LinearLayout, overdraw yok)
 * - Fade + Slide Up (120ms, 80ms gecikme ile "düşünme" hissi)
 * - Max 4 element
 */
class FloatingActionBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        
        // Şık, koyu yarı saydam hap tasarım
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 50f
            setColor(Color.parseColor("#E61E1E1E")) // %90 Opak Koyu Gri
        }
        background = bgDrawable
        
        val padV = 8.dpToPx()
        val padH = 12.dpToPx()
        setPadding(padH, padV, padH, padV)
        
        alpha = 0f
        visibility = View.GONE
        
        // Tıklamaların arka plana geçmesini engelle
        isClickable = true
        isFocusable = true
    }

    /**
     * Eylemleri gösterir ve animasyonu başlatır.
     * @param actions Gösterilecek aksiyonlar (Max 4 olmalı, Classifier zaten sınırlandırıyor)
     * @param roiBottom Seçili alanın alt sınırı (Y koordinatı). Bu sayede ROI'nin tam altında belirir.
     */
    fun showActions(actions: List<SmartAction>, roiBottom: Float, screenHeight: Int) {
        removeAllViews()

        actions.forEach { action ->
            val btn = TextView(context).apply {
                text = action.label
                setTextColor(Color.WHITE)
                textSize = 14f
                val btnPadH = 16.dpToPx()
                val btnPadV = 8.dpToPx()
                setPadding(btnPadH, btnPadV, btnPadH, btnPadV)
                gravity = Gravity.CENTER
                
                // Icon desteği
                setCompoundDrawablesWithIntrinsicBounds(action.icon, 0, 0, 0)
                compoundDrawablePadding = 8.dpToPx()
                
                setOnClickListener { 
                    action.action()
                    hide() // Tıklanınca kaybol
                }
            }
            
            val params = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(btn, params)
        }

        // Pozisyon: ROI'nin hemen altı. Eğer ekranın çok altındaysa üstüne al.
        measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val barHeight = measuredHeight
        
        var targetY = roiBottom + 30f // ROI'nin biraz altı
        if (targetY + barHeight > screenHeight - 100f) {
            // Ekrana sığmıyorsa, ROI'nin üstüne çıkar (Highlight yüksekliğini parametre olarak almak daha iyi olur ama şimdilik ekranı baz alıyoruz)
            targetY = roiBottom - barHeight - 100f 
        }
        
        y = targetY
        
        // Ortala
        val screenWidth = context.resources.displayMetrics.widthPixels
        x = (screenWidth - measuredWidth) / 2f
        
        visibility = View.VISIBLE

        // ⚡ Animasyon: Fade + Slide Up + Scale (Spawn Physics)
        translationY = 20f
        scaleX = 0.95f
        scaleY = 0.95f
        
        val slideUp = ObjectAnimator.ofFloat(this, "translationY", 20f, 0f)
        val fadeIn = ObjectAnimator.ofFloat(this, "alpha", 0f, 1f)
        val scaleXAnim = ObjectAnimator.ofFloat(this, "scaleX", 0.95f, 1f)
        val scaleYAnim = ObjectAnimator.ofFloat(this, "scaleY", 0.95f, 1f)

        AnimatorSet().apply {
            playTogether(slideUp, fadeIn, scaleXAnim, scaleYAnim)
            duration = 120
            interpolator = DecelerateInterpolator()
            startDelay = 80 // "Düşünüyor" delayi (Çok kritik UX detayı)
            start()
        }
    }

    fun hide() {
        animate()
            .alpha(0f)
            .translationY(20f)
            .setDuration(100)
            .withEndAction { visibility = View.GONE }
            .start()
    }

    private fun Int.dpToPx(): Int {
        val density = context.resources.displayMetrics.density
        return (this * density).toInt()
    }
}
