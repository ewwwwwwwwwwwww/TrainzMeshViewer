package id.trainz.meshviewer.model

data class Vec3(val x: Float, val y: Float, val z: Float)
data class BoundingBox(val min: Vec3, val max: Vec3) {
    val center get() = Vec3((min.x + max.x) / 2f, (min.y + max.y) / 2f, (min.z + max.z) / 2f)
    val size get() = Vec3(max.x - min.x, max.y - min.y, max.z - min.z)
}
data class MaterialInfo(
    val name: String,
    val ambient: FloatArray = floatArrayOf(.2f, .2f, .2f, 1f),
    val diffuse: FloatArray = floatArrayOf(.8f, .8f, .8f, 1f),
    val specular: FloatArray = floatArrayOf(0f, 0f, 0f, 1f),
    val opacity: Float = 1f,
    val textureRef: String? = null,
    val textureFound: Boolean = false
)
data class SubMesh(
    val name: String,
    val materialIndex: Int,
    /** Packed position, normal, UV: 8 floats per vertex. */
    val vertices: FloatArray,
    val indices: IntArray
) {
    val vertexCount get() = vertices.size / 8
    val triangleCount get() = indices.size / 3
}
data class AttachmentPoint(val name: String, val position: Vec3, val rotation: FloatArray)
data class TrainzModel(
    val name: String,
    val format: String,
    val bounds: BoundingBox,
    val materials: List<MaterialInfo>,
    val subMeshes: List<SubMesh>,
    val attachments: List<AttachmentPoint>
) {
    val vertexCount get() = subMeshes.sumOf { it.vertexCount }
    val triangleCount get() = subMeshes.sumOf { it.triangleCount }
}
