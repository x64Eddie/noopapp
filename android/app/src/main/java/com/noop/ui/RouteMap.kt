package com.noop.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.noop.analytics.RouteMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Web-Mercator math for [RouteMap] (the projection OpenStreetMap's raster tiles use). Pure, so it is
 * unit-tested without a device. World pixels are for a [TILE_PX]-sized tile at zoom z.
 */
internal object MapProjection {
    const val TILE_PX = 256.0
    const val MAX_ZOOM = 17
    const val MIN_ZOOM = 2

    /** Mercator is undefined at the poles; OSM clips to ±85.0511°. */
    private const val MAX_LAT = 85.05112878

    fun worldX(lon: Double, zoom: Int): Double = (lon + 180.0) / 360.0 * TILE_PX * (1 shl zoom)

    fun worldY(lat: Double, zoom: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        return (1.0 - ln(tan(r) + 1.0 / kotlin.math.cos(r)) / PI) / 2.0 * TILE_PX * (1 shl zoom)
    }

    fun latAt(worldY: Double, zoom: Int): Double {
        val n = PI - 2.0 * PI * worldY / (TILE_PX * (1 shl zoom))
        return Math.toDegrees(atan(0.5 * (exp(n) - exp(-n))))
    }

    /** The deepest zoom at which the route's bounds fit a [widthPx]×[heightPx] view (in tile pixels) with
     *  [padPx] on every side. A single point, or no points, gets a street-level default. */
    fun fitZoom(points: List<RouteMath.LatLng>, widthPx: Double, heightPx: Double, padPx: Double): Int {
        if (points.size < 2) return 15
        val w = (widthPx - 2 * padPx).coerceAtLeast(1.0)
        val h = (heightPx - 2 * padPx).coerceAtLeast(1.0)
        for (z in MAX_ZOOM downTo MIN_ZOOM) {
            val xs = points.map { worldX(it.lon, z) }
            val ys = points.map { worldY(it.lat, z) }
            if (xs.max() - xs.min() <= w && ys.max() - ys.min() <= h) return z
        }
        return MIN_ZOOM
    }

    /** One tile to draw: its OSM x/y at [z] and its top-left in view pixels. */
    data class Tile(val z: Int, val x: Int, val y: Int, val left: Double, val top: Double)

    /** Tiles covering a view whose top-left sits at world pixel ([originX], [originY]). Columns wrap
     *  around the antimeridian; rows outside the world are skipped. */
    fun tilesFor(z: Int, originX: Double, originY: Double, widthPx: Double, heightPx: Double): List<Tile> {
        val n = 1 shl z
        val x0 = floor(originX / TILE_PX).toInt()
        val y0 = floor(originY / TILE_PX).toInt()
        val x1 = floor((originX + widthPx) / TILE_PX).toInt()
        val y1 = floor((originY + heightPx) / TILE_PX).toInt()
        val out = ArrayList<Tile>()
        for (ty in y0..y1) {
            if (ty !in 0 until n) continue
            for (tx in x0..x1) {
                out += Tile(z, Math.floorMod(tx, n), ty, tx * TILE_PX - originX, ty * TILE_PX - originY)
            }
        }
        return out
    }
}

/**
 * A recorded GPS route over an OpenStreetMap street map. Only rendered when the user has opted in
 * ([NoopPrefs.routeMapTiles]): each tile request tells tile.openstreetmap.org the area of the route. Tiles
 * are cached under the app's cache dir, so re-opening a workout costs no network. With the tiles missing
 * (offline, or a fetch failed) the route still draws on the plain surface.
 *
 * Tile use follows the OSM tile usage policy: an identifying User-Agent, a visible attribution, local
 * caching, and no bulk prefetch (only the handful of tiles one view needs).
 */
@Composable
fun RouteMap(polyline: String, modifier: Modifier = Modifier) {
    val points = remember(polyline) { RouteMath.decode(polyline) }
    val context = LocalContext.current
    val density = LocalDensity.current.density
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(Metrics.cornerSm)),
    ) {
        if (points.size < 2) return@BoxWithConstraints
        // Tiles are laid out in dp so streets stay legible on dense screens; one tile pixel = one dp.
        val wDp = maxWidth.value.toDouble()
        val hDp = maxHeight.value.toDouble()
        val z = remember(points, wDp, hDp) { MapProjection.fitZoom(points, wDp, hDp, padPx = 16.0) }
        val xs = remember(points, z) { points.map { MapProjection.worldX(it.lon, z) } }
        val ys = remember(points, z) { points.map { MapProjection.worldY(it.lat, z) } }
        val originX = (xs.min() + xs.max()) / 2 - wDp / 2
        val originY = (ys.min() + ys.max()) / 2 - hDp / 2
        val tiles = remember(z, originX, originY, wDp, hDp) { MapProjection.tilesFor(z, originX, originY, wDp, hDp) }

        val loaded = remember(polyline) { mutableStateMapOf<MapProjection.Tile, ImageBitmap>() }
        LaunchedEffect(tiles) {
            for (t in tiles) launch { OsmTileCache.load(context, t)?.let { loaded[t] = it } }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(Palette.surfaceInset)
            val tilePx = (MapProjection.TILE_PX * density).toInt()
            for (t in tiles) {
                val img = loaded[t] ?: continue
                drawImage(
                    img,
                    dstOffset = IntOffset((t.left * density).toInt(), (t.top * density).toInt()),
                    dstSize = IntSize(tilePx, tilePx),
                )
            }
            val screen = points.indices.map {
                Offset(((xs[it] - originX) * density).toFloat(), ((ys[it] - originY) * density).toFloat())
            }
            val path = Path().apply {
                moveTo(screen.first().x, screen.first().y)
                screen.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, color = Palette.accent, style = Stroke(width = 4.dp.toPx()))
            drawCircle(Palette.accent, radius = 5.dp.toPx(), center = screen.first())
            drawCircle(Palette.statusCritical, radius = 5.dp.toPx(), center = screen.last())
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
            Text(
                "© OpenStreetMap contributors",
                style = NoopType.caption,
                color = Palette.textTertiary,
                modifier = Modifier.padding(4.dp),
            )
        }
    }
}

/** Disk-cached OSM raster tiles. A failed fetch returns null and is retried the next time the map opens. */
internal object OsmTileCache {
    private const val MAX_AGE_MS = 30L * 24 * 3600 * 1000

    suspend fun load(context: Context, t: MapProjection.Tile): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(context.cacheDir, "osm_tiles/${t.z}/${t.x}/${t.y}.png")
            val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS
            if (!fresh) {
                val conn = URL("https://tile.openstreetmap.org/${t.z}/${t.x}/${t.y}.png").openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("User-Agent", "NOOP-Android (personal build; route view)")
                try {
                    if (conn.responseCode != 200) return@runCatching null
                    val bytes = conn.inputStream.use { it.readBytes() }
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                } finally {
                    conn.disconnect()
                }
            }
            BitmapFactory.decodeFile(file.path)?.asImageBitmap()
        }.getOrNull()
    }
}
