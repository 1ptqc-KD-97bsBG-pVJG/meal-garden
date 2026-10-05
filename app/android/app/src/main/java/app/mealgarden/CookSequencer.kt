package app.mealgarden

/** Same millisecond clock and zero-based indices as companion/cook-sequencer.mjs. */
data class CookStep(val minutes: Double? = null, val passiveMinutes: Double = 0.0,
                    val startMinute: Double? = null, val equipment: String = "", val text: String = "",
                    val timerMinutes: Double? = null)
data class CookProgress(val startedAt: Long? = null, val doneAt: Long? = null)
data class CookRunning(val step: Int, val endsAt: Long)
data class CookSequence(val now: Int?, val running: List<CookRunning>, val next: Int?, val waitingOn: CookRunning?)

object CookSequencer {
    fun passiveMillis(step: CookStep): Long = (step.passiveMinutes * 60000).toLong()
    // Starting a passive step confirms its hands-on setup is done.
    fun end(step: CookStep, state: CookProgress): Long? = state.startedAt?.plus(passiveMillis(step))
    fun appliance(step: CookStep): String? {
        val head = step.equipment.substringBefore('·').trim().lowercase()
        val name = listOf("duxtop" to "Duxtop", "ninja" to "Ninja", "oven" to "Oven", "microwave" to "Microwave", "air fryer" to "Air fryer")
            .firstOrNull { head.startsWith(it.first) }?.second
        return if (head.isEmpty() || head.startsWith("stove") || Regex("^(counter|worktop|prep|cutting board)\\b").containsMatchIn(head)) null else name ?: head
    }
    private fun overlaps(a: CookStep, b: CookStep): Boolean {
        val start = a.startMinute ?: return false
        val minutes = a.minutes ?: return false
        val later = b.startMinute ?: return false
        if (b.minutes == null || a.passiveMinutes == 0.0) return false
        val passiveStart = start + minutes - a.passiveMinutes
        val laterStart = if (later == start) passiveStart else later
        return laterStart >= passiveStart && laterStart < start + minutes
    }
    fun sequence(steps: List<CookStep>, states: List<CookProgress>, time: Long): CookSequence {
        require(time >= 0 && states.size <= steps.size)
        steps.forEach { s ->
            listOfNotNull(s.minutes, s.passiveMinutes, s.startMinute, s.timerMinutes).forEach { require(it.isFinite() && it >= 0) }
            require(s.passiveMinutes <= (s.minutes ?: 0.0))
        }
        val progress = steps.indices.map { states.getOrElse(it) { CookProgress() } }
        progress.forEach { p ->
            listOfNotNull(p.startedAt, p.doneAt).forEach { require(it in 0..time) }
            require(p.startedAt == null || p.doneAt == null || p.doneAt >= p.startedAt)
        }
        fun resolved(i: Int) = progress[i].doneAt != null ||
            (passiveMillis(steps[i]) > 0 && end(steps[i], progress[i])?.let { it <= time } == true)
        val running = steps.indices.filter { !resolved(it) && progress[it].startedAt != null && passiveMillis(steps[it]) > 0 }
            .map { CookRunning(it, end(steps[it], progress[it])!!) }.sortedWith(compareBy({ it.endsAt }, { it.step }))
        val pending = steps.indices.filter { progress[it].startedAt == null && progress[it].doneAt == null }
        val active = steps.indices.firstOrNull { progress[it].startedAt != null && progress[it].doneAt == null && passiveMillis(steps[it]) == 0L }
        fun blockers(i: Int): Set<Int> {
            val result = (0 until i).filter { !resolved(it) && !overlaps(steps[it], steps[i]) }.toMutableSet()
            val appliance = appliance(steps[i])
            if (appliance != null) running.filter { appliance(steps[it.step]) == appliance }.forEach { result.add(it.step) }
            return result
        }
        val now = active ?: pending.firstOrNull { blockers(it).isEmpty() }
        val next = pending.firstOrNull { it != now }
        val blocking = pending.flatMap { blockers(it) }.toSet()
        val waiting = if (now == null) running.firstOrNull { pending.isEmpty() || it.step in blocking } else null
        return CookSequence(now, running, next, waiting)
    }
}
