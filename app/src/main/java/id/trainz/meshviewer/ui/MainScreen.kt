package id.trainz.meshviewer.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.trainz.meshviewer.gl.MeshGLSurfaceView
import id.trainz.meshviewer.gl.MeshRenderer
import id.trainz.meshviewer.model.TrainzModel

@Composable
fun MainScreen(
    model: TrainzModel?, glView: MeshGLSurfaceView?, renderer: MeshRenderer?,
    attachmentPositions: List<Pair<String, FloatArray>>,
    onOpenFile: () -> Unit, onOpenFolder: () -> Unit, onFolderChosen: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(true) }
    var texture by remember { mutableStateOf(true) }; var wire by remember { mutableStateOf(false) }; var wireOnly by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(false) }; var axes by remember { mutableStateOf(true) }; var attachments by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color(0xff1c2026))) {
        if (glView != null) AndroidViewCompat(glView, Modifier.fillMaxSize())
        if (model == null) Text("Pilih berkas .im/.pm atau folder aset Trainz", color = Color.White, modifier = Modifier.align(Alignment.Center))
        if (attachments && model != null) Canvas(Modifier.fillMaxSize()) {
            val paint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 32f; isFakeBoldText = true; setShadowLayer(4f, 1f, 2f, android.graphics.Color.BLACK) }
            attachmentPositions.forEach { (name, xy) -> drawContext.canvas.nativeCanvas.apply { drawCircle(xy[0], xy[1], 8f, Paint().apply { color = android.graphics.Color.YELLOW }); drawText(name, xy[0] + 12f, xy[1] - 8f, paint) } }
        }
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Color(0xbb101318)).padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TrainzMeshViewer", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenFile) { Text("Berkas") }; TextButton(onClick = onOpenFolder) { Text("Folder") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(texture, { texture = !texture; renderer?.let { it.options = it.options.copy(texture = texture) } }, label = { Text("Texture") })
                FilterChip(wire, { wire = !wire; renderer?.let { it.options = it.options.copy(wire = wire) } }, label = { Text("Wire") })
                FilterChip(wireOnly, { wireOnly = !wireOnly; if (wireOnly) wire = false; renderer?.let { it.options = it.options.copy(wireOnly = wireOnly, wire = wire) } }, label = { Text("Wire only") })
                FilterChip(bounds, { bounds = !bounds; renderer?.let { it.options = it.options.copy(bounds = bounds) } }, label = { Text("Bounds") })
                FilterChip(axes, { axes = !axes; renderer?.let { it.options = it.options.copy(axes = axes) } }, label = { Text("Axes") })
                FilterChip(attachments, { attachments = !attachments; renderer?.let { it.options = it.options.copy(attachments = attachments) } }, label = { Text("Attach") })
                TextButton(onClick = { glView?.resetCamera() }) { Text("Reset") }
            }
        }
        if (model != null) Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = Color(0xee171a20), shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${model.name}  •  ${model.format}", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Tutup" else "Info") }
                }
                Text("${model.subMeshes.size} sub-mesh • ${model.vertexCount} vertex • ${model.triangleCount} segitiga • ukuran ${"%.2f × %.2f × %.2f m".format(model.bounds.size.x, model.bounds.size.y, model.bounds.size.z)}", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                if (expanded) LazyColumn(Modifier.heightIn(max = 220.dp).padding(top = 6.dp)) {
                    items(model.materials) { m -> Text("Material ${m.name}: ${m.textureRef ?: "tanpa tekstur"} — ${if (m.textureFound) "ditemukan" else "tidak ditemukan"}", color = Color.White, style = MaterialTheme.typography.bodySmall) }
                    items(model.attachments) { a -> Text("Attachment ${a.name}: (${a.position.x}, ${a.position.y}, ${a.position.z})", color = Color.LightGray, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    LaunchedEffect(attachments) { renderer?.let { it.options = it.options.copy(attachments = attachments) } }
}

@Composable
private fun AndroidViewCompat(view: android.view.View, modifier: Modifier) {
    androidx.compose.ui.viewinterop.AndroidView(factory = { view }, modifier = modifier)
}
