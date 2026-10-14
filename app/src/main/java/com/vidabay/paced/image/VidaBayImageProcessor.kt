package com.vidabay.paced.image

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import kotlin.math.max
import kotlin.math.min

object VidaBayImageProcessor {

    // Default target resolution (SN3H-G 400x400 E-Ink hardware)
    const val DEFAULT_DIMENSION = 400
    const val PAYLOAD_SIZE_400 = 40000 // 400 cols * 100 bytes = 40,000 bytes
    const val PAYLOAD_SIZE_240 = 14400 // 240 cols * 60 bytes = 14,400 bytes

    // 4-Color Palette: Black, White, Red, Yellow
    val COLOR_BLACK = Color.rgb(0, 0, 0)
    val COLOR_WHITE = Color.rgb(255, 255, 255)
    val COLOR_RED = Color.rgb(255, 0, 0)
    val COLOR_YELLOW = Color.rgb(255, 255, 0)

    val PALETTE = intArrayOf(COLOR_BLACK, COLOR_WHITE, COLOR_YELLOW, COLOR_RED)

    /**
     * Ensures bitmap is in software memory (Config.ARGB_8888) and not Config.HARDWARE.
     */
    fun ensureSoftwareBitmap(src: Bitmap): Bitmap {
        return if (src.config == Bitmap.Config.HARDWARE) {
            src.copy(Bitmap.Config.ARGB_8888, false) ?: src
        } else {
            src
        }
    }

    /**
     * Resizes and crops a source bitmap to a square bitmap of target dimension.
     */
    fun createSquareBitmap(srcInput: Bitmap, targetDim: Int = DEFAULT_DIMENSION): Bitmap {
        val src = ensureSoftwareBitmap(srcInput)
        val srcWidth = src.width
        val srcHeight = src.height
        val minDim = min(srcWidth, srcHeight)
        val xOffset = (srcWidth - minDim) / 2
        val yOffset = (srcHeight - minDim) / 2

        val cropped = Bitmap.createBitmap(src, xOffset, yOffset, minDim, minDim)
        if (cropped.width == targetDim && cropped.height == targetDim) {
            return cropped
        }
        return Bitmap.createScaledBitmap(cropped, targetDim, targetDim, true)
    }

