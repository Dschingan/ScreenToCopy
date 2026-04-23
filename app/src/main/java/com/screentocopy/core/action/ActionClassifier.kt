package com.screentocopy.core.action

import android.content.Context
import android.content.Intent
import android.net.Uri

class ActionClassifier(private val context: Context) {

    /**
     * @param text Ocr sonucu çıkan metin
     * @param hasImage Seçimde kullanılabilecek görsel var mı
     * @param imageUri Varsa görselin uri'si (Share için)
     */
    fun classify(text: String, hasImage: Boolean, imageUri: Uri? = null, onCopyRequested: () -> Unit): List<SmartAction> {
        val actions = mutableListOf<SmartAction>()
        val cleanText = text.trim()

        // 1. Copy (Her zaman en öncelikli)
        actions.add(
            SmartAction(
                id = "copy",
                label = "Copy",
                icon = android.R.drawable.ic_menu_edit,
                priority = 100,
                action = { onCopyRequested() }
            )
        )

        if (cleanText.isNotEmpty()) {
            // 2. Open URL
            val isUrl = cleanText.startsWith("http://") || 
                        cleanText.startsWith("https://") || 
                        cleanText.matches(Regex("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(/.*)?$"))
            
            if (isUrl) {
                actions.add(
                    SmartAction(
                        id = "open_url",
                        label = "Open",
                        icon = android.R.drawable.ic_menu_view,
                        priority = 90,
                        action = {
                            val url = if (!cleanText.startsWith("http")) "https://$cleanText" else cleanText
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        }
                    )
                )
            }

            // 3. Call Phone Number
            val isPhone = cleanText.matches(Regex("^\\+?[0-9\\s\\-()]{7,15}$"))
            if (isPhone) {
                actions.add(
                    SmartAction(
                        id = "call",
                        label = "Call",
                        icon = android.R.drawable.ic_menu_call,
                        priority = 85,
                        action = {
                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanText"))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        }
                    )
                )
            }

            // 4. Search & Translate (Uzun metinler veya URL/Phone olmayanlar için)
            if (cleanText.length > 2) {
                actions.add(
                    SmartAction(
                        id = "search",
                        label = "Search",
                        icon = android.R.drawable.ic_menu_search,
                        priority = 80,
                        action = {
                            val intent = Intent(Intent.ACTION_WEB_SEARCH)
                            intent.putExtra("query", cleanText)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        }
                    )
                )

                actions.add(
                    SmartAction(
                        id = "translate",
                        label = "Translate",
                        icon = android.R.drawable.ic_menu_sort_alphabetically,
                        priority = 70,
                        action = {
                            // Basit bir web translate intent'i (İleride ML Kit Translate eklenebilir)
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://translate.google.com/?sl=auto&tl=en&text=${Uri.encode(cleanText)}&op=translate"))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(intent) }
                        }
                    )
                )
            }
        }

        // 5. Share
        if (cleanText.isNotEmpty() || hasImage) {
            actions.add(
                SmartAction(
                    id = "share",
                    label = "Share",
                    icon = android.R.drawable.ic_menu_share,
                    priority = 60,
                    action = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = if (hasImage) "image/png" else "text/plain"
                            if (cleanText.isNotEmpty()) {
                                putExtra(Intent.EXTRA_TEXT, cleanText)
                            }
                            if (hasImage && imageUri != null) {
                                putExtra(Intent.EXTRA_STREAM, imageUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        }
                        val chooser = Intent.createChooser(shareIntent, "Share").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(chooser) }
                    }
                )
            )
        }

        // Kural: Max 4 action göster ve önceliğe göre sırala
        return actions.sortedByDescending { it.priority }.take(4)
    }
}
