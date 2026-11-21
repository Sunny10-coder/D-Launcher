package com.mylittleworld.parent.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mylittleworld.shared.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Minimal slippy-map renderer for OpenStreetMap tiles. No API key, no third-party
 * SDK, no location data sent anywhere except the standard tile requests for the
 * area being viewed. Tiles failing to load render as neutral placeholders.
 *
 * OSM's tile usage policy requires a descriptive User-Agent; this app identifies
 * itself and does not prefetch tiles outside the visible viewport.
 */
@Composable
fun OsmMap(
    lat: Double,
    lng: Double,
    modifier: Modifier = Modifier,
    zoom: Int = 15,
    accuracyMeters: Double? = null,
    stale: Boolean = false,
) {
    val context = LocalContext.current
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val loader = remember { TileLoader(context.applicationContext) }
    var tiles by remember { mutableStateOf<Map<TileKey, ImageBitmap>>(emptyMap()) }

    val center = remember(lat, lng, zoom) { project(lat, lng, zoom) }

    LaunchedEffect(center, viewSize, zoom) {
        if (viewSize.width == 0 || viewSize.height == 0) return@LaunchedEffect
        val halfW = viewSize.width / 2f
        val halfH = viewSize.height / 2f
        val startX = floor((center.x - halfW) / TILE_SIZE).toInt()
        val endX = floor((center.x + halfW) / TILE_SIZE).toInt()
        val startY = floor((center.y - halfH) / TILE_SIZE).toInt()
        val endY = floor((center.y + halfH) / TILE_SIZE).toInt()
        val max = (1 shl zoom) - 1
        val keys = buildList {
            for (x in startX..endX) {
                for (y in startY..endY) {
                    if (x in 0..max && y in 0..max) add(TileKey(zoom, x, y))
                }
            }
        }
        val loaded = keys.associateWith { key -> loader.load(key) }
            .mapNotNull { (key, bmp) -> bmp?.let { key to it } }
            .toMap()
        tiles = loaded
    }

    Box(modifier = modifier.onSizeChanged { viewSize = it }) {
        Canvas(Modifier.fillMaxSize()) {
            if (tiles.isEmpty()) {
                drawRect(Color(0xFFEDEDF2))
                return@Canvas
            }
            tiles.forEach { (key, bitmap) ->
                val dx = key.x * TILE_SIZE - (center.x - size.width / 2f)
                val dy = key.y * TILE_SIZE - (center.y - size.height / 2f)
                drawImage(
                    image = bitmap,
                    dstOffset = IntOffset(dx.toInt(), dy.toInt()),
                    dstSize = IntSize(TILE_SIZE.toInt(), TILE_SIZE.toInt()),
                )
            }

            if (accuracyMeters != null && accuracyMeters > 0) {
                val metersPerPixel = 156543.03392 * kotlin.math.cos(lat * PI / 180) /
                    (1 shl zoom)
                drawCircle(
                    color = Color(0x334C6FD6),
                    radius = (accuracyMeters / metersPerPixel).toFloat(),
                    center = centerOf(size),
                )
            }
            drawMarker(centerOf(size), stale)
        }
    }
}

private fun centerOf(size: Size): Offset = Offset(size.width / 2f, size.height / 2f)

private fun DrawScope.drawMarker(center: Offset, stale: Boolean) {
    val color = if (stale) Color(0xFFB4761E) else Color(0xFF4C6FD6)
    drawCircle(Color.White, radius = 11.dp.toPx(), center = center)
    drawCircle(color, radius = 8.dp.toPx(), center = center)
}

data class TileKey(val zoom: Int, val x: Int, val y: Int)

private class TileLoader(private val context: android.content.Context) {

    private val cache = object : LinkedHashMap<TileKey, ImageBitmap>(48, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, ImageBitmap>?): Boolean =
            size > 96
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun load(key: TileKey): ImageBitmap? {
        synchronized(cache) { cache[key]?.let { return it } }
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("https://tile.openstreetmap.org/${key.zoom}/${key.x}/${key.y}.png")
                    .header(
                        "User-Agent",
                        "MyLittleWorldParent/1.0 (private family app; ${context.packageName})",
                    )
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val bytes = response.body?.bytes() ?: return@use null
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: return@use null
                    bitmap.asImageBitmap().also { decode ->
                        synchronized(cache) { cache[key] = decode }
                    }
                }
            }.onFailure { AppLog.d("OsmMap", "Tile unavailable") }.getOrNull()
        }
    }
}

/** Web-Mercator projection to global pixel coordinates at a zoom level. */
fun project(lat: Double, lng: Double, zoom: Int): Offset {
    val scale = TILE_SIZE * (1 shl zoom)
    val x = (lng + 180.0) / 360.0 * scale
    val latRad = lat * PI / 180.0
    val y = (1.0 - ln(tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / PI) / 2.0 * scale
    return Offset(x.toFloat(), y.toFloat())
}

/** Inverse projection used when the parent taps "centre here". */
fun unproject(x: Double, y: Double, zoom: Int): Pair<Double, Double> {
    val scale = TILE_SIZE * (1 shl zoom)
    val lng = x / scale * 360.0 - 180.0
    val n = PI - 2.0 * PI * y / scale
    val lat = 180.0 / PI * atan(sinh(n))
    return lat to lng
}

private const val TILE_SIZE = 256.0
