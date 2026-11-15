package id.trainz.meshviewer.parser

import id.trainz.meshviewer.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/** Strict reader for the documented chunk envelope used by this viewer. Rejects unknown geometry layouts. */
object TrainzMeshParser {
    private const val MAX_ITEMS = 5_000_000
    private data class Chunk(val id: String, val data: ByteBuffer)

    fun parse(bytes: ByteArray, displayName: String): TrainzModel {
        require(bytes.size >= 12) { "File is shorter than the 12-byte JIRF header." }
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(readAscii(input, 4) == "JIRF") { "Invalid magic; expected JIRF." }
        val declared = uint32(input).toLong()
        require(declared == (bytes.size - 8).toLong()) { "Header length does not match file size." }
        val format = readAscii(input, 4)
        require(format == "IMSH" || format == "PMSH") { "Unsupported mesh type '$format'." }
        val chunks = readChunks(input.slice().order(ByteOrder.LITTLE_ENDIAN))
        val info = chunks.firstOrNull { it.id == "INFO" }?.data?.duplicate()?.order(ByteOrder.LITTLE_ENDIAN)
            ?: error("Missing INFO chunk.")
        val subMeshCount = boundedCount(uint32(info), "sub-mesh")
        val attachmentCount = boundedCount(uint32(info), "attachment")
        val bounds = BoundingBox(toOpenGl(readVec3(info)), toOpenGl(readVec3(info)))
        val materials = chunks.filter { it.id == "MATL" }.map { parseMaterial(it.data) }.toMutableList()
        val textures = chunks.filter { it.id == "TEXS" }.flatMap { parseStrings(it.data) }
        if (materials.isEmpty()) materials += MaterialInfo("Default")
        textures.forEachIndexed { i, name -> if (i < materials.size && materials[i].textureRef == null) materials[i] = materials[i].copy(textureRef = name) }
        val geomChunks = chunks.filter { it.id == "GEOM" }
        val meshes = geomChunks.mapIndexed { i, c -> parseGeometry(c.data, "mesh_$i") }
        require(meshes.size == subMeshCount || (subMeshCount == 0 && meshes.isEmpty())) {
            "INFO declares $subMeshCount sub-meshes, but ${meshes.size} GEOM chunks were found."
        }
        val attachments = chunks.filter { it.id == "ATT " }.flatMap { parseAttachments(it.data) }
        if (attachmentCount != attachments.size) {
            require(attachments.isEmpty() && attachmentCount == 0) {
                "INFO declares $attachmentCount attachment points, but ${attachments.size} were parsed."
            }
        }
        return TrainzModel(displayName, format, bounds, materials, meshes, attachments)
    }

    private fun readChunks(b: ByteBuffer): List<Chunk> {
        val result = mutableListOf<Chunk>()
        while (b.remaining() >= 8) {
            val id = readAscii(b, 4)
            val len = uint32(b).toLong()
            require(len <= b.remaining().toLong()) { "Chunk $id length $len exceeds remaining file." }
            val part = b.slice().order(ByteOrder.LITTLE_ENDIAN)
            part.limit(len.toInt())
            result += Chunk(id, part)
            b.position(b.position() + len.toInt())
            val padding = (4 - (len.toInt() and 3)) and 3
            require(padding <= b.remaining()) { "Truncated alignment padding after $id." }
            b.position(b.position() + padding)
        }
        require(!b.hasRemaining()) { "Trailing bytes do not form a chunk header." }
        return result
    }

    private fun parseMaterial(src: ByteBuffer): MaterialInfo {
        val b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val name = readCString(b)
        require(b.remaining() >= 52) { "MATL '$name' is shorter than its color/opacity fields." }
        val ambient = FloatArray(4) { b.float }; val diffuse = FloatArray(4) { b.float }
        val specular = FloatArray(4) { b.float }; val opacity = b.float
        val texture = if (b.hasRemaining()) readCString(b).ifBlank { null } else null
        return MaterialInfo(name, ambient, diffuse, specular, opacity.coerceIn(0f, 1f), texture)
    }

