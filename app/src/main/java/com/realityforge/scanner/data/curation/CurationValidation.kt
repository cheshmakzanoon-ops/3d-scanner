package com.realityforge.scanner.data.curation

internal val SHA256 = Regex("[0-9a-f]{64}")
internal fun requireStableId(value: String, label: String) = stableId(value, label)
internal fun stableId(value: String, label: String) {
    require(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}").matches(value)) { "$label has an invalid identifier." }
}
internal fun validateNote(note: String?) {
    if (note != null) require(note.length <= 240 && note.none { it == '\u0000' || it == '\r' })
}
