package id.trainz.meshviewer.gl

import android.graphics.Bitmap
import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import id.trainz.meshviewer.model.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

class MeshRenderer(private val context: Context) : GLSurfaceView.Renderer {
    data class Options(val texture: Boolean = true, val wire: Boolean = false, val wireOnly: Boolean = false, val bounds: Boolean = false, val axes: Boolean = true, val attachments: Boolean = false)
    @Volatile var model: TrainzModel? = null
    @Volatile var bitmaps: Map<Int, Bitmap> = emptyMap()
    @Volatile var options = Options()
    @Volatile var onAttachmentProjection: ((List<Pair<String, FloatArray>>) -> Unit)? = null
    private data class GpuMesh(val vao: Int, val count: Int, val material: Int)
    private val gpuMeshes = mutableListOf<GpuMesh>()
    private val textures = mutableMapOf<Int, Int>()
    private var program = 0
    private var mvpLoc = 0; private var modelLoc = 0; private var colorLoc = 0; private var texLoc = 0; private var useTexLoc = 0; private var lightingLoc = 0
    private var width = 1; private var height = 1
    private val projection = FloatArray(16); private val view = FloatArray(16); private val modelMatrix = FloatArray(16); private val mvp = FloatArray(16)
    @Volatile var yaw = .7f; @Volatile var pitch = .35f; @Volatile var zoom = 1.0f; @Volatile var panX = 0f; @Volatile var panY = 0f
    private var uploadedModel: TrainzModel? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(.11f, .13f, .16f, 1f); GLES30.glEnable(GLES30.GL_DEPTH_TEST); GLES30.glEnable(GLES30.GL_CULL_FACE)
        val vertexShader = context.assets.open("shaders/mesh.vert").bufferedReader().use { it.readText() }
        val fragmentShader = context.assets.open("shaders/mesh.frag").bufferedReader().use { it.readText() }
        program = linkProgram(vertexShader, fragmentShader)
        mvpLoc = GLES30.glGetUniformLocation(program, "uMvp"); modelLoc = GLES30.glGetUniformLocation(program, "uModel")
        colorLoc = GLES30.glGetUniformLocation(program, "uColor"); texLoc = GLES30.glGetUniformLocation(program, "uTexture")
        useTexLoc = GLES30.glGetUniformLocation(program, "uUseTexture"); lightingLoc = GLES30.glGetUniformLocation(program, "uLighting")
    }
    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) { width = w.coerceAtLeast(1); height = h.coerceAtLeast(1); GLES30.glViewport(0, 0, width, height) }
    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val current = model ?: return
        if (uploadedModel !== current) upload(current)
        updateMatrices(current)
        GLES30.glUseProgram(program); GLES30.glUniformMatrix4fv(mvpLoc, 1, false, mvp, 0); GLES30.glUniformMatrix4fv(modelLoc, 1, false, modelMatrix, 0)
        GLES30.glUniform1i(texLoc, 0); GLES30.glUniform1i(lightingLoc, 1)
        if (!options.wireOnly) {
            for (mesh in gpuMeshes) {
                val tex = textures[mesh.material]
                GLES30.glUniform1i(useTexLoc, if (options.texture && tex != null) 1 else 0)
                GLES30.glUniform4f(colorLoc, .78f, .8f, .84f, 1f)
                if (tex != null && options.texture) { GLES30.glActiveTexture(GLES30.GL_TEXTURE0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex) }
                GLES30.glBindVertexArray(mesh.vao); GLES30.glDrawElements(GLES30.GL_TRIANGLES, mesh.count, GLES30.GL_UNSIGNED_INT, 0)
            }
        }
        if (options.wire || options.wireOnly) drawEdges(current)
        if (options.bounds) drawLines(boxLines(current.bounds), floatArrayOf(1f, .8f, .1f, 1f))
        if (options.axes) drawLines(axisLines(current.bounds), null)
        if (options.attachments) projectAttachments(current)
    }

    private fun upload(m: TrainzModel) {
        gpuMeshes.forEach { GLES30.glDeleteVertexArrays(1, intArrayOf(it.vao), 0) }; gpuMeshes.clear()
        textures.values.forEach { GLES30.glDeleteTextures(1, intArrayOf(it), 0) }; textures.clear()
        m.subMeshes.forEach { mesh ->
            val vao = IntArray(1); val buffers = IntArray(2); GLES30.glGenVertexArrays(1, vao, 0); GLES30.glGenBuffers(2, buffers, 0); GLES30.glBindVertexArray(vao[0])
            val vb = ByteBuffer.allocateDirect(mesh.vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(mesh.vertices).position(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffers[0]); GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.vertices.size * 4, vb, GLES30.GL_STATIC_DRAW)
            val ib = ByteBuffer.allocateDirect(mesh.indices.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(mesh.indices).position(0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buffers[1]); GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 4, ib, GLES30.GL_STATIC_DRAW)
            val stride = 8 * 4
            GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
            GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
            GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 24)
            gpuMeshes += GpuMesh(vao[0], mesh.indices.size, mesh.materialIndex)
        }
        bitmaps.forEach { (material, bitmap) ->
            val id = IntArray(1); GLES30.glGenTextures(1, id, 0); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR); GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            android.opengl.GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0); GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D); textures[material] = id[0]
        }
        uploadedModel = m
    }
    private fun updateMatrices(m: TrainzModel) {
        Matrix.setIdentityM(modelMatrix, 0)
        val center = m.bounds.center; val radius = maxOf(m.bounds.size.x, m.bounds.size.y, m.bounds.size.z, .1f) * .5f
        val distance = radius * 3.2f / zoom.coerceIn(.15f, 12f)
        val cp = cos(pitch); val sp = sin(pitch); val cy = cos(yaw); val sy = sin(yaw)
        val eye = floatArrayOf(center.x + distance * cp * sy, center.y + distance * sp, center.z + distance * cp * cy)
        val up = floatArrayOf(0f, 1f, 0f); Matrix.setLookAtM(view, 0, eye[0] + panX, eye[1] + panY, eye[2], center.x + panX, center.y + panY, center.z, up[0], up[1], up[2])
        Matrix.perspectiveM(projection, 0, 45f, width.toFloat() / height, radius * .01f, radius * 100f)
        Matrix.multiplyMM(mvp, 0, projection, 0, view, 0); Matrix.multiplyMM(mvp, 0, mvp, 0, modelMatrix, 0)
    }
    private fun drawEdges(m: TrainzModel) { m.subMeshes.forEach { mesh -> val lines = FloatArray(mesh.indices.size * 6); var p=0; for (t in mesh.indices.indices step 3) { val a=mesh.indices[t]; val b=mesh.indices[t+1]; val c=mesh.indices[t+2]; for ((x,y) in listOf(a to b,b to c,c to a)) for (v in intArrayOf(x,y)) { val q=v*8; lines[p++]=mesh.vertices[q]; lines[p++]=mesh.vertices[q+1]; lines[p++]=mesh.vertices[q+2] } }; drawLines(lines.copyOf(p), floatArrayOf(.04f,.05f,.06f,1f)) } }
    private fun boxLines(b: BoundingBox): FloatArray { val pts = listOf(Vec3(b.min.x,b.min.y,b.min.z),Vec3(b.max.x,b.min.y,b.min.z),Vec3(b.max.x,b.max.y,b.min.z),Vec3(b.min.x,b.max.y,b.min.z),Vec3(b.min.x,b.min.y,b.max.z),Vec3(b.max.x,b.min.y,b.max.z),Vec3(b.max.x,b.max.y,b.max.z),Vec3(b.min.x,b.max.y,b.max.z)); val pairs=listOf(0 to 1,1 to 2,2 to 3,3 to 0,4 to 5,5 to 6,6 to 7,7 to 4,0 to 4,1 to 5,2 to 6,3 to 7); return pairs.flatMap { listOf(pts[it.first],pts[it.second]) }.flatMap { listOf(it.x,it.y,it.z) }.toFloatArray() }
    private fun axisLines(b: BoundingBox): FloatArray { val s=maxOf(b.size.x,b.size.y,b.size.z,.5f)*.7f; return floatArrayOf(0f,0f,0f,s,0f,0f, 0f,0f,0f,0f,s,0f, 0f,0f,0f,0f,0f,s) }
    private fun drawLines(points: FloatArray, color: FloatArray?) { if(points.isEmpty())return; val vao=IntArray(1);val vbo=IntArray(1);GLES30.glGenVertexArrays(1,vao,0);GLES30.glGenBuffers(1,vbo,0);GLES30.glBindVertexArray(vao[0]);GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,vbo[0]);val fb=ByteBuffer.allocateDirect(points.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(points).position(0);GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,points.size*4,fb,GLES30.GL_STREAM_DRAW);GLES30.glEnableVertexAttribArray(0);GLES30.glVertexAttribPointer(0,3,GLES30.GL_FLOAT,false,12,0);GLES30.glDisableVertexAttribArray(1);GLES30.glDisableVertexAttribArray(2);GLES30.glUniform1i(useTexLoc,0);GLES30.glUniform1i(lightingLoc,0);if(color!=null)GLES30.glUniform4fv(colorLoc,1,color,0);else { GLES30.glUniform4f(colorLoc,1f,0f,0f,1f);GLES30.glDrawArrays(GLES30.GL_LINES,0,2);GLES30.glUniform4f(colorLoc,0f,1f,0f,1f);GLES30.glDrawArrays(GLES30.GL_LINES,2,2);GLES30.glUniform4f(colorLoc,0f,0f,1f,1f);GLES30.glDrawArrays(GLES30.GL_LINES,4,2);GLES30.glDeleteBuffers(1,vbo,0);GLES30.glDeleteVertexArrays(1,vao,0);GLES30.glUniform1i(lightingLoc,1);return};GLES30.glDrawArrays(GLES30.GL_LINES,0,points.size/3);GLES30.glDeleteBuffers(1,vbo,0);GLES30.glDeleteVertexArrays(1,vao,0);GLES30.glUniform1i(lightingLoc,1) }
    private fun projectAttachments(m: TrainzModel) { val result=m.attachments.mapNotNull { a -> val p=floatArrayOf(a.position.x,a.position.y,a.position.z,1f);val clip=FloatArray(4);Matrix.multiplyMV(clip,0,mvp,0,p,0);if(clip[3]<=0f)null else Pair(a.name,floatArrayOf((clip[0]/clip[3]+1)*width/2f,(1-clip[1]/clip[3])*height/2f)) }; onAttachmentProjection?.invoke(result) }
    private fun linkProgram(vs:String,fs:String):Int { fun shader(type:Int,source:String):Int { val s=GLES30.glCreateShader(type);GLES30.glShaderSource(s,source);GLES30.glCompileShader(s);val ok=IntArray(1);GLES30.glGetShaderiv(s,GLES30.GL_COMPILE_STATUS,ok,0);check(ok[0]!=0){GLES30.glGetShaderInfoLog(s)};return s }; val p=GLES30.glCreateProgram();GLES30.glAttachShader(p,shader(GLES30.GL_VERTEX_SHADER,vs));GLES30.glAttachShader(p,shader(GLES30.GL_FRAGMENT_SHADER,fs));GLES30.glBindAttribLocation(p,0,"aPosition");GLES30.glBindAttribLocation(p,1,"aNormal");GLES30.glBindAttribLocation(p,2,"aUv");GLES30.glLinkProgram(p);val ok=IntArray(1);GLES30.glGetProgramiv(p,GLES30.GL_LINK_STATUS,ok,0);check(ok[0]!=0){GLES30.glGetProgramInfoLog(p)};return p }
}
