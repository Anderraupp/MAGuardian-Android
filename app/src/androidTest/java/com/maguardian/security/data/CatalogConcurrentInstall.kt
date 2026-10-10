package com.maguardian.security.data

import android.content.Context
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Controls scheduling only; both workers call the real synchronized install. */
internal object CatalogConcurrentInstall {
    data class Result(val version: Int, val installed: Boolean?, val rejection: String?)

    fun race(ctx: Context, lower: String, higher: String, first: Int? = null): List<Result> {
        val versions = listOf(ThreatCatalog.parse(lower).version, ThreatCatalog.parse(higher).version)
        require(first == null || first in versions)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val attempting = CountDownLatch(1)
        val otherThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = listOf(lower, higher).mapIndexed { index, raw ->
                pool.submit(Callable {
                    val version = versions[index]
                    fun install(): Result = try {
                        Result(version, ThreatCatalog.install(ctx, raw), null)
                    } catch (e: IllegalArgumentException) {
                        // Do not turn unexpected exceptions into an accepted outcome.
                        check(e.message == "Versão antiga rejeitada") { "Unexpected rejection: $e" }
                        Result(version, null, e.message)
                    }
                    fun awaitStart() {
                        ready.countDown()
                        check(start.await(20, TimeUnit.SECONDS)) { "Start barrier timed out" }
                    }
                    if (first == version) {
                        // Hold the SAME monitor used by @Synchronized, not a fake installer.
                        // The other worker must actually contend before this one installs.
                        synchronized(ThreatCatalog) {
                            awaitStart()
                            check(attempting.await(20, TimeUnit.SECONDS))
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                            while (otherThread.get().state != Thread.State.BLOCKED) {
                                check(System.nanoTime() < deadline) { "Second installer did not contend" }
                                Thread.sleep(1)
                            }
                            install()
                        }
                    } else {
                        otherThread.set(Thread.currentThread())
                        awaitStart()
                        attempting.countDown()
                        install()
                    }
                })
            }
            check(ready.await(20, TimeUnit.SECONDS)) { "Workers did not reach barrier" }
            start.countDown()
            return futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            pool.shutdownNow()
            check(pool.awaitTermination(30, TimeUnit.SECONDS)) { "Installer worker leaked" }
        }
    }

    fun assertResults(results: List<Result>, low: Int, high: Int, first: Int? = null) {
        org.junit.Assert.assertEquals(Result(high, true, null), results.single { it.version == high })
        val lower = results.single { it.version == low }
        when (first) {
            low -> org.junit.Assert.assertEquals(Result(low, true, null), lower)
            high -> org.junit.Assert.assertEquals(Result(low, null, "Versão antiga rejeitada"), lower)
            null -> org.junit.Assert.assertTrue(
                "Lower version must succeed before higher or be explicitly rejected: $lower",
                lower == Result(low, true, null) ||
                    lower == Result(low, null, "Versão antiga rejeitada"))
            else -> error("Unknown order")
        }
    }
}
