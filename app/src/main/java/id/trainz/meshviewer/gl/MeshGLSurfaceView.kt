package id.trainz.meshviewer.gl

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import kotlin.math.hypot

class MeshGLSurfaceView(context: Context, suppliedRenderer: MeshRenderer? = null) : GLSurfaceView(context) {
    val renderer: MeshRenderer = suppliedRenderer ?: MeshRenderer(context)
    private var lastX = 0f; private var lastY = 0f; private var lastDistance = 0f; private var lastMidX = 0f; private var lastMidY = 0f
    init { setEGLContextClientVersion(3); setRenderer(renderer); renderMode = RENDERMODE_CONTINUOUSLY }
    fun resetCamera() { renderer.yaw = .7f; renderer.pitch = .35f; renderer.zoom = 1f; renderer.panX = 0f; renderer.panY = 0f }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; return true }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) { lastDistance = distance(event); lastMidX = (event.getX(0) + event.getX(1)) / 2; lastMidY = (event.getY(0) + event.getY(1)) / 2 }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1) {
                val dx = event.x - lastX; val dy = event.y - lastY
                renderer.yaw += dx * .008f; renderer.pitch = (renderer.pitch + dy * .008f).coerceIn(-1.45f, 1.45f)
                lastX = event.x; lastY = event.y
            } else if (event.pointerCount >= 2) {
                val d = distance(event); if (lastDistance > 0f) renderer.zoom = (renderer.zoom * d / lastDistance).coerceIn(.15f, 12f); lastDistance = d
                val mx = (event.getX(0) + event.getX(1)) / 2; val my = (event.getY(0) + event.getY(1)) / 2
                val scale = 2f / height.coerceAtLeast(1) / renderer.zoom
                renderer.panX -= (mx - lastMidX) * scale; renderer.panY += (my - lastMidY) * scale
                lastMidX = mx; lastMidY = my
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> { lastDistance = 0f }
        }
        return true
    }
    private fun distance(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)).coerceAtLeast(1f)
}
