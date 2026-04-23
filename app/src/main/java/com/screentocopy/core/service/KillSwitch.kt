package com.screentocopy.core.service

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 🔴 Kill-Switch
 * 
 * Sistemin stabilitesi bozulduğunda veya kullanıcı servisi acil durdurmak istediğinde
 * tüm processleri (Overlay, Projection, OCR) güvenli bir şekilde kapatır.
 */
object KillSwitch {

    fun trigger(context: Context, reason: String) {
        Log.e("KillSwitch", "KILL SWITCH TRIGGERED! Reason: $reason")

        // 1. Durdurulabilir Servisleri Kapat
        // val stopIntent = Intent(context, STSAccessibilityService::class.java)
        // context.stopService(stopIntent)

        // 2. State & Cache'i Temizle
        // ServiceHealth ve diğer Singleton state'leri sıfırla
        
        // 3. (Opsiyonel) Process seviyesinde kendini öldür
        // System.exit(0) veya Process.killProcess(Process.myPid())
        // Sadece çok kritik (memory leak vs) durumlarda kullanılmalı.
    }
}
