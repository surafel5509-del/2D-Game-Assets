/**
 * Lumen2D — minimal zero-dependency test harness.
 *
 * The engine has no third-party dependencies, and its test suite follows suit: tests are plain
 * functions collected into [T], run by `TestMainKt`. This keeps the same suite runnable from
 * Gradle (`:engine-core:engineTests`), from `tools/build-local.sh test` and from CI without
 * downloading JUnit — which matters for the offline/air-gapped builds the engine supports.
 */
package dev.lumen2d.core.test

import kotlin.math.abs

/** Tiny assertion + reporting engine shared by every suite. */
object T {
    private val failures = ArrayList<String>()
    private val sectionFailures = HashMap<String, Int>()
    private var currentSection = "general"
    var assertions: Int = 0
        private set
    var testCount: Int = 0
        private set

    fun section(name: String) {
        currentSection = name
    }

    fun test(name: String, body: () -> Unit) {
        testCount++
        try {
            body()
        } catch (t: Throwable) {
            val message = t.message ?: t.toString()
            failures.add("[$currentSection] $name: $message")
            sectionFailures[currentSection] = (sectionFailures[currentSection] ?: 0) + 1
            println("  ✗ $name — $message")
        }
    }

    fun check(condition: Boolean, message: String) {
        assertions++
        if (!condition) throw AssertionError(message)
    }

    fun eq(expected: Any?, actual: Any?, message: String = "") {
        assertions++
        if (expected != actual) {
            throw AssertionError("${if (message.isEmpty()) "values differ" else message}: expected <$expected> but was <$actual>")
        }
    }

    fun near(expected: Float, actual: Float, tolerance: Float = 0.001f, message: String = "") {
        assertions++
        if (abs(expected - actual) > tolerance) {
            throw AssertionError("${if (message.isEmpty()) "values differ" else message}: expected $expected ±$tolerance but was $actual")
        }
    }

    fun near(expected: Double, actual: Double, tolerance: Double = 0.0001, message: String = "") {
        assertions++
        if (abs(expected - actual) > tolerance) {
            throw AssertionError("${if (message.isEmpty()) "values differ" else message}: expected $expected ±$tolerance but was $actual")
        }
    }

    fun truthy(value: Any?, message: String = "expected a non-null value") {
        assertions++
        if (value == null || value == false) throw AssertionError("$message (was $value)")
    }

    fun throws(message: String, body: () -> Unit) {
        assertions++
        try {
            body()
        } catch (t: Throwable) {
            return
        }
        throw AssertionError("$message: expected an exception but none was thrown")
    }

    /** Runs the suites and returns the process exit code. */
    fun report(title: String = "Lumen2D engine tests"): Int {
        val line = "─".repeat(58)
        println(line)
        println("$title — $testCount tests, $assertions assertions")
        println(line)
        if (failures.isEmpty()) {
            println("✅ all green")
            return 0
        }
        println("❌ ${failures.size} failing test(s):")
        failures.forEach { println("   • $it") }
        sectionFailures.forEach { (section, count) -> println("   ($section: $count)") }
        return 1
    }

    fun failed(): Boolean = failures.isNotEmpty()
}

/** Convenience alias so suites read like English. */
fun test(name: String, body: () -> Unit) = T.test(name, body)
fun section(name: String) = T.section(name)
fun check(condition: Boolean, message: String) = T.check(condition, message)
fun eq(expected: Any?, actual: Any?, message: String = "") = T.eq(expected, actual, message)
fun near(expected: Float, actual: Float, tolerance: Float = 0.001f, message: String = "") = T.near(expected, actual, tolerance, message)
fun near(expected: Double, actual: Double, tolerance: Double = 0.0001, message: String = "") = T.near(expected, actual, tolerance, message)
fun truthy(value: Any?, message: String = "expected a non-null value") = T.truthy(value, message)
fun fails(message: String, body: () -> Unit) = T.throws(message, body)
