package id.trainz.meshviewer.parser

import id.trainz.meshviewer.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/** Defensive parser for the JIRF chunk layouts used by the viewer. */
object TrainzMeshParser {
    private const val MAX_ITEMS = 5_000_000
    private const val ANIMATION_ERROR = "File ini adalah file animasi (.kin), silakan pilih file 3D mesh (.im atau .pm)."
    private val GEOMETRY_TYPES = setOf("IDXM", "IMSH", "PRGM", "PMSH")
    private val CHILD_IDS = setOf("INFO", "MATL", "TEXS", "GEOM", "CHNK", "ATT ", "MATI", "VERT", "TRIS")
    private data class Chunk(val id: String, val data: ByteBuffer)

    fun parse(bytes: ByteArray, displayName: String): TrainzModel {
        val data = when {
            bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte() -> GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            bytes.size >= 2 && bytes[0] == 0x78.toByte() && (bytes[1].toInt() and 0xff) in setOf(0x01, 0x5e, 0x9c, 0xda) -> InflaterInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            else -> bytes
        }
        require(data.size >= 4) { "File terlalu pendek untuk memiliki header mesh." }
        val input = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val first = readAscii(input, 4)
        if (first == "ANIM") throw IllegalArgumentException(ANIMATION_ERROR)
        require(first == "JIRF") {
            val ascii = data.take(4).map { b -> val c = b.toInt() and 0xff; if (c in 32..126) c.toChar() else '.' }.joinToString("")
            val hex = data.take(4).joinToString(" ") { "%02X".format(it.toInt() and 0xff) }
            "Invalid magic: got '$ascii' ($hex), expected 'JIRF'"
        }
        require(data.size >= 12) { "File is shorter than the 12-byte JIRF header." }
        val declared = uint32(input)
        val format = readAscii(input, 4)
        if (format == "ANIM") throw IllegalArgumentException(ANIMATION_ERROR)
        require(declared == (data.size - 8).toLong()) { "Header length does not match file size." }
        require(format in GEOMETRY_TYPES) { "Unsupported mesh type '$format'. Expected IDXM, IMSH, PRGM, or PMSH." }

        val chunks = readChunks(input.slice().order(ByteOrder.LITTLE_ENDIAN))
        val info = chunks.firstOrNull { it.id == "INFO" }?.data?.duplicate()?.order(ByteOrder.LITTLE_ENDIAN)
            ?: error("Missing INFO chunk.")
        require(info.remaining() >= 32) { "INFO chunk is truncated." }
        val subMeshCount = boundedCount(uint32(info), "sub-mesh")
        val attachmentCount = boundedCount(uint32(info), "attachment")
        val bounds = BoundingBox(toOpenGl(readVec3(info)), toOpenGl(readVec3(info)))

        val materials = chunks.filter { it.id == "MATL" }.map { parseMaterial(it.data) }.toMutableList()
        val textureNames = chunks.filter { it.id == "TEXS" }.flatMap { parseStrings(it.data) }
        if (materials.isEmpty()) materials += MaterialInfo("Default")
        textureNames.forEachIndexed { i, name ->
            if (i < materials.size && materials[i].textureRef == null) materials[i] = materials[i].copy(textureRef = name)
        }

        val meshContainers = chunks.filter { it.id == "GEOM" || it.id == "CHNK" }
        val meshes = meshContainers.flatMapIndexed { index, chunk ->
            collectMeshes(chunk, "mesh_$index", format)
        }
        require(meshes.size == subMeshCount || (subMeshCount == 0 && meshes.isEmpty())) {
            "INFO declares $subMeshCount sub-meshes, but ${meshes.size} geometry records were found."
        }
        val attachments = chunks.filter { it.id == "ATT " }.flatMap { parseAttachments(it.data) }
        require(attachmentCount == attachments.size || (attachmentCount == 0 && attachments.isEmpty())) {
            "INFO declares $attachmentCount attachment points, but ${attachments.size} were parsed."
        }
        return TrainzModel(displayName, format, bounds, materials, meshes, attachments)
    }

