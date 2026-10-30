package com.example.voice_assistant.benchmark
import com.example.voice_assistant.native_bridge.contracts.PerformanceMetric
interface PerformanceMonitor {
    fun start(): Long
    fun finish(stage: String, started: Long): PerformanceMetric
    fun notRun(stage: String): PerformanceMetric
}
class MonotonicPerformanceMonitor(private val nanoTime: () -> Long = System::nanoTime) : PerformanceMonitor {
    override fun start() = nanoTime()
    override fun finish(stage: String, started: Long) =
        PerformanceMetric(stage, ((nanoTime() - started) / 1_000_000.0).coerceAtLeast(0.0), "measured")
    override fun notRun(stage: String) = PerformanceMetric(stage, null, "not_run")
}
