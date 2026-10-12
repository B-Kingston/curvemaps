package com.curvemaps.app.region

import java.io.InputStream

/**
 * What a user-picked map file is, decided from its first bytes so the single
 * **Load map file** action can accept either format regardless of file name
 * (the picker, downloads and share sheets routinely rename files).
 */
enum class MapFileKind {
    /** A complete regional package (ZIP64 container): tiles + graph + geocoder. */
    PACKAGE,

    /** A bare PMTiles v3 basemap: pictures only, no routing or search data. */
    PMTILES,

    UNKNOWN,
    ;

    companion object {
        /** Bytes [sniff] needs: "PMTiles" plus the version byte. */
        const val SNIFF_BYTES = 8

        private val PMTILES_MAGIC = "PMTiles".encodeToByteArray()
        private const val PMTILES_VERSION = 3
        private val ZIP_LOCAL_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

        fun sniff(header: ByteArray): MapFileKind = when {
            header.size > PMTILES_MAGIC.size &&
                header.copyOfRange(0, PMTILES_MAGIC.size).contentEquals(PMTILES_MAGIC) &&
                header[PMTILES_MAGIC.size].toInt() == PMTILES_VERSION -> PMTILES
            header.size >= ZIP_LOCAL_HEADER.size &&
                header.copyOfRange(0, ZIP_LOCAL_HEADER.size).contentEquals(ZIP_LOCAL_HEADER) -> PACKAGE
            else -> UNKNOWN
        }

        /** Reads at most [SNIFF_BYTES] from [input] (closing it) and classifies them. */
        fun sniff(input: InputStream): MapFileKind = input.use { stream ->
            val header = ByteArray(SNIFF_BYTES)
            var filled = 0
            while (filled < header.size) {
                val count = stream.read(header, filled, header.size - filled)
                if (count < 0) break
                filled += count
            }
            sniff(header.copyOf(filled))
        }
    }
}
