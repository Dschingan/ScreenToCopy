package com.screentocopy.core.action

/**
 * 🎯 SelectionAction — User's resolved intent after gesture analysis.
 *
 * COPY  → default, zero-latency clipboard write
 * EDIT  → user held center zone ≥ dwellThresholdMs; clipboard write first, then edit intent async
 */
enum class SelectionAction {
    COPY,
    EDIT
}
