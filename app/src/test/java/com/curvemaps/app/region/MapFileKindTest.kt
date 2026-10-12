package com.curvemaps.app.region

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlin.random.Random

/**
 * "Load map file" accepts both a `.motomap` package and a bare PMTiles
 * basemap, decided from the bytes alone. A wrong decision either sends a
 * basemap through the package installer or a package through the legacy
 * basemap import (which would silently drop its graph and geocoder), so the
 * classification is pinned against real files, near misses, and random bytes.
 */
class MapFileKindTest {

    private fun pmtilesHeader(version: Int = 3) =
        "PMTiles".encodeToByteArray() + byteArrayOf(version.toByte()) + ByteArray(119)

    @Test
    fun aPmtilesV3HeaderIsABasemap() {
        assertEquals(MapFileKind.PMTILES, MapFileKind.sniff(pmtilesHeader()))
    }

    @Test
    fun otherPmtilesVersionsAreNotAccepted() {
        assertEquals(MapFileKind.UNKNOWN, MapFileKind.sniff(pmtilesHeader(version = 2)))
        assertEquals(MapFileKind.UNKNOWN, MapFileKind.sniff(pmtilesHeader(version = 4)))
    }

    @Test
    fun theRealCommittedMonacoPackageIsAPackage() {
        val file = SyntheticRegionPackage.repoFile("app/src/androidTest/assets/regions/monaco.motomap")
        assertEquals(MapFileKind.PACKAGE, MapFileKind.sniff(file.inputStream()))
    }

    @Test
    fun aSyntheticPackageIsAPackageWhateverItIsCalled() {
        val file = File.createTempFile("renamed-download", ".pmtiles").also { it.deleteOnExit() }
        SyntheticRegionPackage(file).build()
        assertEquals(MapFileKind.PACKAGE, MapFileKind.sniff(file.inputStream()))
    }

    @Test
    fun emptyTruncatedAndForeignFilesAreUnknown() {
        assertEquals(MapFileKind.UNKNOWN, MapFileKind.sniff(ByteArray(0)))
        assertEquals(MapFileKind.UNKNOWN, MapFileKind.sniff("PMTil".encodeToByteArray()))
        // The PMTiles magic with the version byte missing is truncated, not v3.
        assertEquals(MapFileKind.UNKNOWN, MapFileKind.sniff("PMTiles".encodeToByteArray()))
        assertEquals(MapFileKind.UNKNOWN, MapFileKind.sniff(byteArrayOf(0x50, 0x4B)))
        assertEquals(MapFileKind.UNKNOWN, MapFileKind.sniff("<html>".encodeToByteArray()))
        // An empty ZIP (end-of-central-directory first) holds no package.
        assertEquals(MapFileKind.UNKNOWN, MapFileKind.sniff(byteArrayOf(0x50, 0x4B, 0x05, 0x06)))
    }

    @Test
    fun streamsAreClassifiedFromAtMostTheirFirstBytesAndClosed() {
        var closed = false
        var read = 0
        val source = object : InputStream() {
            private val inner = ByteArrayInputStream(pmtilesHeader())
            override fun read(): Int = inner.read().also { if (it >= 0) read++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                inner.read(b, off, len).also { if (it > 0) read += it }
            override fun close() {
                closed = true
            }
        }
        assertEquals(MapFileKind.PMTILES, MapFileKind.sniff(source))
        assertEquals(true, closed)
        assertEquals(MapFileKind.SNIFF_BYTES, read)
    }

    @Test
    fun aDribblingStreamIsStillClassified() {
        val bytes = pmtilesHeader()
        val slow = object : InputStream() {
            var pos = 0
            override fun read(): Int = if (pos < bytes.size) bytes[pos++].toInt() and 0xFF else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= bytes.size) return -1
                b[off] = bytes[pos++]
                return 1
            }
        }
        assertEquals(MapFileKind.PMTILES, MapFileKind.sniff(slow))
    }

    @Test
    fun randomBytesNeverThrowAndOnlyExactMagicsMatch() {
        val random = Random(20261003)
        repeat(5_000) {
            val bytes = random.nextBytes(random.nextInt(0, 16))
            val kind = MapFileKind.sniff(bytes)
            val isPmtiles = bytes.size >= 8 &&
                bytes.copyOfRange(0, 7).contentEquals("PMTiles".encodeToByteArray()) && bytes[7].toInt() == 3
            val isZip = bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x50, 0x4B, 3, 4))
            val expected = when {
                isPmtiles -> MapFileKind.PMTILES
                isZip -> MapFileKind.PACKAGE
                else -> MapFileKind.UNKNOWN
            }
            assertEquals(bytes.joinToString(" ") { "%02x".format(it) }, expected, kind)
        }
    }
}
