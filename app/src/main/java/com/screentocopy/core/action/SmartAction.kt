package com.screentocopy.core.action

data class SmartAction(
    val id: String,
    val label: String,
    val icon: Int, // e.g., android.R.drawable.ic_menu_copy
    val priority: Int,
    val action: () -> Unit
)
