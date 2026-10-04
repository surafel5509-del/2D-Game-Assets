/**
 * Lumen2D engine test runner.
 *
 * Run with `tools/build-local.sh test` (kotlinc) or `./gradlew :engine-core:engineTests`.
 * Exits with a non-zero status when any test fails so CI fails loudly.
 */
package dev.lumen2d.core.test

import dev.lumen2d.core.util.Log
import dev.lumen2d.core.util.LogLevel
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    Log.minLevel = if (args.contains("--verbose")) LogLevel.DEBUG else LogLevel.ERROR
    Log.printToStdout = args.contains("--verbose")

    println("Lumen2D engine test suite")
    println("engine-core: math, json, scene graph, physics, tilemaps, particles, animation, audio, assets, library, scripts")
    val started = System.currentTimeMillis()

    coreTests()
    renderTests()
    sceneTests()
    physicsTests()
    tilemapTests()
    particleTests()
    animationTests()
    audioTests()
    assetTests()
    libraryTests()
    scriptTests()

    val elapsed = System.currentTimeMillis() - started
    val code = T.report()
    println("finished in ${elapsed}ms")
    exitProcess(code)
}