    private fun parseStrings(src: ByteBuffer): List<String> {
        val b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val count = boundedCount(uint32(b), "texture")
        return List(count) { readCString(b) }
    }

    private fun parseGeometry(src: ByteBuffer, name: String): SubMesh {
        val b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var material = 0
        var verts: FloatArray? = null
        var inds: IntArray? = null
        while (b.remaining() >= 8) {
            val id = readAscii(b, 4); val size = uint32(b).toLong()
            require(size <= b.remaining().toLong()) { "GEOM/$id exceeds its parent." }
            val c = b.slice().order(ByteOrder.LITTLE_ENDIAN); c.limit(size.toInt())
            when (id) {
                "MATI" -> material = uint32(c).toInt()
                "VERT" -> verts = parseVertices(c)
                "TRIS" -> inds = parseIndices(c)
            }
            b.position(b.position() + size.toInt() + ((4 - (size.toInt() and 3)) and 3))
        }
        val v = verts ?: error("GEOM has no VERT chunk.")
        val ix = inds ?: error("GEOM has no TRIS chunk.")
        require(ix.all { it in 0 until v.size / 8 }) { "TRIS contains an out-of-range vertex index." }
        return SubMesh(name, material, v, ix)
    }

    private fun parseVertices(b: ByteBuffer): FloatArray {
        val count = boundedCount(uint32(b), "vertex")
        val stride = 32
        require(b.remaining().toLong() == count.toLong() * stride) { "VERT payload must contain position, normal and UV (32 bytes/vertex)." }
        val out = FloatArray(count * 8)
        for (i in 0 until count) {
            val x = b.float; val y = b.float; val z = b.float
            val nx = b.float; val ny = b.float; val nz = b.float
            val u = b.float; val v = b.float
            val p = i * 8
            out[p] = x; out[p + 1] = z; out[p + 2] = -y
            out[p + 3] = nx; out[p + 4] = nz; out[p + 5] = -ny
            out[p + 6] = u; out[p + 7] = v
        }
        return out
    }

    private fun parseIndices(b: ByteBuffer): IntArray {
        val width = b.get().toInt() and 255
        val count = boundedCount(uint32(b), "index")
        require(width == 2 || width == 4) { "TRIS index width must be 2 or 4 bytes." }
        require(b.remaining().toLong() == count.toLong() * width) { "TRIS payload length mismatch." }
        return IntArray(count) { if (width == 2) (b.short.toInt() and 0xffff) else uint32(b).toInt() }
    }

    private fun parseAttachments(src: ByteBuffer): List<AttachmentPoint> {
        val b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val count = boundedCount(uint32(b), "attachment")
        return List(count) {
            val name = readCString(b)
            val p0 = readVec3(b)
            val rotation = FloatArray(9) { b.float }
            AttachmentPoint(name, Vec3(p0.x, p0.z, -p0.y), rotation)
        }
    }

    private fun readVec3(b: ByteBuffer) = Vec3(b.float, b.float, b.float)
    private fun toOpenGl(v: Vec3) = Vec3(v.x, v.z, -v.y)
    private fun boundedCount(v: Long, what: String): Int { require(v <= MAX_ITEMS) { "Unreasonable $what count: $v" }; return v.toInt() }
    private fun uint32(b: ByteBuffer): Long = b.int.toLong() and 0xffffffffL
    private fun readAscii(b: ByteBuffer, n: Int): String { require(b.remaining() >= n) { "Unexpected end of file." }; val a = ByteArray(n); b.get(a); return String(a, Charsets.US_ASCII) }
    private fun readCString(b: ByteBuffer): String {
        val out = java.io.ByteArrayOutputStream()
        while (b.hasRemaining()) { val c = b.get(); if (c.toInt() == 0) return out.toString(Charset.forName("UTF-8").name()); out.write(c.toInt()) }
        error("Unterminated string in chunk.")
    }
}
