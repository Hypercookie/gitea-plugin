package com.github.hypercookie.giteaplugin.util

import com.intellij.util.io.HttpRequests
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.awt.Image
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Downloads small images a Gitea server points at (avatars, custom emoji).
 *
 * The URL is server data, so only http(s) is fetched, through the
 * platform's [HttpRequests] (IDE proxy and certificate settings, timeouts).
 * The download and the decoded image are both size-capped, so a huge file
 * or an image bomb can't exhaust the heap. Any failure gives null, and the
 * caller keeps its placeholder icon.
 */
object GiteaImages {

    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_DIMENSION = 2048
    private const val TIMEOUT_MS = 15_000

    suspend fun load(url: String?): Image? {
        if (!GiteaBrowser.isSafe(url)) return null
        return try {
            runInterruptible(Dispatchers.IO) {
                val bytes = HttpRequests.request(url!!.trim())
                    .connectTimeout(TIMEOUT_MS)
                    .readTimeout(TIMEOUT_MS)
                    .connect { it.inputStream.readNBytes(MAX_BYTES + 1) }
                if (bytes.size > MAX_BYTES) null else decode(bytes)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /** Decodes [bytes] unless the image is larger than [MAX_DIMENSION] in
     * either direction, checked from its header before any pixels are read. */
    internal fun decode(bytes: ByteArray): Image? {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                if (reader.getWidth(0) > MAX_DIMENSION || reader.getHeight(0) > MAX_DIMENSION) return null
                return reader.read(0)
            } finally {
                reader.dispose()
            }
        }
    }
}
