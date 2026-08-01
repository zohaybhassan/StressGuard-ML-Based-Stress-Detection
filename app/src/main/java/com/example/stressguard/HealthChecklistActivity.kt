package com.example.stressguard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.stressguard.data.HealthChecklistRepository
import com.example.stressguard.data.local.HealthChecklistEntity
import com.example.stressguard.ui.fitSystemBars
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import kotlinx.coroutines.launch

/**
 * The self-reported risk factors that plan §7's rule-based score reads.
 *
 * Every answer maps to a fixed number of points, and the recommendation explains which ones it
 * used, so the form is deliberately short and plainly worded — a checkbox the user did not
 * understand still contributes to a recommendation they are then told to act on.
 *
 * Skippable. The score is defined for an all-false checklist (sustained stress alone can reach the
 * "book a checkup" threshold, per §7's second rule), so refusing to answer degrades the
 * recommendation rather than blocking the app on a medical questionnaire.
 */
class HealthChecklistActivity : AppCompatActivity() {

    private lateinit var boxes: Map<Field, MaterialCheckBox>

    /** The eight factors plan §7 scores, tied to their entity fields in one place. */
    private enum class Field { SMOKING, HEART, HYPERTENSION, DIABETES, SLEEP, ANXIETY, CAFFEINE, INACTIVE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_health_checklist)
        fitSystemBars(top = findViewById(R.id.checklistRoot))

        boxes = mapOf(
            Field.SMOKING to findViewById(R.id.cbSmoking),
            Field.HEART to findViewById(R.id.cbHeartCondition),
            Field.HYPERTENSION to findViewById(R.id.cbHypertension),
            Field.DIABETES to findViewById(R.id.cbDiabetes),
            Field.SLEEP to findViewById(R.id.cbSleepDisorder),
            Field.ANXIETY to findViewById(R.id.cbAnxietyHistory),
            Field.CAFFEINE to findViewById(R.id.cbHighCaffeineUse),
            Field.INACTIVE to findViewById(R.id.cbPhysicallyInactive),
        )

        val save = findViewById<MaterialButton>(R.id.btnSaveChecklist)
        val skip = findViewById<MaterialButton>(R.id.btnSkipChecklist)

        // Editing rather than first-run: show what was answered before, or the user has to
        // reconstruct the whole form from memory to change one box.
        lifecycleScope.launch {
            HealthChecklistRepository.current(this@HealthChecklistActivity)?.let(::render)
        }

        save.setOnClickListener {
            save.isEnabled = false
            lifecycleScope.launch {
                HealthChecklistRepository.save(this@HealthChecklistActivity, collect())
                Toast.makeText(
                    this@HealthChecklistActivity,
                    "Health checklist saved",
                    Toast.LENGTH_SHORT,
                ).show()
                continueOn()
            }
        }

        skip.setOnClickListener { continueOn() }
    }

    private fun render(checklist: HealthChecklistEntity) {
        boxes[Field.SMOKING]?.isChecked = checklist.smoking
        boxes[Field.HEART]?.isChecked = checklist.heartCondition
        boxes[Field.HYPERTENSION]?.isChecked = checklist.hypertension
        boxes[Field.DIABETES]?.isChecked = checklist.diabetes
        boxes[Field.SLEEP]?.isChecked = checklist.sleepDisorder
        boxes[Field.ANXIETY]?.isChecked = checklist.anxietyHistory
        boxes[Field.CAFFEINE]?.isChecked = checklist.highCaffeineUse
        boxes[Field.INACTIVE]?.isChecked = checklist.physicallyInactive
    }

    private fun collect() = HealthChecklistEntity(
        smoking = checked(Field.SMOKING),
        heartCondition = checked(Field.HEART),
        hypertension = checked(Field.HYPERTENSION),
        diabetes = checked(Field.DIABETES),
        sleepDisorder = checked(Field.SLEEP),
        anxietyHistory = checked(Field.ANXIETY),
        highCaffeineUse = checked(Field.CAFFEINE),
        physicallyInactive = checked(Field.INACTIVE),
        updatedAtEpochMs = System.currentTimeMillis(),
    )

    private fun checked(field: Field): Boolean = boxes[field]?.isChecked == true

    /**
     * Goes to the dashboard on first run, or simply back when the user opened this to edit.
     *
     * Distinguished by the launching extra rather than by the back stack, because both routes
     * arrive here through `startActivity`.
     */
    private fun continueOn() {
        if (intent.getBooleanExtra(EXTRA_EDITING, false)) {
            finish()
        } else {
            startActivity(Intent(this, HomeDashboardActivity::class.java))
            finish()
        }
    }

    companion object {
        private const val EXTRA_EDITING = "editing"

        /** First-run entry: continues to the dashboard once answered or skipped. */
        fun setupIntent(context: Context) = Intent(context, HealthChecklistActivity::class.java)

        /** Edit entry from the dashboard: returns where it came from. */
        fun editIntent(context: Context) = Intent(context, HealthChecklistActivity::class.java)
            .putExtra(EXTRA_EDITING, true)
    }
}
