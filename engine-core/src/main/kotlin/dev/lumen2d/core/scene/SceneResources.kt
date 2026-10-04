/**
 * Lumen2D — shared runtime resources.
 *
 * Nodes receive everything they need (textures, fonts, assets, audio, platform services) from
 * the tree's [SceneResources], which keeps node code free of platform imports and makes the
 * engine trivially testable: swap in an in-memory asset database and a software renderer and
 * the whole game runs headless.
 */
package dev.lumen2d.core.scene

import dev.lumen2d.core.assets.AssetDatabase
import dev.lumen2d.core.audio.AudioMixer
import dev.lumen2d.core.audio.AudioClip
import dev.lumen2d.core.math.Color
import dev.lumen2d.core.platform.Platform
import dev.lumen2d.core.render.BitmapFont
import dev.lumen2d.core.render.FontManager
import dev.lumen2d.core.render.Texture2D
import dev.lumen2d.core.render.TextureManager

class SceneResources(
    var textures: TextureManager = TextureManager(),
    val assets: AssetDatabase? = null,
    val audio: AudioMixer? = null,
    val platform: Platform? = null,
) {
    val fonts: FontManager = assets?.fonts ?: FontManager(textures)

    /**
     * Resolves a texture by raw texture id (`player.png`), asset id (`base:sprites/player.png`)
     * or full virtual path (`lib://packs/base/sprites/player.png`).
     */
    fun texture(id: String?): Texture2D? {
        if (id.isNullOrEmpty()) return null
        textures.getOrNull(id)?.let { return it }
        val meta = assets?.find(id) ?: return null
        return assets.loadTexture(meta.id)
    }

    fun font(id: String?): BitmapFont? {
        // Empty id means "the project's default font" — bootstrapped at startup by
        // [FontManager.ensureFallback], so text is always renderable.
        if (id.isNullOrEmpty()) return fonts.default ?: fonts.ensureFallback()
        fonts.getOrNull(id)?.let { return it }
        assets?.find(id)?.let { meta -> assets.loadFont(meta.id)?.let { return it } }
        return fonts.default
    }

    fun audioClip(id: String?): AudioClip? {
        if (id.isNullOrEmpty()) return null
        assets?.let { db -> return db.loadAudio(id) }
        return null
    }

    /** Clears caches (hot reload / scene change). */
    fun releaseTransient() {
        textures.clear()
        fonts.clear()
    }

    val clearColor: Color = Color(0.06f, 0.07f, 0.11f)
}
