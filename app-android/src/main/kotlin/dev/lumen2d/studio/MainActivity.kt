/**
 * Lumen2D Studio — project hub.
 *
 * The screen the app opens on: every project in app storage as a card (scenes, scripts, assets,
 * modified date), plus "new project", "import .lumenzip", "play" and "export". Samples that ship
 * inside the APK are seeded here on first launch, so a fresh install already has three playable
 * games.
 */
package dev.lumen2d.studio

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.lumen2d.android.AndroidFilePicker
import dev.lumen2d.android.AndroidPlatform
import dev.lumen2d.android.LumenAndroid
import dev.lumen2d.android.ProjectArchive
import dev.lumen2d.android.ProjectEntry
import dev.lumen2d.android.ProjectStore
import dev.lumen2d.android.SampleInstaller
import java.io.File

class MainActivity : Activity() {

    private lateinit var platform: AndroidPlatform
    private lateinit var projectsDirectory: File
    private val list = ArrayList<ProjectEntry>()
    private var container: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        platform = LumenAndroid.platform(this)
        projectsDirectory = LumenAndroid.projectsDirectory(platform)
        setContentView(buildUi())
        seedSamples()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ----------------------------------------------------------------- the screen

    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Studio.BG)
        root.setPadding(dp(16f), dp(20f), dp(16f), dp(12f))

        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL

        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titles.addView(title("Lumen2D Studio"))
        titles.addView(dim("A 2D game engine you can edit on the phone it runs on."))
        titles.addView(dim(platform.describe(), 10f))
        header.addView(titles)

        val spacer = View(this)
        spacer.layoutParams = LinearLayout.LayoutParams(0, dp(1f), 1f)
        header.addView(spacer)
        header.addView(pill("${list.size} projects"))
        root.addView(header)
        root.addView(divider())

        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.addView(button("+ New project", primary = true) { newProjectDialog() })
        actions.addView(button("Import .lumenzip") { importArchive() })
        actions.addView(button("Export all") { exportAll() })
        root.addView(actions)
        root.addView(spacer(6f))

        val scroll = ScrollView(this)
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        container = content
        scroll.addView(content)
        scroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        root.addView(scroll)
        return root
    }

    private fun refresh() {
        val content = container ?: return
        content.removeAllViews()
        list.clear()
        list.addAll(ProjectStore.list(projectsDirectory))
        if (list.isEmpty()) {
            content.addView(dim("No projects yet — create one, or import a .lumenzip exported from the editor."))
            return
        }
        for (entry in list) content.addView(projectCard(entry))
    }

    private fun projectCard(entry: ProjectEntry): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = roundedBackground(Studio.PANEL)
        card.setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
        card.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.bottomMargin = dp(10f)
        }

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        info.addView(label(entry.title, 16f, Studio.TEXT, bold = true))
        info.addView(dim("${entry.config.designWidth}×${entry.config.designHeight} · ${entry.config.orientation} · ${entry.scenes} scenes · ${entry.scripts} scripts · ${entry.assets} assets"))
        info.addView(dim("${entry.directory.absolutePath} · ${relativeTime(entry.modified)}", 10f))
        info.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        head.addView(info)
        card.addView(head)
        card.addView(spacer(8f))

        val actions = LinearLayout(this)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.addView(button("Edit", primary = true) { openEditor(entry.directory, play = false) })
        actions.addView(button("Play") { openEditor(entry.directory, play = true) })
        actions.addView(button("Export") { exportProject(entry) })
        actions.addView(button("Rename") { renameDialog(entry) })
        actions.addView(button("Duplicate") {
            ProjectStore.duplicate(entry, projectsDirectory)
            refresh()
        })
        actions.addView(button("Delete") { confirmDelete(entry) })
        card.addView(actions)
        return card
    }

    // ------------------------------------------------------------------- actions

    private fun seedSamples() {
        val created = SampleInstaller.install(platform, projectsDirectory)
        if (created.isNotEmpty()) {
            platform.device.toast("Installed ${created.size} sample game(s)")
        }
    }

    private fun openEditor(directory: File, play: Boolean) {
        val intent = Intent(this, StudioActivity::class.java)
        intent.putExtra(StudioActivity.EXTRA_PROJECT_DIR, directory.absolutePath)
        intent.putExtra(StudioActivity.EXTRA_AUTOPLAY, play)
        startActivity(intent)
    }

    private fun newProjectDialog() {
        val field = textField("", "My Game")
        val layout = LinearLayout(this).also {
            it.orientation = LinearLayout.VERTICAL
            it.setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        }
        layout.addView(dim("The project is created inside app storage and opens in the editor."))
        layout.addView(field)
        AlertDialog.Builder(this)
            .setTitle("New project")
            .setView(layout)
            .setPositiveButton("Create") { _, _ ->
                val name = field.text.toString().trim().ifEmpty { "My Game" }
                val directory = ProjectStore.newProjectFolder(projectsDirectory, name)
                LumenAndroid.createProject(directory, name)
                refresh()
                openEditor(directory, play = false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameDialog(entry: ProjectEntry) {
        val field = textField(entry.title)
        AlertDialog.Builder(this)
            .setTitle("Rename project")
            .setView(field)
            .setPositiveButton("Rename") { _, _ ->
                ProjectStore.rename(entry, field.text.toString().trim().ifEmpty { entry.title })
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(entry: ProjectEntry) {
        AlertDialog.Builder(this)
            .setTitle("Delete '${entry.title}'?")
            .setMessage("The project folder and everything in it is removed from this device.")
            .setPositiveButton("Delete") { _, _ ->
                ProjectStore.delete(entry)
                refresh()
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun importArchive() {
        val picker = AndroidFilePicker(this)
        picker.attach(this, REQUEST_IMPORT)
        val launched = picker.requestFile(AndroidFilePicker.ARCHIVE_MIME) { location ->
            if (location == null) return@requestFile
            val archive = picker.copyToCache(location.uri, location.displayName) ?: return@requestFile
            val imported = ProjectArchive.import(archive, projectsDirectory, location.displayName.substringBeforeLast('.'))
            archive.delete()
            if (imported == null) {
                platform.device.toast("That archive has no project.lumen")
            } else {
                platform.device.toast("Imported '${imported.title}'")
            }
            refresh()
        }
        if (!launched) platform.device.toast("No file picker available on this device")
    }

    private fun exportProject(entry: ProjectEntry) {
        val target = exportFile(entry)
        val files = ProjectArchive.export(entry.directory, target)
        exportDialog(entry, target, files)
    }

    /**
     * Exporting to app storage is only half the job: the user cannot see that folder, so offer the
     * system share sheet straight away (Drive, mail, another phone over Bluetooth).
     */
    private fun exportDialog(entry: ProjectEntry, archive: File, files: Int) {
        val size = (archive.length() + 1023) / 1024
        AlertDialog.Builder(this)
            .setTitle("Exported \u201C${entry.title}\u201D")
            .setMessage("$files files  ·  $size KiB  ·  ${archive.name}\n\n${archive.parentFile?.absolutePath ?: ""}")
            .setPositiveButton("Share") { _, _ ->
                if (!ProjectSharing.share(this, archive, "Lumen2D project: ${entry.title}")) {
                    platform.device.toast("No app on this device can share the archive")
                }
            }
            .setNeutralButton("Play now") { _, _ -> openEditor(entry.directory, play = true) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun exportAll() {
        var total = 0
        for (entry in list) total += ProjectArchive.export(entry.directory, exportFile(entry))
        platform.device.toast("Exported $total files")
    }

    private fun exportFile(entry: ProjectEntry): File {
        val root = getExternalFilesDir(null)?.let { File(it, "exports") } ?: File(filesDir, "exports")
        root.mkdirs()
        return File(root, "${entry.slug}.${ProjectArchive.EXTENSION}")
    }

    private fun relativeTime(millis: Long): String {
        val delta = System.currentTimeMillis() - millis
        val minutes = delta / 60000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 60 * 24 -> "${minutes / 60} h ago"
            else -> "${minutes / (60 * 24)} d ago"
        }
    }

    companion object {
        private const val REQUEST_IMPORT = 0x4C11
    }
}
