package com.fourinachamber.fortyfive.rendering

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.Texture.TextureFilter
import com.badlogic.gdx.graphics.g2d.BitmapFont
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.g2d.GlyphLayout
import com.badlogic.gdx.utils.Align
import com.badlogic.gdx.utils.TimeUtils
import com.fourinachamber.fortyfive.archipelago.APColors
import com.fourinachamber.fortyfive.screen.general.OnjScreen

object NotificationOverlay {

    private const val DISPLAY_DURATION_MS = 7000L
    private const val SLIDE_DURATION_MS = 200L
    private const val PAUSE_DURATION_MS = 200L

    private const val REST_X = -5f
    private const val HIDDEN_X = -400f

    private val backgroundDelegate = lazy {
        Texture(Gdx.files.internal("textures/notification_background.png"))
    }
    private val background: Texture by backgroundDelegate

    private val foregroundDelegate = lazy {
        Texture(Gdx.files.internal("textures/events/choose_card/cards_background.png"))
    }
    private val foreground: Texture by foregroundDelegate

    private val fontTextureDelegate = lazy {
        Texture(Gdx.files.internal("fonts/roadgeek_bmp.png"), true).also {
            it.setFilter(TextureFilter.MipMapLinearNearest, TextureFilter.Linear)
        }
    }
    private val fontTexture: Texture by fontTextureDelegate

    private val fontDelegate = lazy {
        BitmapFont(Gdx.files.internal("fonts/roadgeek_bmp.fnt"), TextureRegion(fontTexture), false).also {
            it.setUseIntegerPositions(false)
            it.data.markupEnabled = true
        }
    }
    private val font: BitmapFont by fontDelegate

    private val showQueue: ArrayDeque<String> = ArrayDeque()
    private var message: String? = null
    private var showAt: Long = 0L
    private var hideAt: Long = 0L
    private var pauseUntil: Long = 0L

    fun show(message: String) {
        showQueue.addLast(message)
    }

    private fun currentX(now: Long): Float {
        val slideRange = HIDDEN_X - REST_X  // -495f
        return when {
            now < showAt + SLIDE_DURATION_MS -> {
                val p = (now - showAt).toFloat() / SLIDE_DURATION_MS
                HIDDEN_X - slideRange * p * p
            }
            now < hideAt - SLIDE_DURATION_MS -> REST_X
            now < hideAt -> {
                val p = (now - (hideAt - SLIDE_DURATION_MS)).toFloat() / SLIDE_DURATION_MS
                REST_X + slideRange * p * p
            }
            else -> HIDDEN_X
        }
    }

    fun addToRenderPipeline(pipeline: RenderPipeline, @Suppress("UNUSED_PARAMETER") screen: OnjScreen) {
        pipeline.addEarlyBatchTask { batch ->
            val now = TimeUtils.millis()
            if (message != null && now >= hideAt) {
                message = null
                pauseUntil = now + PAUSE_DURATION_MS
                return@addEarlyBatchTask
            }
            if (message == null) {
                if (now < pauseUntil) return@addEarlyBatchTask
                message = showQueue.removeFirstOrNull() ?: return@addEarlyBatchTask
                showAt = now
                hideAt = now + DISPLAY_DURATION_MS
            }
            val msg = message ?: return@addEarlyBatchTask
            val x = currentX(now)
            batch.draw(background, x, 20f, 375f, 110f)
            batch.draw(foreground, x, 25f, 370f, 100f)
            font.data.setScale(0.8f)
            val layout = GlyphLayout(font, APColors.toMarkup(msg), Color.WHITE, 340f, Align.center, true)
            font.draw(batch, layout, x - REST_X + 10f, 75f + layout.height / 2f)
        }
    }

    fun dispose() {
        if (backgroundDelegate.isInitialized()) background.dispose()
        if (foregroundDelegate.isInitialized()) foreground.dispose()
        if (fontDelegate.isInitialized()) font.dispose()
        if (fontTextureDelegate.isInitialized()) fontTexture.dispose()
    }
}