    private fun collectMeshes(chunk: Chunk, name: String, format: String): List<SubMesh> {
        if (chunk.id == "GEOM" && format == "IDXM" && chunk.data.remaining() >= 8 && uint32At(chunk.data, 0) in 200L..205L) {
            return parseLegacyGeom200(chunk.data, name)
        }
        val payload = unwrapVersion(chunk, format)
        val nested = runCatching { readChunks(payload.duplicate().order(ByteOrder.LITTLE_ENDIAN)) }.getOrNull()
        if (nested != null && nested.isNotEmpty()) {
            val children = nested.filter { it.id == "CHNK" || it.id == "GEOM" }
            if (children.isNotEmpty()) return children.flatMapIndexed { i, child -> collectMeshes(child, "${name}_$i", format) }
            if (nested.any { it.id in GEOM_TAGS }) {
                runCatching { parseGeometryChunks(nested, name) }.getOrNull()?.let { return listOf(it) }
            }
        }
        runCatching { scanGeomPayload(chunk.data, name) }.getOrNull()?.let { return listOf(it) }
        val bytes = ByteArray(minOf(64, chunk.data.remaining())).also { chunk.data.duplicate().get(it) }
        val hex = bytes.joinToString(" ") { "%02X".format(it.toInt() and 255) }
        error("GEOM (size=${chunk.data.remaining()}) unsupported layout. First 64B: $hex")
    }

    private val GEOM_TAGS = setOf("CHNK", "VRTX", "INDX", "TRIS", "FACE", "NORM", "UV00", "VERT")

    private fun parseLegacyGeom200(source: ByteBuffer, name: String): List<SubMesh> {
        val b = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val version = uint32(b); require(version in 200..205)
        val count = boundedCount(uint32(b), "sub-mesh")
        require(count in 1..10000) { "Invalid GEOM sub-mesh count: $count" }
        val result = ArrayList<SubMesh>(count)
        repeat(count) { subIndex ->
            val start = b.position()
            try {
                require(b.remaining() >= 48) { "Truncated sub-mesh header" }
                uint32(b) // flags
                val max = FloatArray(3) { b.float }; val min = FloatArray(3) { b.float }
                val material = uint32(b).toInt(); uint32(b) // material, primitive type
                var parsed: SubMesh? = null
                val layouts = listOf(44 to 2, 48 to 3, 52 to 4, 56 to 5)
                for ((vertexOffset, words) in layouts) {
                    val h = b.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply { position(start + 36) }
                    val counts = LongArray(words) { uint32(h) }
                    val nv = counts[0]
                    if (nv !in 1L..500_000L) continue
                    for (rawCount in counts.drop(1)) for (indices in listOf(rawCount, rawCount * 3L)) {
                        if (indices !in 3L..1_500_000L) continue
                        val vertexStart = start + vertexOffset
                        if (vertexStart > b.limit()) continue
                        for (stride in listOf(32, 24, 36, 40, 44, 48, 52, 64)) {
                            for (indexSize in listOf(2, 4)) {
                                val end = vertexStart.toLong() + nv * stride + indices * indexSize
                                val remainder = b.limit().toLong() - end
                                if (end > b.limit() || remainder < 0 || (subIndex == count - 1 && remainder > 16)) continue
                                val vbuf = b.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply { position(vertexStart); limit(vertexStart + nv.toInt() * stride) }.slice().order(ByteOrder.LITTLE_ENDIAN)
                                if ((0..2).any { min[it] > max[it] }) continue
                                val samples = listOf(0, nv.toInt() / 2, nv.toInt() - 1).distinct()
                                if (samples.any { sample -> (0..2).any { axis -> val x=vbuf.getFloat(sample * stride + axis * 4); !x.isFinite() || x < min[axis] - .5f || x > max[axis] + .5f } }) continue
                                val verts = parseStrideVertices(vbuf)
                                val ibuf = b.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply { position(vertexStart + nv.toInt() * stride); limit(end.toInt()) }
                                val ix = IntArray(indices.toInt()) { if (indexSize == 2) ibuf.short.toInt() and 65535 else uint32(ibuf).toInt() }
                                if (ix.any { it !in 0 until nv.toInt() }) continue
                                parsed = SubMesh("${name}_$subIndex", material, verts, ix)
                                b.position(end.toInt()); break
                            }
                            if (parsed != null) break
                        }
                        if (parsed != null) break
                    }
                    if (parsed != null) break
                }
                result += parsed ?: error("No valid vertex/index layout")
            } catch (e: Exception) {
                val dumpStart = minOf(start + 28, b.limit())
                val bytes = ByteArray(minOf(36, b.limit() - dumpStart).coerceAtLeast(0))
                b.duplicate().apply { position(dumpStart); get(bytes) }
                val hex = bytes.toList().chunked(4).joinToString(" ") { word -> word.joinToString("") { "%02X".format(it.toInt() and 255) } }
                throw IllegalArgumentException("Sub$subIndex fail off28..63: $hex", e)
            }
        }
        return result
    }

