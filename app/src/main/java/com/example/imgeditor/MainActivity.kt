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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.absoluteOffset
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 图像在画布中的填充比例（占画布短边的百分比）。
 * 0.82 = 图像占 82%，四周各留约 9% 的操作空间。
 */
private const val IMAGE_FILL_FACTOR = 0.82f

/** 放大镜直径 */
private val MAGNIFIER_SIZE = 140.dp

/** 放大镜放大倍数 */
private const val MAGNIFY_FACTOR = 3f

/** 放大镜边缘与触摸点的间距 */
private val MAGNIFIER_GAP = 70.dp

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

/**
 * 把 Bitmap 无损写入输出文件夹。
 * 始终输出 PNG（无损压缩，像素零丢失）。
 *
 * 返回值：
 * - 空字符串 ""   表示保存成功
 * - 非空字符串    表示失败原因，方便在状态栏显示
 */
private suspend fun writeBitmapToOutput(
    context: Context,
    outTree: Uri,
    bmp: Bitmap,
    originalName: String
): String = withContext(Dispatchers.IO) {
    try {
        if (bmp.isRecycled) {
            return@withContext "图像已被回收"
        }

        val fileName = originalName.substringBeforeLast('.', originalName) + ".png"

        // 删除同名旧文件，避免不同设备上 createDocument 自动改名或失败
        findChildUri(context, outTree, fileName)?.let { old ->
            try {
                DocumentsContract.deleteDocument(context.contentResolver, old)
            } catch (_: Exception) {
                // 删除失败不致命，继续尝试创建
            }
        }

        val newDoc = DocumentsContract.createDocument(
            context.contentResolver, outTree, "image/png", fileName
        ) ?: return@withContext "无法创建文件 $fileName（可能无写入权限）"

        // 关键修复：openOutputStream 为 null 时直接失败，不再误报成功
        val os = context.contentResolver.openOutputStream(newDoc, "w")
            ?: return@withContext "无法打开输出流 $fileName"

        val compressed = os.use { stream ->
            val ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.flush()
            ok
        }

        if (!compressed) {
            return@withContext "PNG 编码失败 $fileName"
        }

        // 二次验证：查询文件大小，确保真的写入成功
        val size = try {
            context.contentResolver.query(
                newDoc,
                arrayOf(DocumentsContract.Document.COLUMN_SIZE),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }

        if (size <= 0L) {
            return@withContext "文件写入为空 $fileName"
        }

        ""  // 成功
    } catch (e: Exception) {
        "保存异常：${e.message ?: e.javaClass.simpleName}"
    }
}

@Composable
fun EditorScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

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

    // 操作结果显示（保存成功/失败等）
    var actionStatus by remember {
        mutableStateOf("① 点「输入」选图片文件夹　② 点「输出」选保存文件夹")
    }
    // 图片信息显示（序号、文件名、尺寸）
    var imageInfo by remember { mutableStateOf("") }

    // 手指触摸位置（画布坐标系），null 表示未触摸
    var touchPos by remember { mutableStateOf<Offset?>(null) }

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
            actionStatus = "输出文件夹已设置，修改后翻页会自动保存"
        }
    }

    LaunchedEffect(inputTree) {
        val t = inputTree ?: return@LaunchedEffect
        busy = true
        actionStatus = "正在读取图片列表…"
        val list = listImages(context, t)
        images = list
        index = 0
        busy = false
        actionStatus = if (list.isEmpty()) "该文件夹里没有图片" else "共 ${list.size} 张图片"
    }

    LaunchedEffect(images, index) {
        val item = images.getOrNull(index)
        if (item == null) {
            bitmap = null
            modified = false
            imageInfo = ""
            return@LaunchedEffect
        }
        busy = true
        modified = false
        val b = loadBitmap(context, item.uri)
        bitmap = b
        modified = false
        version++
        busy = false
        imageInfo = if (b == null) {
            "加载失败：${item.name}"
        } else {
            "${index + 1}/${images.size}　${item.name}　${b.width}×${b.height}"
        }
    }

    /**
     * 翻页。
     * save = true  （上一张/下一张）：如果当前图有修改，先保存再翻页
     * save = false （跳过）        ：不保存，直接丢弃当前图的修改
     */
    fun navigate(step: Int, save: Boolean) {
        if (images.isEmpty()) return
        if (busy) {
            actionStatus = "正在处理，请稍候…"
            return
        }

        val target = index + step
        if (target < 0) {
            actionStatus = "已经是第一张了"
            return
        }
        if (target >= images.size) {
            actionStatus = "已经是最后一张了"
            return
        }

        // 在协程启动前捕获所有需要的状态，避免异步过程中被覆盖
        val wasModified = modified
        val savedBitmap = bitmap
        val savedItem = images.getOrNull(index)
        val needSave = save && wasModified && savedBitmap != null && savedItem != null

        // 立即设置 busy，防止连点
        if (needSave) busy = true

        scope.launch {
            if (needSave && savedBitmap != null && savedItem != null) {
                if (outputTree == null) {
                    actionStatus = "⚠ 未选择输出文件夹，本次修改未保存"
                } else {
                    val err = writeBitmapToOutput(
                        context, outputTree!!, savedBitmap, savedItem.name
                    )
                    actionStatus = if (err.isEmpty()) {
                        "✓ 已保存：${savedItem.name.substringBeforeLast('.')}.png"
                    } else {
                        "✗ 保存失败：$err"
                    }
                }
                busy = false
            }

            modified = false
            index = target
        }
    }

    fun resetCurrent() {
        if (busy) return
        val item = images.getOrNull(index) ?: return
        scope.launch {
            busy = true
            actionStatus = "正在重置…"
            val b = loadBitmap(context, item.uri)
            bitmap = b
            modified = false
            version++
            busy = false
            imageInfo = if (b == null) {
                "重置失败：${item.name}"
            } else {
                "${index + 1}/${images.size}　${item.name}　${b.width}×${b.height}"
            }
            actionStatus = "已重置：${item.name}"
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
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ToolButton("输入", inputTree != null, true) { inputPicker.launch(null) }
                ToolButton("输出", outputTree != null, true) { outputPicker.launch(null) }
                ToolButton("擦除", eraser, true) {
                    eraser = true
                    brushColor = Color.White
                }
                ToolButton("补画", !eraser, true) {
                    eraser = false
                    brushColor = Color.Black
                }
                ToolButton("重置", false, bitmap != null) { resetCurrent() }
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
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF2B2B33)),
            contentAlignment = Alignment.Center
        ) {
            val canvasW = with(density) { maxWidth.toPx() }
            val canvasH = with(density) { maxHeight.toPx() }

            val bmp = bitmap
            val imgBitmap = remember(bitmap, version) { bitmap?.asImageBitmap() }

            if (bmp != null && imgBitmap != null && canvasW > 0f && canvasH > 0f) {
                val bmpW = bmp.width.toFloat()
                val bmpH = bmp.height.toFloat()

                val fitScale = minOf(canvasW / bmpW, canvasH / bmpH)
                val displayScale = fitScale * IMAGE_FILL_FACTOR
                val displayW = (bmpW * displayScale).coerceAtLeast(1f)
                val displayH = (bmpH * displayScale).coerceAtLeast(1f)
                val originX = (canvasW - displayW) / 2f
                val originY = (canvasH - displayH) / 2f

                // ---- 图像显示（居中） ----
                Image(
                    bitmap = imgBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(
                            width = with(density) { displayW.toDp() },
                            height = with(density) { displayH.toDp() }
                        )
                )

                // ---- 触摸交互层（覆盖整个画布） ----
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(bmp, brushColor, brushSizeDp) {
                            val bW = bmp.width.toFloat()
                            val bH = bmp.height.toFloat()
                            if (bW <= 0f || bH <= 0f) return@pointerInput
                            val vw = size.width.toFloat()
                            val vh = size.height.toFloat()
                            if (vw <= 0f || vh <= 0f) return@pointerInput

                            val oX = (vw - displayW) / 2f
                            val oY = (vh - displayH) / 2f
                            val brushPx = with(density) { brushSizeDp.dp.toPx() }

                            fun toImg(p: Offset) =
                                Offset((p.x - oX) / displayScale, (p.y - oY) / displayScale)

                            var last: Offset? = null

                            detectDragGestures(
                                onDragStart = { pos ->
                                    touchPos = pos
                                    val pt = toImg(pos)
                                    last = pt
                                    strokeOnBitmap(
                                        bmp, pt, pt,
                                        brushColor.toArgb(),
                                        brushPx / displayScale
                                    )
                                    modified = true
                                    version++
                                },
                                onDragEnd = {
                                    last = null
                                    touchPos = null
                                },
                                onDragCancel = {
                                    last = null
                                    touchPos = null
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    touchPos = change.position
                                    val pt = toImg(change.position)
                                    val prev = last
                                    if (prev != null) {
                                        strokeOnBitmap(
                                            bmp, prev, pt,
                                            brushColor.toArgb(),
                                            brushPx / displayScale
                                        )
                                    } else {
                                        strokeOnBitmap(
                                            bmp, pt, pt,
                                            brushColor.toArgb(),
                                            brushPx / displayScale
                                        )
                                    }
                                    last = pt
                                    modified = true
                                    version++
                                }
                            )
                        }
                )

                // ---- 触摸位置圆点标记 + 放大镜 ----
                val tp = touchPos
                if (tp != null) {
                    val magRadiusPx = with(density) { (MAGNIFIER_SIZE / 2).toPx() }
                    val magGapPx = with(density) { MAGNIFIER_GAP.toPx() }
                    val markRadiusPx = with(density) { (brushSizeDp / 2).dp.toPx() }

                    // 1. 主画布上的半透明圆点标记
                    Canvas(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .fillMaxSize()
                    ) {
                        drawCircle(
                            color = brushColor.copy(alpha = 0.45f),
                            radius = markRadiusPx,
                            center = tp
                        )
                        drawCircle(
                            color = Color(0xFFFF2222),
                            radius = markRadiusPx,
                            center = tp,
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                        drawCircle(
                            color = Color(0xFFFF2222),
                            radius = 1.5.dp.toPx(),
                            center = tp
                        )
                    }

                    // 2. 放大镜固定放在触摸点的左上方；越界时贴边
                    val desiredCx = tp.x - magGapPx - magRadiusPx
                    val desiredCy = tp.y - magGapPx - magRadiusPx
                    val maxCx = (canvasW - magRadiusPx).coerceAtLeast(magRadiusPx)
                    val maxCy = (canvasH - magRadiusPx).coerceAtLeast(magRadiusPx)
                    val magCx = desiredCx.coerceIn(magRadiusPx, maxCx)
                    val magCy = desiredCy.coerceIn(magRadiusPx, maxCy)

                    // 触摸点在图片坐标系里的位置
                    val imgPos = Offset(
                        (tp.x - originX) / displayScale,
                        (tp.y - originY) / displayScale
                    )

                    // 3. 放大镜本体
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .absoluteOffset {
                                IntOffset(
                                    (magCx - magRadiusPx).roundToInt(),
                                    (magCy - magRadiusPx).roundToInt()
                                )
                            }
                            .size(MAGNIFIER_SIZE)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val circlePath = Path().apply {
                                addOval(Rect(0f, 0f, size.width, size.height))
                            }

                            clipPath(circlePath) {
                                drawRect(color = Color(0xFF14141A))

                                val srcSize = size.width / (displayScale * MAGNIFY_FACTOR)

                                val srcLeft = imgPos.x - srcSize / 2f
                                val srcTop = imgPos.y - srcSize / 2f

                                val leftClip = if (srcLeft < 0f) -srcLeft else 0f
                                val topClip = if (srcTop < 0f) -srcTop else 0f
                                val rightClip =
                                    if (srcLeft + srcSize > bmpW) srcLeft + srcSize - bmpW else 0f
                                val bottomClip =
                                    if (srcTop + srcSize > bmpH) srcTop + srcSize - bmpH else 0f

                                val actualSrcLeft =
                                    (srcLeft + leftClip).toInt().coerceAtLeast(0)
                                val actualSrcTop =
                                    (srcTop + topClip).toInt().coerceAtLeast(0)
                                val actualSrcW =
                                    (srcSize - leftClip - rightClip).toInt().coerceAtLeast(1)
                                val actualSrcH =
                                    (srcSize - topClip - bottomClip).toInt().coerceAtLeast(1)

                                val safeW =
                                    actualSrcW.coerceAtMost(bmp.width - actualSrcLeft)
                                val safeH =
                                    actualSrcH.coerceAtMost(bmp.height - actualSrcTop)

                                if (safeW > 0 && safeH > 0) {
                                    val pxPerSrc = size.width / srcSize
                                    val dstLeft = (leftClip * pxPerSrc).toInt()
                                    val dstTop = (topClip * pxPerSrc).toInt()
                                    val dstW = (safeW * pxPerSrc).toInt()
                                    val dstH = (safeH * pxPerSrc).toInt()

                                    drawImage(
                                        image = imgBitmap,
                                        srcOffset = IntOffset(actualSrcLeft, actualSrcTop),
                                        srcSize = IntSize(safeW, safeH),
                                        dstOffset = IntOffset(dstLeft, dstTop),
                                        dstSize = IntSize(dstW, dstH),
                                        filterQuality = FilterQuality.None
                                    )
                                }

                                // 放大镜内的圆点标记
                                val magMarkRadius =
                                    brushSizeDp * density.density * MAGNIFY_FACTOR / 2f
                                val center = Offset(size.width / 2f, size.height / 2f)

                                drawCircle(
                                    color = brushColor.copy(alpha = 0.45f),
                                    radius = magMarkRadius,
                                    center = center
                                )
                                drawCircle(
                                    color = Color(0xFFFF2222),
                                    radius = magMarkRadius,
                                    center = center,
                                    style = Stroke(width = 1.5.dp.toPx())
                                )
                                drawCircle(
                                    color = Color(0xFFFF2222),
                                    radius = 1.5.dp.toPx(),
                                    center = center
                                )
                            }

                            // 外圈边框
                            drawCircle(
                                color = Color.White,
                                radius = size.minDimension / 2f - 1f,
                                style = Stroke(width = 2.dp.toPx())
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = if (busy) "加载中…" else "请选择输入文件夹",
                    color = Color(0xFF8888A0),
                    fontSize = 15.sp
                )
            }
        }

        // ============ 状态栏（两行：操作结果 + 图片信息） ============
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF15151B))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = actionStatus,
                    color = Color(0xFFE0E0E8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
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
            if (imageInfo.isNotEmpty()) {
                Text(
                    text = imageInfo,
                    color = Color(0xFF8888A0),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
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
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .weight(1f)
            .height(44.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) Color(0xFF2E7DFF) else Color(0xFF34343E),
            contentColor = Color.White,
            disabledContainerColor = Color(0xFF252530),
            disabledContentColor = Color(0xFF666670)
        )
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
    }
}
