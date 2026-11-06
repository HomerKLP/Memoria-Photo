package app.memoria

/** The steps of one photo in the index loop. [Wait] is the loop waiting for the helper thread's decode. */
enum class Step { Wait, Tag, Objects, Faces, FullDecode, Embed, Video, Database, Save }

/** Time spent in each step of the index loop, so the log says which step is slow on a phone. */
class StepTimes {
    private val ms = DoubleArray(Step.entries.size)
    private var at = 0L

    fun start() { at = System.nanoTime() }

    fun lap(step: Step) {
        val now = System.nanoTime()
        ms[step.ordinal] += (now - at) / 1e6
        at = now
    }

    fun perPhoto(photos: Int) = Step.entries.joinToString(" ") { "${it.name.lowercase()} %.0f".format(ms[it.ordinal] / photos) }
}
