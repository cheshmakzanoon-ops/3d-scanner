package com.realityforge.scanner.export

import java.io.File

/** Bounded validator for the exact existing ASCII writers; richer064 encodings use their own independent reader. */
internal object LegacyPlyValidation {
    fun validate(file: File, surface: Boolean) {
        file.bufferedReader(Charsets.US_ASCII, 65536).use { input ->
            fun line(): String {
                val text = StringBuilder()
                while (true) {
                    val c = input.read()
                    require(c >= 0) { "PLY_TRUNCATED" }
                    if (c == 10) return text.toString().removeSuffix("\r")
                    require(c in 32..126 || c == 13) { "PLY_CHARACTER" }
                    require(text.length < 1024) { "PLY_LINE_LIMIT" }
                    text.append(c.toChar())
                }
            }
            require(line() == "ply" && line() == "format ascii 1.0")
            val provenance = line()
            require(provenance.startsWith("comment RealityForge "))
            if (!surface && provenance == "comment RealityForge sparse_points; arbitrary scale; x-right y-down z-forward") {
                require(line() == "comment representation sparse_points")
            } else {
                require(if (surface) provenance.contains("observed open surface") else provenance.contains("sparse reconstruction"))
            }
            val vertices = line().removePrefix("element vertex ").toInt()
            require(vertices in 1..2_000_000)
            for (property in listOf("x", "y", "z")) require(line() == "property float $property")
            if (surface) for (property in listOf("nx", "ny", "nz")) require(line() == "property float $property")
            for (property in listOf("red", "green", "blue")) require(line() == "property uchar $property")
            var faces = 0
            if (surface) {
                faces = line().removePrefix("element face ").toInt(); require(faces in 1..4_000_000)
                require(line() == "property list uchar int vertex_indices")
            }
            require(line() == "end_header")
            repeat(vertices) {
                val fields = line().trim().split(Regex(" +"))
                val floatCount = if (surface) 6 else 3
                require(fields.size == floatCount + 3)
                for (i in 0 until floatCount) require(fields[i].toDouble().isFinite())
                for (i in floatCount until fields.size) require(fields[i].toInt() in 0..255)
            }
            repeat(faces) {
                val fields = line().trim().split(Regex(" +"))
                require(fields.size == 4 && fields[0] == "3")
                val indices = fields.drop(1).map { it.toInt() }
                require(indices.distinct().size == 3 && indices.all { it in 0 until vertices })
            }
            require(input.read() == -1) { "PLY_TRAILING_DATA" }
        }
    }
}
