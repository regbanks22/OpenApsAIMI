package app.aaps.plugins.aps.openAPSAIMI.basal

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.model.BasalPlan
import app.aaps.plugins.aps.openAPSAIMI.model.LoopContext
import app.aaps.plugins.aps.openAPSAIMI.AIMIAdaptiveBasal
import jakarta.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Planificateur Basal AIMI (complet, sûr, testable).
 *
 * Ordre des décisions :
 *  1) Hypo-guard / suspend (priorité sécurité)
 *  2) Micro-resume (reprise prudente après long 0 basal)
 *  3) Kicker plateau haut (BG élevé, deltas ~ 0)
 *  4) Anti-stall léger (Δ quasi nul et pas franchement positif)
 *  5) AIMIAdaptiveBasal (réglage avancé si applicable)
 *  6) Sinon : pas de TBR -> garder le profil
 *
 * NOTES :
 * - Les seuils par défaut sont prudents et peuvent être reliés aux préférences plus tard.
 * - Les valeurs de BG/deltas utilisent les champs déjà présents dans ctx.bg (accel, r2, etc. en optionnels).
 * - Le planner respecte step, minDurationMin et maxBasal côté pompe.
 */
class AIMIAdaptiveBasal @Inject constructor(
    private val preferences: Preferences,
    private val log: AAPSLogger,
    private val fmt: DecimalFormatter
)
object BasalPlanner {

    // ===== Paramètres par défaut (prudence) =====
    private const val HYPO_SUSPEND_MGDL = 75.0          // seuil direct de suspend
    private const val HYPO_SUSPEND_SOFT_MGDL = 85.0     // seuil "soft" si Δ négatif
    private const val HYPO_SUSPEND_MINUTES = 30

    private const val ZERO_RESUME_MIN = 10              // après >=10 min à 0 basal
    private const val ZERO_RESUME_RATE_FRAC = 0.25      // 25% du profil
    private const val ZERO_RESUME_MINUTES_MAX = 30

    private const val HIGH_BG_MGDL = 180.0
    private const val PLATEAU_DELTA_ABS = 2.5           // mg/dL par 5 min
    private const val KICKER_STEP_FRAC = 0.15           // +15% du profil
    private const val KICKER_MIN_UPH = 0.20             // min 0.20 U/h
    private const val KICKER_MINUTES = 10               // impulsion courte (re-évaluation au cycle)

    private const val ANTI_STALL_FRAC = 0.10            // +10% si Δ≈0
    private const val DELTA_POS_RELEASE = 1.0           // si Δ>1, on relâche l’anti-stall

    private const val MAX_MULTIPLIER = 1.60             // plafond : 1.6× profil

