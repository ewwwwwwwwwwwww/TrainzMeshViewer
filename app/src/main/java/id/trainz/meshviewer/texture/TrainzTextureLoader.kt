package id.trainz.meshviewer.texture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import android.content.ContentResolver
import id.trainz.meshviewer.model.MaterialInfo
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object TrainzTextureLoader {
    data class Result(val materials: List<MaterialInfo>, val bitmaps: Map<Int, Bitmap>)

    /** Loads sidecar textures from a SAF folder. File providers that don't expose siblings simply report missing. */
    fun loadFolder(resolver: ContentResolver, folder: DocumentFile, materials: List<MaterialInfo>): Result {
        val files = mutableMapOf<String, DocumentFile>()
        fun collect(dir: DocumentFile, depth: Int) {
            dir.listFiles().forEach { file ->
                if (file.isDirectory) { if (depth < 3) collect(file, depth + 1) }
                else if (file.name?.lowercase()?.let { name -> listOf(".texture.txt", ".tga", ".png", ".jpg", ".bmp").any(name::endsWith) } == true) {
                    file.name?.lowercase()?.let { files.putIfAbsent(it, file) }
                }
            }
        }
        collect(folder, 0)
        val loaded = mutableMapOf<Int, Bitmap>()
        val updated = materials.mapIndexed { index, material ->
            val ref = material.textureRef ?: return@mapIndexed material
            val base = ref.substringAfterLast('/').substringAfterLast('\\')
            val meta = files[base.lowercase()]
            val text = if (base.endsWith(".texture.txt", true)) meta?.let { readText(resolver, it.uri) }.orEmpty() else ""
            val imageName = if (text.isNotEmpty()) Regex("(?im)^\\s*Primary\\s*=\\s*([^\\r\\n#]+)").find(text)?.groupValues?.get(1)?.trim()?.trim('"') ?: base.removeSuffix(".texture.txt") else base
            val image = files[imageName.substringAfterLast('/').lowercase()]
                ?: files[imageName.substringAfterLast('/').lowercase() + ".tga"]
            var bitmap = image?.let { decode(resolver, it) }
            val alphaName = Regex("(?im)^\\s*Alpha\\s*=\\s*([^\\r\\n#]+)").find(text)?.groupValues?.get(1)?.trim()?.trim('"')
            if (bitmap != null && alphaName != null) {
                val alphaFile = files[alphaName.substringAfterLast('/').lowercase()] ?: files[alphaName.substringAfterLast('/').lowercase() + ".tga"]
                val alphaBitmap = alphaFile?.let { decode(resolver, it) }
                if (alphaBitmap != null) bitmap = combineAlpha(bitmap!!, alphaBitmap)
            }
            if (bitmap != null) loaded[index] = bitmap
            material.copy(textureFound = bitmap != null)
        }
        return Result(updated, loaded)
    }

    fun loadSingle(resolver: ContentResolver, uri: Uri, materials: List<MaterialInfo>): Result {
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':').orEmpty()
        val bitmap = runCatching { resolver.openInputStream(uri)?.use(::decodeStream) }.getOrNull()
        val index = materials.indexOfFirst { it.textureRef?.substringAfterLast('/')?.equals(name, true) == true }
        val map = if (bitmap != null && index >= 0) mapOf(index to bitmap) else emptyMap()
        return Result(materials.mapIndexed { i, m -> if (i == index) m.copy(textureFound = bitmap != null) else m }, map)
    }

    private fun decode(resolver: ContentResolver, file: DocumentFile): Bitmap? = runCatching {
        resolver.openInputStream(file.uri)?.use { stream ->
            if (file.name?.endsWith(".tga", true) == true) decodeTga(stream) else decodeStream(stream)
        }
    }.getOrNull()

    private fun decodeStream(input: InputStream): Bitmap? {
        val bytes = input.readBytes()
        if (bytes.size >= 18 && (bytes[2].toInt() == 2 || bytes[2].toInt() == 10)) return decodeTga(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
    private fun readText(resolver: ContentResolver, uri: Uri) = runCatching { resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()

    private fun combineAlpha(source: Bitmap, mask: Bitmap): Bitmap {
        val width = source.width; val height = source.height
        val src = IntArray(width * height); source.getPixels(src, 0, width, 0, 0, width, height)
        val m = Bitmap.createScaledBitmap(mask, width, height, true)
        val alphaPixels = IntArray(width * height); m.getPixels(alphaPixels, 0, width, 0, 0, width, height)
        val result = IntArray(src.size)
        for (i in src.indices) {
            val p = src[i]; val a = alphaPixels[i]
            val maskValue = if (Color.alpha(a) < 255) Color.alpha(a) else Color.red(a)
            result[i] = Color.argb(Color.alpha(p) * maskValue / 255, Color.red(p), Color.green(p), Color.blue(p))
        }
        return Bitmap.createBitmap(result, width, height, Bitmap.Config.ARGB_8888)
    }

    fun decodeTga(input: InputStream): Bitmap? = decodeTga(input.readBytes())
    fun decodeTga(data: ByteArray): Bitmap? {
        if (data.size < 18) return null
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val idLength = b.get(0).toInt() and 255
        val colorMapType = b.get(1).toInt() and 255
        val type = b.get(2).toInt() and 255
        val width = b.getShort(12).toInt() and 0xffff
        val height = b.getShort(14).toInt() and 0xffff
        val depth = b.get(16).toInt() and 255
        val descriptor = b.get(17).toInt() and 255
        if (colorMapType != 0 || type !in setOf(2, 10) || depth !in setOf(24, 32) || width == 0 || height == 0) return null
        val channels = depth / 8
        var offset = 18 + idLength
        if (offset > data.size) return null
        val count = width.toLong() * height
        if (count > 16_000_000) return null
        val pixels = IntArray(count.toInt())
        var out = 0
        fun pixel(): Int? {
            if (offset + channels > data.size) return null
            val blue = data[offset++].toInt() and 255
            val green = data[offset++].toInt() and 255
            val red = data[offset++].toInt() and 255
            val alpha = if (channels == 4) data[offset++].toInt() and 255 else 255
            return Color.argb(alpha, red, green, blue)
        }
        if (type == 2) {
            while (out < pixels.size) pixels[out++] = pixel() ?: return null
        } else {
            while (out < pixels.size) {
                if (offset >= data.size) return null
                val header = data[offset++].toInt() and 255
                val run = (header and 127) + 1
                if (out + run > pixels.size) return null
                if ((header and 128) != 0) {
                    val value = pixel() ?: return null
                    repeat(run) { pixels[out++] = value }
                } else repeat(run) { pixels[out++] = pixel() ?: return null }
            }
        }
        val topOrigin = (descriptor and 0x20) != 0
        val rightOrigin = (descriptor and 0x10) != 0
        if (!topOrigin || rightOrigin) {
            val corrected = IntArray(pixels.size)
            for (y in 0 until height) for (x in 0 until width) {
                val sx = if (rightOrigin) width - 1 - x else x
                val sy = if (topOrigin) y else height - 1 - y
                corrected[y * width + x] = pixels[sy * width + sx]
            }
            System.arraycopy(corrected, 0, pixels, 0, pixels.size)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
