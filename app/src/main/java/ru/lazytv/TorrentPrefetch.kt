package ru.lazytv

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The torrent search of a title started before the torrent screen is opened (when the card of the title opens or the
 * torrent button gets the focus). The screen then joins the same search: rows that are already found appear at once,
 * the rest arrive as the sources answer. A finished search is kept for ten minutes, so opening the screen again is instant.
 */
object TorrentPrefetch {
    private const val TTL_MS = 10 * 60 * 1000L

    class Job internal constructor() {
        internal val rows = CopyOnWriteArrayList<Torrent>()
        internal val listeners = CopyOnWriteArrayList<(List<Torrent>) -> Unit>()
        internal val finished = CopyOnWriteArrayList<() -> Unit>()
        internal val parts = AtomicInteger(2)
        @Volatile internal var done = false
        @Volatile internal var startedAt = System.currentTimeMillis()
    }

    private val jobs = ConcurrentHashMap<String, Job>()

    private fun key(query: String, name: String, original: String) = "$query|$name|$original".lowercase()

    /** Starts the search, or returns the one already running or finished lately for the same title. */
    fun start(context: Context, query: String, name: String, original: String): Job {
        val app = context.applicationContext
        val k = key(query, name, original)
        synchronized(this) {
            jobs[k]?.let { if (System.currentTimeMillis() - it.startedAt < TTL_MS) return it }
            // a small number of titles at a time: the oldest finished ones go
            if (jobs.size > 6) jobs.entries.filter { it.value.done }.sortedBy { it.value.startedAt }.take(jobs.size - 6).forEach { jobs.remove(it.key) }
            val job = Job()
            jobs[k] = job
            run(app, job, query, name, original)
            return job
        }
    }

    private fun emit(job: Job, items: List<Torrent>) {
        if (items.isEmpty()) return
        synchronized(job) {
            job.rows.addAll(items)
            job.listeners.forEach { it(items) }
        }
    }

    private fun part(job: Job) {
        if (job.parts.decrementAndGet() != 0) return
        synchronized(job) {
            job.done = true
            job.finished.forEach { it() }
        }
    }

    private fun run(app: Context, job: Job, query: String, name: String, original: String) {
        Thread {
            // the year narrows the search, but many releases do not carry it in the title: retry with the name alone
            val rutor = runCatching {
                TorrentSearch.search(query).ifEmpty { if (query != name) TorrentSearch.search(name) else emptyList() }
            }.getOrNull().orEmpty()
            emit(job, rutor)
            part(job)
        }.start()
        Thread {
            runCatching { ExtraTorrents.search(app, name, original) { _, list -> emit(job, list) } }
            part(job)
        }.start()
    }

    /** Joins a search: [onRows] gets the rows found so far at once and the later ones as they come; [onDone] when all sources answered. */
    fun join(job: Job, onRows: (List<Torrent>) -> Unit, onDone: () -> Unit) {
        synchronized(job) {
            if (job.rows.isNotEmpty()) onRows(job.rows.toList())
            job.listeners.add(onRows)
            if (job.done) onDone() else job.finished.add(onDone)
        }
    }
}
