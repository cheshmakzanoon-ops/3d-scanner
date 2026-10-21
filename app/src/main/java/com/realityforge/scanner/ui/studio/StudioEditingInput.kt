package com.realityforge.scanner.ui.studio

import com.realityforge.scanner.studio.editing.EditPoint

/** Explicit source-frame coordinate entry, independent of rendering or stored master data. */
internal fun parseEditPoint(text: String): EditPoint {
    val parts=text.trim().split(Regex("[\\s;]+"))
    require(parts.size==3)
    return EditPoint(requireNotNull(parts[0].toDoubleOrNull()),requireNotNull(parts[1].toDoubleOrNull()),requireNotNull(parts[2].toDoubleOrNull()))
}
