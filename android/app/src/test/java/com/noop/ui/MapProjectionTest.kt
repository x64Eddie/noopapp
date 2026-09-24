package com.noop.ui

import com.noop.analytics.RouteMath.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapProjectionTest {
    @Test fun worldOrigin_matchesOsmTileScheme() {
        // Zoom 0 is one 256 px tile: lon −180 is x 0, the equator is the vertical middle.
        assertEquals(0.0, MapProjection.worldX(-180.0, 0), 1e-9)
        assertEquals(128.0, MapProjection.worldX(0.0, 0), 1e-9)
        assertEquals(128.0, MapProjection.worldY(0.0, 0), 1e-9)
    }

    @Test fun knownTile_elPaso() {
        // 31.7619 N, 106.4850 W at z12: x = 73.515/360·4096 = 836.4, y = (1 − asinh(tan φ)/π)/2·4096 = 1666.6.
        val x = (MapProjection.worldX(-106.4850, 12) / 256).toInt()
        val y = (MapProjection.worldY(31.7619, 12) / 256).toInt()
        assertEquals(836, x)
        assertEquals(1666, y)
    }

    @Test fun latAt_invertsWorldY() {
        for (lat in listOf(-60.0, -10.5, 0.0, 31.76, 70.0)) {
            assertEquals(lat, MapProjection.latAt(MapProjection.worldY(lat, 14), 14), 1e-9)
        }
    }

    @Test fun fitZoom_picksDeepestZoomThatFits() {
        // A ~5 km east-west run in a 360×220 view.
        val run = listOf(LatLng(31.76, -106.50), LatLng(31.77, -106.45))
        val z = MapProjection.fitZoom(run, 360.0, 220.0, 16.0)
        val span = { zz: Int -> MapProjection.worldX(-106.45, zz) - MapProjection.worldX(-106.50, zz) }
        assertTrue(span(z) <= 360.0 - 32)
        assertTrue(span(z + 1) > 360.0 - 32)
    }

    @Test fun tilesFor_coversView_andWrapsColumns() {
        val tiles = MapProjection.tilesFor(2, originX = -100.0, originY = 10.0, widthPx = 360.0, heightPx = 220.0)
        // Columns −1..1 → wrapped to 3,0,1; rows 0..0.
        assertEquals(listOf(3, 0, 1), tiles.map { it.x })
        assertEquals(-156.0, tiles.first().left, 1e-9)
        assertEquals(-10.0, tiles.first().top, 1e-9)
    }
}
