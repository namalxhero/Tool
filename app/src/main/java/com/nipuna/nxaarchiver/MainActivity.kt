package com.nipuna.nxaarchiver

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile

// ---- hacker/neon palette ----
private val BgBlack = Color(0xFF0A0E0A)
private val PanelBlack = Color(0xFF121612)
private val NeonGreen = Color(0xFF39FF88)
private val NeonCyan = Color(0xFF39E0FF)
private val DimGreen = Color(0xFF1F5C3A)
private val TextDim = Color(0xFF7FA78C)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = BgBlack,
                surface = PanelBlack,
                primary = NeonGreen,
                secondary = NeonCyan,
                onBackground = NeonGreen,
                onSurface = NeonGreen
            )) {
                NxaApp(contentResolver)
            }
        }
    }
}

private enum class Tab { COMPRESS, EXTRACT }

@Composable
fun NxaApp(resolver: ContentResolver) {
    var tab by remember { mutableStateOf(Tab.COMPRESS) }

    Surface(modifier = Modifier.fillMaxSize(), color = BgBlack) {
        Column(Modifier.fillMaxSize()) {
            Header()
            TabRow(
                selectedTabIndex = tab.ordinal,
                containerColor = PanelBlack,
                contentColor = NeonGreen
            ) {
                Tab(selected = tab == Tab.COMPRESS, onClick = { tab = Tab.COMPRESS },
                    text = { Text("COMPRESS", fontFamily = FontFamily.Monospace) })
                Tab(selected = tab == Tab.EXTRACT, onClick = { tab = Tab.EXTRACT },
                    text = { Text("EXTRACT", fontFamily = FontFamily.Monospace) })
            }
            when (tab) {
                Tab.COMPRESS -> CompressScreen(resolver)
                Tab.EXTRACT -> ExtractScreen(resolver)
            }
        }
    }
}

@Composable
private fun Header() {
    Column(
        Modifier
            .fillMaxWidth()
            .background(PanelBlack)
            .border(width = 1.dp, color = DimGreen)
            .padding(16.dp)
    ) {
        Text(
            "NXA ARCHIVER",
            color = NeonGreen,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp
        )
        Text(
            ".nxa only  //  AES-256-GCM + XZ  //  pentest-ready",
            color = TextDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
    }
}

private fun fileName(resolver: ContentResolver, uri: Uri): String {
    var name = uri.lastPathSegment ?: "file"
    resolver.query(uri, null, null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx)
    }
    return name
}

@Composable
private fun CompressScreen(resolver: ContentResolver) {
    var selected by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var password by remember { mutableStateOf("") }
    var bundle by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var outputUri by remember { mutableStateOf<Uri?>(null) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        selected = uris
    }
    val createArchive = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null || selected.isEmpty()) return@rememberLauncherForActivityResult
        try {
            val entries = selected.map { u ->
                val bytes = resolver.openInputStream(u)!!.use { it.readBytes() }
                NxaFormat.Entry(fileName(resolver, u), bytes)
            }
            resolver.openOutputStream(uri)!!.use { out ->
                NxaFormat.create(entries, out, password.ifBlank { null }, bundle)
            }
            status = "✓ archive created (${entries.size} files)"
        } catch (e: Exception) {
            status = "✗ failed: ${e.message}"
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        NeonButton("SELECT FILES") { pickFiles.launch(arrayOf("*/*")) }

        if (selected.isNotEmpty()) {
            Panel {
                LazyColumn(Modifier.heightIn(max = 180.dp)) {
                    items(selected) { u ->
                        Text("• ${fileName(resolver, u)}", color = NeonGreen, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        }

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("password (optional — leave blank = no encryption)", color = TextDim) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            colors = neonFieldColors(),
            modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = bundle, onCheckedChange = { bundle = it }, colors = CheckboxDefaults.colors(checkedColor = NeonGreen))
            Text("Bundle as pentest tool-pack (auto manifest.json)", color = TextDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }

        NeonButton("CREATE .NXA", enabled = selected.isNotEmpty()) {
            createArchive.launch("archive.nxa")
        }

        if (status.isNotEmpty()) StatusLine(status)
    }
}

@Composable
private fun ExtractScreen(resolver: ContentResolver) {
    val context = LocalContext.current
    var archiveUri by remember { mutableStateOf<Uri?>(null) }
    var info by remember { mutableStateOf<NxaFormat.ArchiveInfo?>(null) }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    val pickArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        archiveUri = uri
        status = ""
        if (uri != null) {
            try {
                info = resolver.openInputStream(uri)!!.use { NxaFormat.readInfo(it) }
            } catch (e: Exception) {
                status = "✗ not a valid .nxa file"
            }
        }
    }
    val pickDestination = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null || archiveUri == null) return@rememberLauncherForActivityResult
        try {
            val entries = resolver.openInputStream(archiveUri!!)!!.use {
                NxaFormat.extract(it, password.ifBlank { null })
            }
            val destDir = DocumentFile.fromTreeUri(context, treeUri)
                ?: throw IllegalStateException("can't open destination folder")
            for (entry in entries) {
                destDir.findFile(entry.name)?.delete()
                val newFile = destDir.createFile("application/octet-stream", entry.name)
                    ?: throw IllegalStateException("can't create ${entry.name}")
                resolver.openOutputStream(newFile.uri)!!.use { out -> out.write(entry.bytes) }
            }
            status = "✓ extracted ${entries.size} files"
        } catch (e: Exception) {
            status = "✗ failed: ${e.message}"
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        NeonButton("SELECT .NXA FILE") { pickArchive.launch(arrayOf("*/*")) }

        info?.let { i ->
            Panel {
                Text("encrypted   : ${i.encrypted}", color = NeonCyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text("compressed  : ${i.compressed}", color = NeonCyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text("tool bundle : ${i.hasManifest}", color = NeonCyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text("size        : ${i.size} bytes", color = NeonCyan, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
        }

        if (info?.encrypted == true) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("password", color = TextDim) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                colors = neonFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        NeonButton("EXTRACT TO FOLDER…", enabled = archiveUri != null) {
            pickDestination.launch(null)
        }

        if (status.isNotEmpty()) StatusLine(status)
    }
}

@Composable
private fun NeonButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = DimGreen, contentColor = NeonGreen),
        shape = RoundedCornerShape(2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(PanelBlack)
            .border(width = 1.dp, color = DimGreen)
            .padding(12.dp),
        content = content
    )
}

@Composable
private fun StatusLine(text: String) {
    Text(text, color = if (text.startsWith("✓")) NeonGreen else Color(0xFFFF5C5C), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
}

@Composable
private fun neonFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = NeonGreen,
    unfocusedTextColor = NeonGreen,
    focusedBorderColor = NeonGreen,
    unfocusedBorderColor = DimGreen,
    cursorColor = NeonGreen
)
