package id.trainz.meshviewer

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.documentfile.provider.DocumentFile
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import id.trainz.meshviewer.gl.MeshGLSurfaceView
import id.trainz.meshviewer.gl.MeshRenderer
import id.trainz.meshviewer.model.TrainzModel
import id.trainz.meshviewer.parser.TrainzMeshParser
import id.trainz.meshviewer.texture.TrainzTextureLoader
import id.trainz.meshviewer.ui.MainScreen
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var view: MeshGLSurfaceView? = null
    private var renderer: MeshRenderer? = null
    private val openMesh = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { loadMesh(it, null, true) } }
    private val openFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { onFolder(it) } }
    private var folderResult: ((String) -> Unit)? = null
    private val folderChildren = mutableStateListOf<DocumentFile>()
    private var showFolderDialog by mutableStateOf(false)
    private val meshState = mutableStateOf<TrainzModel?>(null)
    private val attachmentState = mutableStateOf<List<Pair<String, FloatArray>>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        renderer = MeshRenderer(this).also { r -> r.onAttachmentProjection = { positions -> runOnUiThread { attachmentState.value = positions } } }
        view = MeshGLSurfaceView(this, renderer!!)
        setContent {
            val context = LocalContext.current
            val model by meshState
            val labels by attachmentState
            MaterialTheme {
                MainScreen(model, view, renderer, labels, { openMesh.launch(arrayOf("*/*")) }, { openFolder.launch(null) })
                if (showFolderDialog) AlertDialog(onDismissRequest = { showFolderDialog = false }, title = { Text("Pilih mesh") },
                    text = { androidx.compose.foundation.lazy.LazyColumn { items(folderChildren.size) { i -> val f=folderChildren[i]; TextButton(onClick = { showFolderDialog=false; f.uri.let { loadMesh(it, DocumentFile.fromTreeUri(context, currentTreeUri!!)) } }) { Text(f.name ?: "mesh") } } } },
                    confirmButton = { TextButton(onClick = { showFolderDialog=false }) { Text("Batal") } })
            }
        }
    }
    private var currentTreeUri: Uri? = null
    private fun onFolder(uri: Uri) {
        currentTreeUri = uri
        contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val tree = DocumentFile.fromTreeUri(this, uri) ?: return
        folderChildren.clear(); folderChildren.addAll(tree.listFiles().filter { it.name?.endsWith(".im", true) == true || it.name?.endsWith(".pm", true) == true })
        if (folderChildren.size == 1) loadMesh(folderChildren.first().uri, tree) else if (folderChildren.isEmpty()) Toast.makeText(this, "Tidak ada file .im/.pm di folder ini", Toast.LENGTH_LONG).show() else showFolderDialog = true
    }
    private fun loadMesh(uri: Uri, folder: DocumentFile?, persistGrant: Boolean = false) {
        if (persistGrant) runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val displayName = DocumentFile.fromSingleUri(this, uri)?.name ?: "mesh"
        executor.execute {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Tidak dapat membaca file")
                val parsed = TrainzMeshParser.parse(bytes, displayName)
                val textures = if (folder != null) TrainzTextureLoader.loadFolder(contentResolver, folder, parsed.materials) else TrainzTextureLoader.loadSingle(contentResolver, uri, parsed.materials)
                val complete = parsed.copy(materials = textures.materials)
                runOnUiThread { meshState.value = complete; renderer?.model = complete; renderer?.bitmaps = textures.bitmaps }
            } catch (e: Exception) { runOnUiThread { Toast.makeText(this, e.message ?: "Gagal membuka mesh", Toast.LENGTH_LONG).show() } }
        }
    }
    override fun onPause() { super.onPause(); view?.onPause() }
    override fun onResume() { super.onResume(); view?.onResume() }
    override fun onDestroy() { super.onDestroy(); executor.shutdown() }
}
