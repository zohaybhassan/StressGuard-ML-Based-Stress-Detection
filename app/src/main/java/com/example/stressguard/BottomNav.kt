package com.example.stressguard

import android.app.Activity
import android.content.Intent
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * The bottom navigation bar, shared by the screens that show it.
 *
 * It was declared in the dashboard layout from the start and wired to nothing — tapping a tab did
 * nothing at all, which reads as a broken app rather than an unfinished one. Centralised here so
 * the two screens that carry it cannot disagree about where a tab goes.
 *
 * Deliberately no animation and no back stack growth: each destination is a single instance, so
 * moving between tabs cannot pile up Activities that the back button then has to unwind one by one.
 */
object BottomNav {

    /**
     * @param selected the tab this screen *is*, so tapping it is a no-op rather than a reload.
     */
    fun wire(activity: Activity, view: BottomNavigationView, selected: Int) {
        view.selectedItemId = selected
        view.setOnItemSelectedListener { item ->
            if (item.itemId == selected) return@setOnItemSelectedListener true

            val destination = when (item.itemId) {
                R.id.nav_home -> HomeDashboardActivity::class.java
                R.id.nav_trends -> TrendsActivity::class.java
                R.id.nav_assistant -> AssistantActivity::class.java
                else -> return@setOnItemSelectedListener false
            }

            activity.startActivity(
                Intent(activity, destination)
                    // REORDER_TO_FRONT rather than a fresh launch: the dashboard holds live state
                    // and a loaded ONNX session, and recreating it on every tab switch would drop
                    // both. CLEAR_TOP keeps the stack from growing as tabs are toggled.
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            activity.overridePendingTransition(0, 0)
            true
        }
    }
}
