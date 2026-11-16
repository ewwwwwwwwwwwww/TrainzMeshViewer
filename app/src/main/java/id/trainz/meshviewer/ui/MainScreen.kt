package id.trainz.meshviewer.ui

import android.content.res.Configuration
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.safeDrawing
import id.trainz.meshviewer.gl.MeshGLSurfaceView
import id.trainz.meshviewer.gl.MeshRenderer
import id.trainz.meshviewer.model.TrainzModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    model: TrainzModel?, glView: MeshGLSurfaceView?, renderer: MeshRenderer?,
    attachmentPositions: List<Pair<String, FloatArray>>,
    onOpenFile: () -> Unit, onOpenFolder: () -> Unit
) {
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var texture by remember { mutableStateOf(true) }
    var wire by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(false) }
    var axes by remember { mutableStateOf(true) }
    var attachments by remember { mutableStateOf(false) }

    LaunchedEffect(texture, wire, bounds, axes, attachments) {
        renderer?.let { r -> r.options = r.options.copy(texture = texture, wire = wire, wireOnly = false, bounds = bounds, axes = axes, attachments = attachments) }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = Color(0xff11151b)
    ) { scaffoldInsets ->
        val safeContent = Modifier.fillMaxSize().padding(scaffoldInsets).consumeWindowInsets(scaffoldInsets)
        if (landscape) {
            Box(safeContent) {
                MeshViewport(model, glView, renderer, attachmentPositions, attachments, Modifier.fillMaxSize())
                Surface(
                    modifier = Modifier.align(Alignment.CenterStart).width(112.dp).fillMaxHeight(.92f)
                        .padding(start = 8.dp).statusBarsPadding().navigationBarsPadding(),
                    color = Color(0xe6192029), shape = RoundedCornerShape(22.dp), tonalElevation = 8.dp
                ) {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        item { Text("Trainz", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp)) }
                        item { RailAction(Icons.Default.FolderOpen, "Buka file", onOpenFile) }
                        item { RailAction(Icons.Default.Refresh, "Reset kamera") { glView?.resetCamera() } }
                        item { RailToggle(Icons.Default.Image, "Texture", texture) { texture = !texture } }
                        item { RailToggle(Icons.Default.GridOn, "Wireframe", wire) { wire = !wire } }
                        item { RailToggle(Icons.Default.CropFree, "Bounding box", bounds) { bounds = !bounds } }
                        item { RailToggle(Icons.Default.Tune, "Axes", axes) { axes = !axes } }
                        item { RailToggle(Icons.Default.LocationOn, "Attachments", attachments) { attachments = !attachments } }
                        if (model != null) item { Text("${model.subMeshes.size} mesh\n${model.vertexCount} vertex\n${model.triangleCount} segitiga", color = Color.LightGray, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(8.dp)) }
                    }
                }
                if (model != null) Text(model.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(10.dp).background(Color(0xa011151b), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp))
            }
        } else {
            BottomSheetScaffold(
                modifier = safeContent,
                scaffoldState = rememberBottomSheetScaffoldState(),
                sheetPeekHeight = if (model == null) 0.dp else 92.dp,
                sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                sheetContainerColor = Color(0xff1b222c),
                sheetContent = { StatsSheet(model, Modifier.navigationBarsPadding()) },
                sheetDragHandle = {
                    if (model != null) {
                        BottomSheetDefaults.DragHandle(
                            modifier = Modifier.defaultMinSize(minWidth = 64.dp, minHeight = 48.dp)
                        )
                    }
                }
            ) { sheetPadding ->
                Column(Modifier.fillMaxSize().padding(sheetPadding)) {
                    PortraitHeader(model, onOpenFile, glView)
                    if (model != null) LazyRow(
                        modifier = Modifier.fillMaxWidth().background(Color(0xff151a21)).heightIn(min = 56.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item { ToggleChip("Texture", Icons.Default.Image, texture) { texture = !texture } }
                        item { ToggleChip("Wireframe", Icons.Default.GridOn, wire) { wire = !wire } }
                        item { ToggleChip("Bounding Box", Icons.Default.CropFree, bounds) { bounds = !bounds } }
                        item { ToggleChip("Axes", Icons.Default.Tune, axes) { axes = !axes } }
                        item { ToggleChip("Attachments", Icons.Default.LocationOn, attachments) { attachments = !attachments } }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        MeshViewport(model, glView, renderer, attachmentPositions, attachments, Modifier.fillMaxSize())
                        if (model == null) EmptyState(onOpenFile, onOpenFolder, Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
}

@Composable
private fun PortraitHeader(model: TrainzModel?, onOpenFile: () -> Unit, glView: MeshGLSurfaceView?) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xff151a21)).statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(model?.name ?: "TrainzMeshViewer", color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(model?.format ?: "Pilih model .im atau .pm", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
        }
        LargeIconButton(Icons.Default.FolderOpen, "Open File", onOpenFile)
        LargeIconButton(Icons.Default.Refresh, "Reset Camera") { glView?.resetCamera() }
    }
}

@Composable
private fun ToggleChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick,
        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).heightIn(min = 48.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color(0xff202832), labelColor = Color(0xffe4eaf2), iconColor = Color(0xffe4eaf2),
            selectedContainerColor = Color(0xff43566e), selectedLabelColor = Color.White, selectedLeadingIconColor = Color.White
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true, selected = selected, borderColor = Color(0xff77889c), selectedBorderColor = Color(0xffa9c7e8)
        ),
        label = { Text(label, maxLines = 1) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp)) }
    )
}

