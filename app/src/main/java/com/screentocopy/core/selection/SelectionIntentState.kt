package com.screentocopy.core.selection

/**
 * 🧠 SelectionIntentState — State machine for the drag-to-edit gesture.
 *
 * Transitions:
 *   IDLE → SELECTING (ACTION_DOWN)
 *   SELECTING → CANDIDATE_COPY | CANDIDATE_EDIT (ACTION_MOVE)
 *   CANDIDATE_EDIT → CANDIDATE_COPY (finger leaves center)
 *   CANDIDATE_* → FINAL_COPY | FINAL_EDIT (ACTION_UP)
 *   ACTION_CANCEL → always FINAL_COPY (system interrupt guard)
 */
enum class SelectionIntentState {
    IDLE,
    SELECTING,
    CANDIDATE_COPY,
    CANDIDATE_EDIT,
    FINAL_COPY,
    FINAL_EDIT
}