    fun plan(ctx: LoopContext): BasalPlan? {
        // ===== Récupération signaux =====
        val mgdl = ctx.bg.mgdl
        val d5 = ctx.bg.delta5
        val short = ctx.bg.shortAvgDelta ?: d5
        val long = ctx.bg.longAvgDelta ?: d5
        val accel = ctx.bg.accel ?: 0.0           // réservé à AIMIAdaptiveBasal
        val r2 = ctx.bg.r2 ?: 0.0                 // idem
        val parabolaMin = ctx.bg.parabolaMinutes ?: 0.0
        val combined = ctx.bg.combinedDelta ?: d5

        val profileBasal = ctx.profile.basalProfileUph
        val target = ctx.profile.targetMgdl

        val maxBasal = ctx.pump.maxBasal.coerceAtLeast(profileBasal)
        val step = ctx.pump.basalStep.coerceAtLeast(0.05)
        val minDur = ctx.pump.minDurationMin.coerceAtLeast(10)

        // Historique via provider (neutre si non injecté)
        val hist = BasalHistoryUtils.historyProvider
        val zeroSinceMin = hist.zeroBasalDurationMinutes(lookBackHours = 6)
        val lastTempIsZero = hist.lastTempIsZero()
        val minutesSinceLastChange = hist.minutesSinceLastChange()

        // Garde-fous
        if (profileBasal <= 0.0) return null

        // ===== 1) Hypo-guard / suspend =====
        if (mgdl <= HYPO_SUSPEND_MGDL || (mgdl <= HYPO_SUSPEND_SOFT_MGDL && d5 < 0.0)) {
            return BasalPlan(
                rateUph = 0.0,
                durationMin = HYPO_SUSPEND_MINUTES,
                reason = "Hypo guard: BG=$mgdl, Δ=${fmt1(d5)} → suspend ${HYPO_SUSPEND_MINUTES}m"
            )
        }

        // ===== 2) Micro-resume après longue période à 0 basal =====
        if (lastTempIsZero && zeroSinceMin >= ZERO_RESUME_MIN) {
            val base = max(KICKER_MIN_UPH, profileBasal * ZERO_RESUME_RATE_FRAC)
            val rate = clampAndQuantize(base, profileBasal, maxBasal, step)
            val dur = min(ZERO_RESUME_MINUTES_MAX, max(minDur, minutesSinceLastChange / 2))
            return BasalPlan(
                rateUph = rate,
                durationMin = dur,
                reason = "Micro-resume after ${zeroSinceMin}m @0U/h → ${fmt2(rate)}U/h × ${dur}m"
            )
        }

        // ===== 3) Kicker plateau haut (BG élevé & deltas ~ 0) =====
        val plateau = (abs(d5) <= PLATEAU_DELTA_ABS) && (abs(short) <= PLATEAU_DELTA_ABS)
        val highAndFlat = plateau && (mgdl >= HIGH_BG_MGDL)
        if (highAndFlat) {
            val baseKick = max(KICKER_MIN_UPH, profileBasal * (1.0 + KICKER_STEP_FRAC))
            val rate = clampAndQuantize(baseKick, profileBasal, maxBasal, step)
            return BasalPlan(
                rateUph = rate,
                durationMin = max(minDur, KICKER_MINUTES),
                reason = "High-flat kicker @${mgdl.toInt()}mg/dL (Δ≈0) → ${fmt2(rate)}U/h × ${max(minDur, KICKER_MINUTES)}m"
            )
        }

        // ===== 4) Anti-stall léger (Δ quasi-nul, pas franchement positif) =====
        val nearFlat = abs(d5) <= PLATEAU_DELTA_ABS && abs(short) <= PLATEAU_DELTA_ABS
        if (nearFlat && d5 < DELTA_POS_RELEASE) {
            val base = profileBasal * (1.0 + ANTI_STALL_FRAC)
            val rate = clampAndQuantize(base, profileBasal, maxBasal, step)
            return BasalPlan(
                rateUph = rate,
                durationMin = minDur,
                reason = "Anti-stall (Δ≈0) → ${fmt2(rate)}U/h × ${minDur}m"
            )
        }

        // ===== 5) Délégation AIMIAdaptiveBasal (si applicable) =====
        val adaptiveBasal = AIMIAdaptiveBasal(
            prefs = ctx.prefs,        // ✅ si ton LoopContext contient déjà Preferences
            log = ctx.logger,         // ✅ si LoopContext a AAPSLogger
            fmt = ctx.formatter       // ✅ idem pour DecimalFormatter
        )
        val aimiOut = adaptiveBasal.suggest(
            AIMIAdaptiveBasal.Input(
                bg = mgdl,
                delta = d5,
                shortAvgDelta = short,
                longAvgDelta = long,
                accel = accel,
                r2 = r2,
                parabolaMin = parabolaMin,
                combinedDelta = combined,
                profileBasal = profileBasal,
                lastTempIsZero = lastTempIsZero,
                zeroSinceMin = zeroSinceMin,
                minutesSinceLastChange = minutesSinceLastChange
            )
        )
        val adaptiveRate = aimiOut.rateUph
        if (adaptiveRate != null && adaptiveRate as Double > 0.0) {
            val rate = clampAndQuantize(adaptiveRate as Double, profileBasal, maxBasal, step)
            val dur = max(minDur, aimiOut.durationMin.coerceAtLeast(0))
            return BasalPlan(
                rateUph = rate,
                durationMin = dur,
                reason = aimiOut.reason ?: "AIMIAdaptiveBasal"
            )
        }

        // ===== 6) Sinon → pas de TBR (profil inchangé) =====
        return null
    }

    // ===== Helpers =====
    private fun clampAndQuantize(
        desiredUph: Double,
        profileBasal: Double,
        maxBasal: Double,
        step: Double
    ): Double {
        val clamped = min(desiredUph, profileBasal * MAX_MULTIPLIER).coerceAtMost(maxBasal)
        return quantize(clamped, step)
    }

    private fun quantize(value: Double, step: Double): Double =
        round(value / step) * step

    private fun fmt1(x: Double): String =
        String.format(java.util.Locale.US, "%.1f", x)

    private fun fmt2(x: Double): String =
        String.format(java.util.Locale.US, "%.2f", x)
}
