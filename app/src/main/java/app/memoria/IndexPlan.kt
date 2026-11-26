package app.memoria

import java.io.File

/** Model files the app may count work for: all there, and not [broken] (failed to load) in this exact copy. A new download tries again. */
fun modelsUsable(files: List<File>, broken: Set<String>): Boolean = files.all { it.exists() } && modelStamp(files) !in broken

/** One copy of [files]: names, sizes and dates. */
fun modelStamp(files: List<File>): String = files.joinToString(";") { "${it.name}:${it.length()}:${it.lastModified()}" }

/** A run's work: [gone] indexed photos no longer on the phone, [todo] new photos, [catchUp] indexed photos missing a vector or boxes. */
class IndexPlan(val gone: Set<Long>, val todo: List<MediaPhoto>, val catchUp: List<MediaPhoto>)

/** [tagging] and [boxing]: the tagger and the object models are on the phone, so photos without their output need a catch-up. */
fun indexPlan(phone: List<MediaPhoto>, done: Set<Long>, tagged: Set<Long>, boxed: Set<Long>, tagging: Boolean, boxing: Boolean): IndexPlan {
    val ids = phone.map { it.id }.toSet()
    return IndexPlan(
        gone = done + tagged - ids,
        todo = phone.filter { it.id !in done },
        // a video is never tagged or boxed again: it has no vector and no per-photo boxes
        catchUp = phone.filter { !it.video && it.id in done && ((tagging && it.id !in tagged) || (boxing && it.id !in boxed)) },
    )
}
