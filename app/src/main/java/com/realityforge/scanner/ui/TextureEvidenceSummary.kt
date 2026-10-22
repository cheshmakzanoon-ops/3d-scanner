package com.realityforge.scanner.ui

import org.json.JSONObject

/** Compact atlas texels can blend cameras; absent face-label measurements are never zeros. */
internal fun textureCameraSwitchSummary(texture: JSONObject): String {
    if (texture.optString("representation") == "compact_visible_photographic_atlas")
        return "Camera-switch edges: not applicable to blended compact atlases."
    fun count(key: String): Long? {
        val raw = texture.opt(key)
        if (raw !is Int && raw !is Long) return null
        return (raw as Number).toLong().takeIf { it in 0..100_000_000 }
    }
    val before = count("viewSwitchEdgesBefore")
    val after = count("viewSwitchEdges")
    return if (before == null || after == null) "Camera-switch edges: unavailable."
        else "Camera-switch edges: $before → $after."
}