    /** Walks tagged records without assuming that the GEOM version header has a fixed length. */
    private fun scanGeomPayload(source: ByteBuffer, name: String): SubMesh {
        val data = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val candidates = mutableListOf<Chunk>()
        for (start in 0..minOf(8, data.remaining() - 8).coerceAtLeast(0)) {
            val id = peekAscii(data, start, 4)
            if (id in GEOM_TAGS) {
                val size = uint32At(data, start + 4)
                if (size <= data.remaining() - start - 8L) {
                    val part = data.duplicate().apply { position(position() + start + 8) }.slice().order(ByteOrder.LITTLE_ENDIAN)
                    part.limit(size.toInt()); candidates += Chunk(id, part)
                }
            }
        }
        var vertexData: ByteBuffer? = null; var indexData: ByteBuffer? = null; var material = 0
        candidates.forEach { c -> when (c.id) {
            "CHNK" -> runCatching { parseGeomChnk(c.data, name) }.getOrNull()?.let { return it }
            "VRTX", "VERT" -> vertexData = c.data
            "INDX", "TRIS", "FACE" -> indexData = c.data
        } }
        val vb = vertexData ?: error("Missing GEOM vertices")
        val ib = indexData ?: error("Missing GEOM indices")
        val v = parseStrideVertices(vb)
        val ix = parseRawIndices(ib, v.size / 8)
        require(ix.all { it in 0 until v.size / 8 })
        return SubMesh(name, material, v, ix)
    }

    private fun parseGeomChnk(src: ByteBuffer, name: String): SubMesh {
        val b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        require(b.remaining() >= 20)
        val version = uint32(b); require(version in 100..65535)
        val material = uint32(b).toInt(); uint32(b) // material and primitive type
        val vertices = boundedCount(uint32(b), "vertex")
        val indices = boundedCount(uint32(b), "index")
        val remaining = b.remaining()
        val vertexBytes = (24..64 step 4).firstOrNull { vertices > 0 && vertices.toLong() * it <= remaining }
            ?: error("Invalid CHNK vertex block")
        val v = parseStrideVertices(b.slice().order(ByteOrder.LITTLE_ENDIAN).apply { limit(vertices * vertexBytes) })
        b.position(b.position() + vertices * vertexBytes)
        val ix = parseRawIndices(b.slice().order(ByteOrder.LITTLE_ENDIAN), v.size / 8, indices)
        return SubMesh(name, material, v, ix)
    }

