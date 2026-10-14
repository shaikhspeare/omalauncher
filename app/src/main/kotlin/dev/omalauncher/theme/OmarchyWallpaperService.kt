package dev.omalauncher.theme

import android.app.WallpaperColors
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import dev.omalauncher.theme.liquid.LiquidDriver
import dev.omalauncher.theme.liquid.LiquidPalette
import dev.omalauncher.theme.liquid.LiquidSettings
import dev.omalauncher.ui.decodeForScreen
import dev.omalauncher.ui.wallpaperCrop
import java.util.concurrent.Executors

/**
 * Android live wallpaper showing the current Omarchy background. Its main
 * purpose is [Engine.onComputeColors]: Android 12+ derives the system color
 * scheme (quick settings, notifications, Settings, keyboard, dynamic-color
 * apps) from the colors the wallpaper reports, so we report the theme's
 * palette instead of colors sampled from the image.
 */
class OmarchyWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = OmarchyEngine()

    private inner class OmarchyEngine : Engine() {
        private val main = Handler(Looper.getMainLooper())
        private val decoder = Executors.newSingleThreadExecutor()
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        /** The pool of liquid, while style.toml's background.liquid asks for it instead of the picture. */
        private var liquid: LiquidDriver? = null

        private var colors: Map<String, String> = emptyMap()
        private var bitmap: Bitmap? = null
        private var loadedPath: String? = null

        /** Where the launcher's pages are scrolled, 0 to 1: the background slides along. */
        private var xOffset = 0.5f

        private val onThemeChanged: () -> Unit = { main.post { reload() } }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setOffsetNotificationsEnabled(true)
            ThemeStore.changeListeners.add(onThemeChanged)
            reload()
        }

        override fun onDestroy() {
            ThemeStore.changeListeners.remove(onThemeChanged)
            liquid?.destroy()
            liquid = null
            decoder.shutdownNow()
            super.onDestroy()
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            liquid?.resize(width, height)
            draw()
        }

        override fun onOffsetsChanged(
            xOffset: Float,
            yOffset: Float,
            xOffsetStep: Float,
            yOffsetStep: Float,
            xPixelOffset: Int,
            yPixelOffset: Int,
        ) {
            if (xOffset == this.xOffset) return
            liquid?.swipe(xOffset - this.xOffset)
            this.xOffset = xOffset
            if (isVisible) draw()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            liquid?.setVisible(visible)
            if (visible) draw()
        }

        /** The launcher forwards its touches: a finger down, moving or up, as thousandths of the screen. */
        override fun onCommand(
            action: String?,
            x: Int,
            y: Int,
            z: Int,
            extras: Bundle?,
            resultRequested: Boolean,
        ): Bundle? {
            if (action == TOUCH_COMMAND) liquid?.touch(on = z != TOUCH_UP, fx = x / 1000f, fy = y / 1000f)
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        /** Starts or stops the liquid as the style asks, and gives it the theme's colors. */
        private fun applyLiquid() {
            val style = StyleStore.read(this@OmarchyWallpaperService)
            if (!style[Style.LIQUID]) {
                liquid?.destroy()
                liquid = null
                return
            }
            val driver =
                liquid ?: LiquidDriver(this@OmarchyWallpaperService) { surfaceHolder }.also {
                    liquid = it
                    it.resize(surfaceHolder.surfaceFrame.width(), surfaceHolder.surfaceFrame.height())
                    it.setVisible(isVisible)
                }
            driver.configure(
                LiquidSettings(
                    gravity = style[Style.LIQUID_GRAVITY].toFloat(),
                    wobble = style[Style.LIQUID_WOBBLE].toFloat(),
                    shake = style[Style.LIQUID_SHAKE].toFloat(),
                    fps = style[Style.LIQUID_FPS].toInt(),
                ),
                LiquidPalette.from(colors),
            )
        }

        /** Reads the saved theme off the main thread, then redraws with it. */
        private fun reload() {
            // A change posted just before the engine was destroyed.
            if (decoder.isShutdown) return
            decoder.execute {
                val (themeColors, file) = ThemeStore.savedSelection(this@OmarchyWallpaperService) ?: (emptyMap<String, String>() to null)
                val decoded = if (file?.path != loadedPath) file?.let { decodeForScreen(it, this@OmarchyWallpaperService) } else null
                main.post {
                    val colorsChanged = themeColors != colors
                    colors = themeColors
                    if (file?.path != loadedPath) {
                        loadedPath = file?.path
                        bitmap?.recycle()
                        bitmap = decoded
                    }
                    applyLiquid()
                    draw()
                    if (colorsChanged) notifyColorsChanged()
                }
            }
        }

        private fun draw() {
            // The liquid draws itself; the picture is only for when it's off.
            if (liquid != null) return
            val holder = surfaceHolder
            val canvas = runCatching { holder.lockHardwareCanvas() }.getOrNull() ?: return
            try {
                canvas.drawColor(color("background", Color.BLACK))
                bitmap?.let { bmp ->
                    val src = wallpaperCrop(bmp.width, bmp.height, canvas.width, canvas.height, xOffset)
                    canvas.drawBitmap(bmp, src, Rect(0, 0, canvas.width, canvas.height), paint)
                }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        private fun color(
            key: String,
            fallback: Int,
        ): Int = colors[key]?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: fallback

        /** The theme palette, accent first: Android seeds its system colors from the most prominent one. */
        override fun onComputeColors(): WallpaperColors? {
            if (colors.isEmpty()) return null
            val primary = Color.valueOf(color("accent", Color.BLUE))
            val secondary = Color.valueOf(color("magenta", color("accent", Color.BLUE)))
            val tertiary = Color.valueOf(color("background", Color.BLACK))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return WallpaperColors(primary, secondary, tertiary)
            val hints =
                if (colors["mode"] == "light") WallpaperColors.HINT_SUPPORTS_DARK_TEXT else WallpaperColors.HINT_SUPPORTS_DARK_THEME
            return WallpaperColors(primary, secondary, tertiary, hints)
        }
    }

    companion object {
        /** The command the launcher sends its touches with (WallpaperManager.sendWallpaperCommand): z is [TOUCH_DOWN], [TOUCH_MOVE] or [TOUCH_UP]. */
        const val TOUCH_COMMAND = "dev.omalauncher.liquid.touch"
        const val TOUCH_DOWN = 0
        const val TOUCH_MOVE = 1
        const val TOUCH_UP = 2
    }
}