    /**
     * Performs Floyd-Steinberg dithering against the 4-color palette (Black, White, Yellow, Red).
     */
    fun ditherFloydSteinberg(input: Bitmap, targetDim: Int = DEFAULT_DIMENSION): Bitmap {
        val softInput = ensureSoftwareBitmap(input)
        val base = if (softInput.width != targetDim || softInput.height != targetDim) {
            createSquareBitmap(softInput, targetDim)
        } else {
            softInput
        }

        val outBitmap = Bitmap.createBitmap(targetDim, targetDim, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(targetDim * targetDim)
        base.getPixels(pixels, 0, targetDim, 0, 0, targetDim, targetDim)

        // Error buffers: 3 color channels per pixel (R, G, B)
        val rChan = FloatArray(targetDim * targetDim)
        val gChan = FloatArray(targetDim * targetDim)
        val bChan = FloatArray(targetDim * targetDim)

        for (i in pixels.indices) {
            val c = pixels[i]
            rChan[i] = Color.red(c).toFloat()
            gChan[i] = Color.green(c).toFloat()
            bChan[i] = Color.blue(c).toFloat()
        }

        val outPixels = IntArray(targetDim * targetDim)

        for (y in 0 until targetDim) {
            for (x in 0 until targetDim) {
                val idx = y * targetDim + x
                val oldR = rChan[idx].coerceIn(0f, 255f)
                val oldG = gChan[idx].coerceIn(0f, 255f)
                val oldB = bChan[idx].coerceIn(0f, 255f)

                val newColor = findClosestPaletteColor(oldR, oldG, oldB)
                outPixels[idx] = newColor

                val errR = oldR - Color.red(newColor)
                val errG = oldG - Color.green(newColor)
                val errB = oldB - Color.blue(newColor)

                // Floyd-Steinberg distribution:
                // (x + 1, y    ): 7/16
                // (x - 1, y + 1): 3/16
                // (x    , y + 1): 5/16
                // (x + 1, y + 1): 1/16
                if (x + 1 < targetDim) {
                    val rightIdx = y * targetDim + (x + 1)
                    rChan[rightIdx] += errR * (7f / 16f)
                    gChan[rightIdx] += errG * (7f / 16f)
                    bChan[rightIdx] += errB * (7f / 16f)
                }
                if (y + 1 < targetDim) {
                    if (x - 1 >= 0) {
                        val blIdx = (y + 1) * targetDim + (x - 1)
                        rChan[blIdx] += errR * (3f / 16f)
                        gChan[blIdx] += errG * (3f / 16f)
                        bChan[blIdx] += errB * (3f / 16f)
                    }
                    val bottomIdx = (y + 1) * targetDim + x
                    rChan[bottomIdx] += errR * (5f / 16f)
                    gChan[bottomIdx] += errG * (5f / 16f)
                    bChan[bottomIdx] += errB * (5f / 16f)

                    if (x + 1 < targetDim) {
                        val brIdx = (y + 1) * targetDim + (x + 1)
                        rChan[brIdx] += errR * (1f / 16f)
                        gChan[brIdx] += errG * (1f / 16f)
                        bChan[brIdx] += errB * (1f / 16f)
                    }
                }
            }
        }

        outBitmap.setPixels(outPixels, 0, targetDim, 0, 0, targetDim, targetDim)
        return outBitmap
    }

    private fun findClosestPaletteColor(r: Float, g: Float, b: Float): Int {
        var closestColor = PALETTE[0]
        var minDist = Float.MAX_VALUE
        for (candidate in PALETTE) {
            val dr = r - Color.red(candidate)
            val dg = g - Color.green(candidate)
            val db = b - Color.blue(candidate)
            val dist = dr * dr + dg * dg + db * db
            if (dist < minDist) {
                minDist = dist
                closestColor = candidate
            }
        }
        return closestColor
    }

    /**
     * Packs the dithered image into the VidaBay hardware format:
     * - 400x400: 40,000 bytes (rotated 270°, NO horizontal flip).
     * - 240x240: 14,400 bytes (horizontal flip + rotated 270°).
     *
     * In both cases, 4 vertical pixels per byte in column-major order:
     * byte = (p0 shl 6) or (p1 shl 4) or (p2 shl 2) or p3.
     */
    fun packToVidaBayPayload(dithered: Bitmap, targetDim: Int = DEFAULT_DIMENSION): ByteArray {
        val matrix = Matrix()
        if (targetDim == 240 || targetDim == 200) {
            // Older 240/200 models require horizontal mirror first
            matrix.postScale(-1f, 1f, targetDim / 2f, targetDim / 2f)
        }
        // Both models require 270° clockwise rotation for panel scan lines
        matrix.postRotate(270f, targetDim / 2f, targetDim / 2f)

        val transformed = Bitmap.createBitmap(dithered, 0, 0, targetDim, targetDim, matrix, false)

        val pixels = IntArray(targetDim * targetDim)
        transformed.getPixels(pixels, 0, targetDim, 0, 0, targetDim, targetDim)

        val payloadSize = targetDim * (targetDim / 4)
        val payload = ByteArray(payloadSize)
        var byteIdx = 0

        for (x in 0 until targetDim) {
            for (yChunk in 0 until (targetDim / 4)) {
                var packedByte = 0
                for (p in 0 until 4) {
                    val y = yChunk * 4 + p
                    val color = pixels[y * targetDim + x]
                    val code = colorTo2Bit(color)
                    packedByte = (packedByte shl 2) or (code and 0x03)
                }
                payload[byteIdx++] = packedByte.toByte()
            }
        }

        return payload
    }

    /**
     * VidaBay 2-bit color encoding:
     * Black  = 0b00 (0)
     * White  = 0b01 (1)
     * Yellow = 0b10 (2)
     * Red    = 0b11 (3)
     */
    fun colorTo2Bit(color: Int): Int {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)

        return if (max(r, max(g, b)) < 128) {
            0 // Black (00)
        } else if (r > 128 && b < 128 && g < 128) {
            3 // Red (11)
        } else if (r <= 128 || g <= 128 || b >= 128) {
            1 // White (01)
        } else {
            2 // Yellow (10)
        }
    }
}