    private fun parseStrideVertices(src: ByteBuffer): FloatArray {
        val b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        var count = -1
        if (b.remaining() >= 4) { val declared = uint32At(b, 0); if (declared <= MAX_ITEMS && b.remaining() - 4 >= declared * 24L && declared > 0) { count = declared.toInt(); b.position(b.position() + 4) } }
        val stride = (24..64 step 4).firstOrNull { s -> count > 0 && b.remaining().toLong() == count.toLong() * s || count < 0 && b.remaining() % s == 0 }
            ?: error("Unsupported vertex stride")
        if (count < 0) count = b.remaining() / stride
        val out = FloatArray(count * 8)
        repeat(count) { i -> val x=b.float; val y=b.float; val z=b.float; val nx=b.float; val ny=b.float; val nz=b.float; val u=b.float; val v=b.float
            val p=i*8; out[p]=x; out[p+1]=z; out[p+2]=-y; out[p+3]=nx; out[p+4]=nz; out[p+5]=-ny; out[p+6]=u; out[p+7]=v
            b.position(b.position() + stride - 32)
        }
        return out
    }

    private fun parseRawIndices(src: ByteBuffer, vertexCount: Int, expected: Int = -1): IntArray {
        val b=src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val count = if (expected >= 0) expected else if (b.remaining() >= 4 && uint32At(b,0) <= MAX_ITEMS && (b.remaining()-4).toLong() in setOf(uint32At(b,0)*2,uint32At(b,0)*4)) uint32(b).toInt() else -1
        val width = when { count >= 0 && b.remaining().toLong()==count*2L -> 2; count >= 0 && b.remaining().toLong()==count*4L -> 4; b.remaining()%2==0 -> 2; else -> 4 }
        val n = if(count>=0) count else b.remaining()/width
        val out=IntArray(n) { if(width==2) b.short.toInt() and 65535 else uint32(b).toInt() }
        require(out.all { it in 0 until vertexCount }); return out
    }

    /** IDXM/PRGM containers prefix their nested chunk stream with a uint32 version. */
    private fun unwrapVersion(chunk: Chunk, format: String): ByteBuffer {
        val data = chunk.data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (format !in setOf("IDXM", "PRGM") || chunk.id !in setOf("CHNK", "GEOM") || data.remaining() < 12) return data
        val firstId = peekAscii(data, 0, 4)
        if (firstId in CHILD_IDS) return data
        val version = uint32At(data, 0)
        if (version > 0xffffL) return data
        val afterVersion = data.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply { position(4) }.slice().order(ByteOrder.LITTLE_ENDIAN)
        if (!looksLikeChunkSequence(afterVersion)) return data
        return afterVersion
    }

    private fun looksLikeChunkSequence(src: ByteBuffer): Boolean {
        if (src.remaining() < 8) return false
        val id = peekAscii(src, 0, 4)
        if (id.any { it.code !in 32..126 }) return false
        val size = uint32At(src, 4)
        return size <= (src.remaining() - 8).toLong()
    }

    private fun readChunks(src: ByteBuffer): List<Chunk> {
        val b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val result = mutableListOf<Chunk>()
        while (b.remaining() >= 8) {
            val id = readAscii(b, 4)
            val length = uint32(b)
            require(length <= b.remaining().toLong()) { "Chunk $id length $length exceeds remaining file." }
            val part = b.slice().order(ByteOrder.LITTLE_ENDIAN)
            part.limit(length.toInt())
            result += Chunk(id, part)
            b.position(b.position() + length.toInt())
            val padding = (4 - (length.toInt() and 3)) and 3
            require(padding <= b.remaining()) { "Truncated alignment padding after $id." }
            b.position(b.position() + padding)
        }
        require(!b.hasRemaining()) { "Trailing bytes do not form a chunk header." }
        return result
    }

