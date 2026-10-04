/** Virtual-file-system plumbing and the sample/pack seeding the Android layer runs on first launch. */
package dev.lumen2d.core.test

import dev.lumen2d.core.game.Project
import dev.lumen2d.core.game.ProjectSeeder
import dev.lumen2d.core.platform.InMemoryFileSystem
import dev.lumen2d.core.platform.PrefixedFileSystem

fun storageTests() {
    section("storage")

    test("a prefixed filesystem answers walk with paths it accepts back") {
        val delegate = InMemoryFileSystem()
        val user = PrefixedFileSystem(delegate, "user")
        user.writeText("saves/slot1.json", "{\"level\":1}")
        user.writeText("packs/base/pack.json", "{}")

        val walked = user.walk("saves")
        eq(listOf("saves/slot1.json"), walked, "walk is relative to the mount, not to the delegate")
        eq("{\"level\":1}", user.readText(walked.first()), "a walked path round-trips through readBytes")
        eq(1, user.walk("").count { it == "saves/slot1.json" }, "walking the mount root sees its own tree")
    }

    test("seeding samples copies whole projects, not their parent folder") {
        // Deliberately the same shape as the APK: one filesystem rooted at `samples/`, with the
        // projects inside it. A filesystem that answered walk with root-prefixed paths made the
        // installer write `samples/hello-lumen2d/…` *inside* the project folder, so the hub listed
        // nothing at all.
        val assets = InMemoryFileSystem()
        val source = PrefixedFileSystem(assets, "samples")
        source.writeText("hello-lumen2d/${Project.MANIFEST}", "{\"name\":\"Hello Lumen2D\"}")
        source.writeText("hello-lumen2d/scenes/level_1.scene.json", "{}")
        source.writeText("hello-lumen2d/scripts/player.lumen", "func _ready() {}\n")
        source.writeText("neon-shooter/${Project.MANIFEST}", "{}")
        source.writeText("neon-shooter/scenes/main.scene.json", "{}")
        val target = InMemoryFileSystem()

        val created = ProjectSeeder.installSamples(source, target)
        eq(listOf("hello-lumen2d", "neon-shooter"), created.sorted(), "both projects were installed")
        check(target.exists("hello-lumen2d/${Project.MANIFEST}"), "the manifest lands at the project root")
        check(target.exists("hello-lumen2d/scenes/level_1.scene.json"), "and so do the scenes")
        check(target.exists("hello-lumen2d/scripts/player.lumen"), "and the scripts")
        check(!target.exists("hello-lumen2d/hello-lumen2d"), "a project is not nested inside itself")
        check(!target.exists("samples/hello-lumen2d"), "the source root does not leak into the target")
        eq("func _ready() {}\n", target.readText("hello-lumen2d/scripts/player.lumen"), "contents survive the copy")
        eq(5, target.walk("").size, "every file of both projects is copied")
    }

    test("seeding is idempotent and leaves edited projects alone") {
        val source = InMemoryFileSystem()
        source.writeText("game/${Project.MANIFEST}", "{}")
        source.writeText("game/scenes/a.scene.json", "{}")
        val target = InMemoryFileSystem()

        eq(listOf("game"), ProjectSeeder.installSamples(source, target))
        target.writeText("game/scenes/a.scene.json", "{\"edited\":true}")
        eq(emptyList<String>(), ProjectSeeder.installSamples(source, target), "nothing to do on the second run")
        eq("{\"edited\":true}", target.readText("game/scenes/a.scene.json"), "user edits survive a re-seed")
    }

    test("a folder without a manifest is never installed as a project") {
        val source = InMemoryFileSystem()
        source.writeText("loose/notes.txt", "hi")
        val target = InMemoryFileSystem()
        eq(emptyList<String>(), ProjectSeeder.installSamples(source, target))
        check(!target.exists("loose"), "the incomplete folder is removed again")
    }

    test("copyFolder copies one pack under a new root") {
        val source = InMemoryFileSystem()
        source.writeText("packs/base/pack.json", "{}")
        source.writeText("packs/base/sprites/player.png", "png")
        source.writeText("packs/other/pack.json", "{}")
        val target = InMemoryFileSystem()

        eq(2, ProjectSeeder.copyFolder(source, "packs/base", target, "base"))
        check(target.exists("base/pack.json"), "copied under the pack id")
        check(target.exists("base/sprites/player.png"), "sub-folders keep their shape")
        check(!target.exists("other"), "sibling packs are left alone")
    }
}
