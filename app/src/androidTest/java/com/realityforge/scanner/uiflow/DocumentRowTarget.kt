package com.realityforge.scanner.uiflow

/** Visible screen bounds from the uniquely identified DocumentsUI item, never a guessed filename coordinate. */
internal data class DocumentRowBounds(val left:Int,val top:Int,val right:Int,val bottom:Int) {
    fun contains(x:Float,y:Float)=x>=left && x<right && y>=top && y<bottom
}

/** Avoid preview/other child controls, and never tap outside the visible owning directory. */
internal fun documentRowTapPoint(row:DocumentRowBounds, directory:DocumentRowBounds,
                                childControls:List<DocumentRowBounds>):Pair<Float,Float>? {
    val left=maxOf(row.left,directory.left).toDouble()
    val top=maxOf(row.top,directory.top).toDouble()
    val right=minOf(row.right,directory.right).toDouble()
    val bottom=minOf(row.bottom,directory.bottom).toDouble()
    if(right-left<2 || bottom-top<2) return null
    // Grid thumbnails put their preview button in the upper corner. Prefer the lower item body.
    for((fx,fy) in listOf(.5 to .8,.25 to .8,.75 to .8,.25 to .5,.5 to .5,.75 to .5,.25 to .25,.5 to .25,.75 to .25)) {
        val x=(left+(right-left)*fx).toFloat()
        val y=(top+(bottom-top)*fy).toFloat()
        if(row.contains(x,y) && directory.contains(x,y) && childControls.none {it.contains(x,y)}) return x to y
    }
    return null
}