    private fun parseGeometryChunks(chunks: List<Chunk>, name: String): SubMesh {
        var material = 0
        var vertices: FloatArray? = null
        var indices: IntArray? = null
        chunks.forEach { chunk ->
            val b = chunk.data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            when (chunk.id) {
                "MATI" -> { require(b.remaining() >= 4) { "MATI chunk is truncated." }; material = uint32(b).toInt() }
                "VERT" -> vertices = parseVertices(b)
                "TRIS" -> indices = parseIndices(b)
            }
        }
        val v = vertices ?: error("GEOM has no VERT chunk.")
        val ix = indices ?: error("GEOM has no TRIS chunk.")
        require(ix.all { it >= 0 && it < v.size / 8 }) { "TRIS contains an out-of-range vertex index." }
        return SubMesh(name, material, v, ix)
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
        require(b.remaining() >= 4) { "TEXS chunk is truncated." }
        val count = boundedCount(uint32(b), "texture")
        return List(count) { readCString(b) }
    }

    private fun parseVertices(b: ByteBuffer): FloatArray {
        require(b.remaining() >= 4) { "VERT chunk is truncated." }
        val count = boundedCount(uint32(b), "vertex")
        require(b.remaining().toLong() == count.toLong() * 32L) { "VERT payload must contain position, normal and UV (32 bytes/vertex)." }
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

    /** Accepts [width byte + count + values] and [count + values] encodings. */
    private fun parseIndices(src: ByteBuffer): IntArray {
        val b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        require(b.remaining() >= 4) { "TRIS chunk is truncated." }
        val firstByte = b.get(b.position()).toInt() and 0xff
        if ((firstByte == 2 || firstByte == 4) && b.remaining() >= 5) {
            val width = firstByte
            val count = uint32At(b, 1)
            if (count <= MAX_ITEMS && b.remaining().toLong() == 5L + count * width) {
                b.get(); b.int
                return readIndexValues(b, count.toInt(), width)
            }
        }
        val count = uint32(b)
        val width = when (b.remaining().toLong()) {
            count * 2L -> 2
            count * 4L -> 4
            else -> error("TRIS payload length does not match a uint16 or uint32 index array.")
        }
        return readIndexValues(b, boundedCount(count, "index"), width)
    }

    private fun readIndexValues(b: ByteBuffer, count: Int, width: Int): IntArray = IntArray(count) {
        if (width == 2) b.short.toInt() and 0xffff else uint32(b).also { require(it <= Int.MAX_VALUE.toLong()) { "Index exceeds supported signed range." } }.toInt()
    }

    private fun parseAttachments(src: ByteBuffer): List<AttachmentPoint> {
        val b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        require(b.remaining() >= 4) { "ATT chunk is truncated." }
        val count = boundedCount(uint32(b), "attachment")
        return List(count) {
            val name = readCString(b)
            val p = toOpenGl(readVec3(b))
            val rotation = FloatArray(9) { b.float }
            AttachmentPoint(name, p, rotation)
        }
    }

    private fun readVec3(b: ByteBuffer) = Vec3(b.float, b.float, b.float)
    private fun toOpenGl(v: Vec3) = Vec3(v.x, v.z, -v.y)
    private fun boundedCount(v: Long, what: String): Int { require(v <= MAX_ITEMS) { "Unreasonable $what count: $v" }; return v.toInt() }
    private fun uint32(b: ByteBuffer): Long = b.int.toLong() and 0xffffffffL
    private fun uint32At(b: ByteBuffer, offset: Int): Long = b.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply { position(position() + offset) }.int.toLong() and 0xffffffffL
    private fun peekAscii(b: ByteBuffer, offset: Int, count: Int): String = b.duplicate().apply { position(position() + offset) }.let { readAscii(it, count) }
    private fun readAscii(b: ByteBuffer, n: Int): String { require(b.remaining() >= n) { "Unexpected end of file." }; val a = ByteArray(n); b.get(a); return String(a, Charsets.US_ASCII) }
    private fun readCString(b: ByteBuffer): String {
        val out = java.io.ByteArrayOutputStream()
        while (b.hasRemaining()) { val c = b.get(); if (c.toInt() == 0) return out.toString(Charset.forName("UTF-8").name()); out.write(c.toInt()) }
        error("Unterminated string in chunk.")
    }
}
