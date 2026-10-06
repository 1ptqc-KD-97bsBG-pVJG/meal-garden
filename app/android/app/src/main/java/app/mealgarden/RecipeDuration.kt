package app.mealgarden

import org.json.JSONObject
import kotlin.math.ceil

/** Presentation only; waits and appliance timelines continue to use canonical step values. */
fun recipeDuration(recipe: JSONObject): String {
    val steps = recipe.a("steps").objects()
    val timed = steps.filter { it.has("minutes") && it.optDouble("minutes", -1.0).let { n -> n.isFinite() && n >= 0 } }
    if (timed.isEmpty()) return naturalDuration(recipe.optDouble("total_minutes", 0.0))
    if (timed.size != steps.size) {
        val total = naturalDuration(recipe.optDouble("total_minutes", 0.0))
        return total.ifBlank { "Time incomplete" }
    }
    val handsOn = timed.sumOf { (it.optDouble("minutes") - it.optDouble("passive_minutes", 0.0)).coerceAtLeast(0.0) }
    val waits = timed.filter { it.optDouble("passive_minutes", 0.0) > 0 }
    val hasTimeline = timed.all { it.has("start_minute") && it.optDouble("start_minute", -1.0) >= 0 }
    // Hands-on effort may overlap a running timer. Only uncovered wall time counts as waiting.
    val waiting = if (hasTimeline) {
        val intervals = timed.map { step ->
            val start = step.optDouble("start_minute")
            start to start + (step.optDouble("minutes") - step.optDouble("passive_minutes", 0.0)).coerceAtLeast(0.0)
        }.filter { it.second > it.first }.sortedBy { it.first }
        var activeEnd = Double.NEGATIVE_INFINITY
        var activeWall = 0.0
        for ((start, end) in intervals) {
            activeWall += (end - maxOf(start, activeEnd)).coerceAtLeast(0.0)
            activeEnd = maxOf(activeEnd, end)
        }
        val elapsed = timed.maxOf { it.optDouble("start_minute") + it.optDouble("minutes") } - timed.minOf { it.optDouble("start_minute") }
        (elapsed - activeWall).coerceAtLeast(0.0)
    } else waits.sumOf { it.optDouble("passive_minutes") }
    if (waiting <= 0) return naturalDuration(handsOn)
    fun waitKind(step: JSONObject): String {
        val text = (step.s("title") + " " + step.s("text")).lowercase()
        return when {
            Regex("\\brest(?:ing)?\\b").containsMatchIn(text) -> "rest"
            Regex("\\bchill(?:ing)?\\b|\\brefrigerat").containsMatchIn(text) -> "chill"
            Regex("\\bsoak(?:ing)?\\b").containsMatchIn(text) -> "soak"
            else -> "wait"
        }
    }
    val kinds = waits.map(::waitKind).distinct()
    val singleOvernight = waits.singleOrNull()?.let {
        val duration = it.optDouble("passive_minutes")
        duration in 360.0..960.0 && waiting <= duration &&
            Regex("\\bovernight\\b").containsMatchIn((it.s("title") + " " + it.s("text")).lowercase())
    } == true
    val waitingLabel = if (singleOvernight) "overnight" else "${naturalDuration(waiting)} ${kinds.singleOrNull() ?: "wait"}"
    return listOf(naturalDuration(handsOn), waitingLabel).filter { it.isNotBlank() }.joinToString(" + ")
}

fun naturalDuration(minutes: Double): String {
    if (!minutes.isFinite() || minutes <= 0) return ""
    val rounded = ceil(minutes).toInt()
    return when {
        rounded < 60 -> "$rounded min"
        rounded < 1440 -> (rounded / 60).toString() + " h" + if (rounded % 60 > 0) " ${rounded % 60} min" else ""
        else -> (rounded / 1440).toString() + (if (rounded / 1440 == 1) " day" else " days") + (if (rounded % 1440 >= 60) " ${rounded % 1440 / 60} h" else "")
    }
}
