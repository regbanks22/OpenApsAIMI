package app.aaps.plugins.aps.openAPSAIMI.basal

import app.aaps.core.data.model.TB
import kotlin.math.max

/**
 * Utilitaires robustes d'historique Basal (TBR).
 *
 * - Fournit une API via un provider (pluggable) -> pas de dépendance forte à la persistence.
 * - Par défaut, provider neutre (retourne 0 / false) => ne casse pas la compilation.
 *
 * Modèle TB (confirmé) :
 *   - TB.isAbsolute : true => rate en U/h ; false => rate en % du profil
 *   - TB.rate       : valeur (U/h ou % selon isAbsolute)
 *   - TB.timestamp  : début de la TBR
 *   - TB.duration   : ms ; TB.end = timestamp + duration
 *
 * Zéro basal :
 *   - si isAbsolute -> rate <= 0.05 U/h
 *   - si !isAbsolute -> rate <= 5 %
 */
object BasalHistoryUtils {

    // --------- API publique (provider) ---------

    interface BasalHistoryProvider {
        /** Durée (minutes) d'une TBR ≈ 0 en cours/continu sur les dernières [lookBackHours] heures. */
        fun zeroBasalDurationMinutes(lookBackHours: Int): Int

        /** Vrai si la TBR active (actuelle) est ≈ 0. */
        fun lastTempIsZero(): Boolean

        /** Minutes écoulées depuis le dernier changement de TBR (ou 0 si inconnu). */
        fun minutesSinceLastChange(): Int
    }

    /**
     * Provider neutre par défaut (retours « safe »).
     * Laisse le planificateur fonctionner sans persistence branchée.
     */
    object EmptyProvider : BasalHistoryProvider {
        override fun zeroBasalDurationMinutes(lookBackHours: Int) = 0
        override fun lastTempIsZero() = false
        override fun minutesSinceLastChange() = 0
    }

    @Volatile
    var historyProvider: BasalHistoryProvider = EmptyProvider

    fun setHistoryProvider(provider: BasalHistoryProvider) {
        historyProvider = provider
    }

    // --------- Implémentation générique sur source TB ---------

    /**
     * Provider générique basé sur une fonction fetch :
     *   - fetcher(fromMillis) doit retourner les TB **triés du plus récent au plus ancien**.
     */
    class FetcherProvider(
        private val fetcher: (fromMillis: Long) -> List<TB>,
        private val nowProvider: () -> Long = { System.currentTimeMillis() }
    ) : BasalHistoryProvider {

        override fun zeroBasalDurationMinutes(lookBackHours: Int): Int {
            val now = nowProvider()
            val from = now - lookBackHours * 60L * 60L * 1000L
            val events = safeFetch(from)
            if (events.isEmpty()) return 0

            var lastZeroTs: Long? = null
            for (tb in events) {
                val ts = tb.timestamp
                val isZero = isZeroBasal(tb)
                if (isZero) {
                    // On note le plus ancien timestamp d'une séquence 0 soutenue
                    lastZeroTs = ts
                } else {
                    // Liste récente -> ancienne : dès qu'on voit non-zero, on stoppe
                    break
                }
            }
            val ref = lastZeroTs ?: return 0
            val durMs = now - ref
            return max(0, (durMs / 60000L).toInt())
        }

        override fun lastTempIsZero(): Boolean {
            val now = nowProvider()
            val from = now - 3L * 60L * 60L * 1000L
            val events = safeFetch(from)
            if (events.isEmpty()) return false
            // Le plus récent en premier
            val current = events.first()
            // Si la TBR la plus récente est en cours, on teste à partir d'elle ;
            // sinon on considère que l'état courant est le profil (non-zero).
            return current.isInProgress && isZeroBasal(current)
        }

        override fun minutesSinceLastChange(): Int {
            val now = nowProvider()
            val from = now - 6L * 60L * 60L * 1000L
            val events = safeFetch(from)
            if (events.isEmpty()) return 0
            val current = events.first()
            val start = current.timestamp
            val durMs = now - start
            return max(0, (durMs / 60000L).toInt())
        }

        private fun safeFetch(from: Long): List<TB> = try {
            fetcher(from)
        } catch (_: Throwable) {
            emptyList()
        }

        private fun isZeroBasal(tb: TB): Boolean {
            return if (tb.isAbsolute) {
                tb.rate <= 0.05     // U/h
            } else {
                tb.rate <= 5.0      // %
            }
        }
    }
}
