package com.example.imgeditor

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    EditorScreen()
                }
            }
        }
    }
}

data class ImageItem(val uri: Uri, val name: String)

private fun findChildUri(context: Context, treeUri: Uri, name: String): Uri? {
    try {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                }
            }
        }
    } catch (_: Exception) {
    }
    return null
}

private suspend fun listImages(context: Context, treeUri: Uri): List<ImageItem> =
    withContext(Dispatchers.IO) {
        val result = mutableListOf<ImageItem>()
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime.startsWith("image/")) {
                        result.add(
                            ImageItem(
                                DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                                name
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }
        result.sortBy { it.name.lowercase() }
        result
    }

/**
 * 原分辨率加载图片，不做任何降采样，保证像素级无损。
 */
private suspend fun loadBitmap(context: Context, uri: Uri): Bitmap? =
    withContext(Dispatchers.IO) {
        try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = 1
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inDither = false
                inPremultiplied = false
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return@withContext null

            if (decoded.isMutable && decoded.config == Bitmap.Config.ARGB_8888) {
                decoded
            } else {
                decoded.copy(Bitmap.Config.ARGB_8888, true)
            }
        } catch (_: Exception) {
            null
        }
    }

private fun strokeOnBitmap(
    bmp: Bitmap,
    from: Offset,
    to: Offset,
    color: Int,
    widthPx: Float
) {
    val canvas = AndroidCanvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = widthPx.coerceAtLeast(1f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    canvas.drawLine(from.x, from.y, to.x, to.y, paint)
}

private fun mapPoint(
    p: Offset,
    viewSize: IntSize,
    bmpW: Float,
    bmpH: Float
): Pair<Offset, Float> {
    val w = viewSize.width.toFloat()
    val h = viewSize.height.toFloat()
    if (w <= 0f || h <= 0f || bmpW <= 0f || bmpH <= 0f) return Offset.Zero to 1f
    val s = minOf(w / bmpW, h / bmpH)
    val dx = (w - bmpW * s) / 2f
    val dy = (h - bmpH * s) / 2f
    return Offset((p.x - dx) / s, (p.y - dy) / s) to s
}

/**
 * 把 Bitmap 无损写入输出文件夹。
 * 始终输出 PNG（无损压缩，像素零丢失）。
 */
private suspend fun writeBitmapToOutput(
    context: Context,
    outTree: Uri,
    bmp: Bitmap,
    originalName: String
): Boolean = withContext(Dispatchers.IO) {
    try {
        val fileName = originalName.substringBeforeLast('.', originalName) + ".png"

        findChildUri(context, outTree, fileName)?.let { old ->
            try {
                DocumentsContract.deleteDocument(context.contentResolver, old)
            } catch (_: Exception) {
            }
        }

        val newDoc = DocumentsContract.createDocument(
            context.contentResolver, outTree, "image/png", fileName
        ) ?: return@withContext false

        context.contentResolver.openOutputStream(newDoc, "w")?.use { os ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
            os.flush()
        }
        true
    } catch (_: Exception) {
        false
    }
}

@Composable
fun EditorScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var inputTree by remember { mutableStateOf<Uri?>(null) }
    var outputTree by remember { mutableStateOf<Uri?>(null) }
    var images by remember { mutableStateOf<List<ImageItem>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var modified by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    var eraser by remember { mutableStateOf(false) }
    var brushColor by remember { mutableStateOf(Color.Black) }
    var brushSizeDp by remember { mutableFloatStateOf(16f) }
    var busy by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf("① 点「输入」选图片文件夹　② 点「输出」选保存文件夹")
    }

    val palette = remember {
        listOf(
            Color(0xFF000000), Color(0xFFFFFFFF),
            Color(0xFFFF3B30), Color(0xFFFF9500),
            Color(0xFFFFCC00), Color(0xFF34C759),
            Color(0xFF007AFF), Color(0xFFAF52DE)
        )
    }

    val inputPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            inputTree = uri
            index = 0
        }
    }

    val outputPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            outputTree = uri
            status = "输出文件夹已设置，修改后翻页会自动保存"
        }
    }

    LaunchedEffect(inputTree) {
        val t = inputTree ?: return@LaunchedEffect
        busy = true
        status = "正在读取图片列表…"
        val list = listImages(context, t)
        images = list
        index = 0
        busy = false
        status = if (list.isEmpty()) "该文件夹里没有图片" else "共 ${list.size} 张图片"
    }

    LaunchedEffect(images, index) {
        val item = images.getOrNull(index)
        if (item == null) {
            bitmap = null
            modified = false
            return@LaunchedEffect
        }
        busy = true
        modified = false
        val b = loadBitmap(context, item.uri)
        bitmap = b
        modified = false
        version++
        busy = false
        status = if (b == null) {
            "加载失败：${item.name}"
        } else {
            "${index + 1}/${images.size}　${item.name}　${b.width}×${b.height}"
        }
    }

    fun navigate(step: Int, save: Boolean) {
        if (images.isEmpty() || busy) return
        val target = index + step
        if (target < 0) {
            status = "已经是第一张了"
            return
        }
        if (target >= images.size) {
            status = "已经是最后一张了"
            return
        }

        val wasModified = modified
        val currentBitmap = bitmap
        val currentItem = images.getOrNull(index)

        scope.launch {
            if (save && wasModified && currentBitmap != null && currentItem != null) {
                busy = true
                if (outputTree == null) {
                    status = "未选择输出文件夹，本次修改未保存"
                } else {
                    val ok = writeBitmapToOutput(
                        context, outputTree!!, currentBitmap, currentItem.name
                    )
                    status = if (ok) {
                        "已保存：${currentItem.name.substringBeforeLast('.')}.png"
                    } else {
                        "保存失败"
                    }
                }
                busy = false
            }

            modified = false
            index = target
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101014))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ============ 顶部工具栏 ============
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1B1B22))
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolButton("输入", inputTree != null) { inputPicker.launch(null) }
                ToolButton("输出", outputTree != null) { outputPicker.launch(null) }
                ToolButton("擦除", eraser) {
                    eraser = true
                    brushColor = Color.White
                }
                ToolButton("补画", !eraser) {
                    eraser = false
                    brushColor = Color.Black
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                palette.forEach { c ->
                    val selected = c == brushColor
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) Color(0xFF4DA3FF) else Color(0x55FFFFFF),
                                shape = CircleShape
                            )
                            .clickable {
                                brushColor = c
                            }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("笔刷", color = Color(0xFFB0B0BB), fontSize = 12.sp)
                Slider(
                    value = brushSizeDp,
                    onValueChange = { brushSizeDp = it },
                    valueRange = 2f..80f,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                Text(
                    text = "${brushSizeDp.toInt()}",
                    color = Color(0xFFB0B0BB),
                    fontSize = 12.sp,
                    modifier = Modifier.width(26.dp)
                )
            }
        }

        // ============ 画布 ============
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF2B2B33)),
            contentAlignment = Alignment.Center
        ) {
            val imgBitmap = remember(bitmap, version) { bitmap?.asImageBitmap() }

            if (imgBitmap != null) {
                Image(
                    bitmap = imgBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(bitmap, brushColor, brushSizeDp) {
                            val bmp = bitmap ?: return@pointerInput
                            val bmpW = bmp.width.toFloat()
                            val bmpH = bmp.height.toFloat()
                            if (bmpW <= 0f || bmpH <= 0f) return@pointerInput

                            var last: Offset? = null

                            detectDragGestures(
                                onDragStart = { pos ->
                                    val (pt, s) = mapPoint(pos, size, bmpW, bmpH)
                                    last = pt
                                    strokeOnBitmap(
                                        bmp, pt, pt,
                                        brushColor.toArgb(), brushSizeDp.dp.toPx() / s
                                    )
                                    modified = true
                                    version++
                                },
                                onDragEnd = { last = null },
                                onDragCancel = { last = null },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val (pt, s) = mapPoint(change.position, size, bmpW, bmpH)
                                    val prev = last
                                    if (prev != null) {
                                        strokeOnBitmap(
                                            bmp, prev, pt,
                                            brushColor.toArgb(), brushSizeDp.dp.toPx() / s
                                        )
                                    } else {
                                        strokeOnBitmap(
                                            bmp, pt, pt,
                                            brushColor.toArgb(), brushSizeDp.dp.toPx() / s
                                        )
                                    }
                                    last = pt
                                    modified = true
                                    version++
                                }
                            )
                        }
                )
            } else {
                Text(
                    text = if (busy) "加载中…" else "请选择输入文件夹",
                    color = Color(0xFF8888A0),
                    fontSize = 15.sp
                )
            }
        }

        // ============ 状态栏 ============
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF15151B))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = status,
                color = Color(0xFFAAAAB8),
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            if (modified) {
                Text(
                    text = "● 已修改",
                    color = Color(0xFFFF9F0A),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ============ 底部按钮 ============
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1B1B22))
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { navigate(-1, true) },
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3A3A46),
                    contentColor = Color.White
                )
            ) {
                Text("上一张", fontSize = 15.sp)
            }

            Button(
                onClick = { navigate(1, false) },
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF5A5A66),
                    contentColor = Color.White
                )
            ) {
                Text("跳过", fontSize = 15.sp)
            }

            Button(
                onClick = { navigate(1, true) },
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2E7DFF),
                    contentColor = Color.White
                )
            ) {
                Text("下一张", fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun RowScope.ToolButton(
    text: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .height(44.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Color(0xFF2E7DFF) else Color(0xFF34343E),
            contentColor = Color.White
        )
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
    }
}