@Composable
private fun LargeIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp).defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { contentDescription = label }
    ) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp)) }
}

@Composable
private fun EmptyState(onOpenFile: () -> Unit, onOpenFolder: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth(.88f), colors = CardDefaults.cardColors(containerColor = Color(0xe6212832)), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            Text("Buka model Trainz", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Pilih file mesh .im atau .pm dari penyimpanan perangkat.", color = Color.LightGray, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onOpenFile, modifier = Modifier.fillMaxWidth().defaultMinSize(minWidth = 48.dp, minHeight = 56.dp)) {
                Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Pilih File Mesh (.im / .pm)")
            }
            OutlinedButton(onClick = onOpenFolder, modifier = Modifier.fillMaxWidth().defaultMinSize(minWidth = 48.dp, minHeight = 52.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xffd9e7f5)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xff9aafc5))) { Text("Pilih Folder Aset") }
        }
    }
}

@Composable
private fun MeshViewport(
    model: TrainzModel?, glView: MeshGLSurfaceView?, renderer: MeshRenderer?,
    attachmentPositions: List<Pair<String, FloatArray>>, showAttachments: Boolean, modifier: Modifier = Modifier
) {
    Box(modifier.background(Color(0xff1c2026))) {
        if (glView != null) androidx.compose.ui.viewinterop.AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())
        if (showAttachments && model != null) Canvas(Modifier.fillMaxSize()) {
            val label = Paint().apply { color = android.graphics.Color.WHITE; textSize = 32f; isFakeBoldText = true; setShadowLayer(4f, 1f, 2f, android.graphics.Color.BLACK) }
            attachmentPositions.forEach { (name, xy) ->
                drawContext.canvas.nativeCanvas.apply {
                    drawCircle(xy[0], xy[1], 8f, Paint().apply { color = android.graphics.Color.YELLOW })
                    drawText(name, xy[0] + 12f, xy[1] - 8f, label)
                }
            }
        }
    }
}

@Composable
private fun StatsSheet(model: TrainzModel?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().heightIn(min = 80.dp, max = 560.dp).padding(horizontal = 18.dp)) {
        if (model != null) {
            Text(model.name, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${model.format}  •  ${model.subMeshes.size} sub-mesh  •  ${model.vertexCount} vertex  •  ${model.triangleCount} polygon", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            Text("Bounding box: ${"%.2f × %.2f × %.2f m".format(model.bounds.size.x, model.bounds.size.y, model.bounds.size.z)}", color = Color.LightGray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp, bottom = 8.dp))
            HorizontalDivider(color = Color(0xff3a424e))
            LazyColumn(Modifier.weight(1f, fill = false).padding(top = 6.dp, bottom = 12.dp)) {
                item { Text("Material & tekstur", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 6.dp)) }
                items(model.materials) { material ->
                    Text("${material.name}: ${material.textureRef ?: "tanpa tekstur"} · ${if (material.textureFound) "ditemukan" else "tidak ditemukan"}", color = Color.LightGray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                item { Text("Attachment points", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)) }
                items(model.attachments) { attachment ->
                    Text("${attachment.name}  (${"%.2f".format(attachment.position.x)}, ${"%.2f".format(attachment.position.y)}, ${"%.2f".format(attachment.position.z)})", color = Color.LightGray, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
            }
        } else {
            Text("Belum ada mesh yang dibuka", color = Color.White, modifier = Modifier.defaultMinSize(minHeight = 64.dp).padding(top = 16.dp))
        }
    }
}

@Composable
private fun RailAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().defaultMinSize(minWidth = 48.dp, minHeight = 56.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null); Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
    }
}

@Composable
private fun RailToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().defaultMinSize(minWidth = 48.dp, minHeight = 56.dp).padding(vertical = 2.dp), color = if (selected) Color(0xff43566e) else Color.Transparent, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp)); Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}
