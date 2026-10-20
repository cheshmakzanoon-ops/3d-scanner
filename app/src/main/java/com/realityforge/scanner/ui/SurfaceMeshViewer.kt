package com.realityforge.scanner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.realityforge.scanner.data.reopening.PreparedSurface
import kotlin.math.*

/**
 * A bounded diagnostic triangle renderer. Export retains ALL indexed triangles;
 * this preview samples faces on large meshes to keep Canvas responsive.
 * M09+ should replace it with Filament and physically based, UV-baked materials.
 */
@Composable
fun SurfaceMeshViewer(geometry: PreparedSurface, modifier: Modifier = Modifier, wireframe: Boolean = false) {
    var yaw by remember(geometry) { mutableFloatStateOf(0f) }
    var pitch by remember(geometry) { mutableFloatStateOf(0f) }
    var zoom by remember(geometry) { mutableFloatStateOf(1f) }
    Canvas(modifier.background(Color(0xFF0A1218))
        .semantics { contentDescription = "Observed triangle mesh with vertex colors; drag to orbit and pinch to zoom" }
        .pointerInput(geometry) {
            detectTransformGestures { _,pan,magnify,_ ->
                yaw += pan.x*.008f
                pitch=(pitch+pan.y*.008f).coerceIn(-1.5f,1.5f)
                zoom=(zoom*magnify).coerceIn(.3f,5f)
            }
        }) {
        val vertices = geometry.vertices
        val count=vertices.pointCount
        val positioned=FloatArray(count*3)
        val cy=cos(yaw);val sy=sin(yaw);val cp=cos(pitch);val sp=sin(pitch)
        for(i in 0 until count){
            val x=vertices.x(i);val y=vertices.y(i);val z=vertices.z(i)
            val rotatedX=cy*x+sy*z
            val rotatedZ=-sy*x+cy*z
            positioned[i*3]=rotatedX
            positioned[i*3+1]=cp*y-sp*rotatedZ
            positioned[i*3+2]=sp*y+cp*rotatedZ
        }
        val total=geometry.triangleCount
        val sorted=ArrayList<Pair<Int,Float>>(total)
        for(f in 0 until total){
            val a=geometry.index(f,0);val b=geometry.index(f,1);val c=geometry.index(f,2)
            if(a !in 0 until count || b !in 0 until count || c !in 0 until count)continue
            sorted.add(f to ((positioned[a*3+2]+positioned[b*3+2]+positioned[c*3+2])/3f))
        }
        sorted.sortBy { it.second }
        val scale=min(size.width,size.height)*.43f*zoom
        for ((f,_) in sorted){
            val a=geometry.index(f,0);val b=geometry.index(f,1);val c=geometry.index(f,2)
            val ax=positioned[a*3];val ay=positioned[a*3+1]
            val bx=positioned[b*3];val by=positioned[b*3+1]
            val cx=positioned[c*3];val cy2=positioned[c*3+1]
            val nx=(by-ay)*(positioned[c*3+2]-positioned[a*3+2])-
                (positioned[b*3+2]-positioned[a*3+2])*(cy2-ay)
            val ny=(positioned[b*3+2]-positioned[a*3+2])*(cx-ax)-
                (bx-ax)*(positioned[c*3+2]-positioned[a*3+2])
            val nz=(bx-ax)*(cy2-ay)-(by-ay)*(cx-ax)
            val nlen=sqrt(nx*nx+ny*ny+nz*nz).coerceAtLeast(1e-7f)
            val brightness=(.22f+.78f*abs((nx*.33f+ny*.36f+nz*.87f)/nlen)).coerceIn(.15f,1f)
            val rgb=vertices.rgb(a)
            val red=(((rgb shr 16) and 255)*brightness).toInt().coerceIn(0,255)
            val green=(((rgb shr 8) and 255)*brightness).toInt().coerceIn(0,255)
            val blue=((rgb and 255)*brightness).toInt().coerceIn(0,255)
            val path=Path().apply {
                moveTo(size.width/2+ax*scale,size.height/2-ay*scale)
                lineTo(size.width/2+bx*scale,size.height/2-by*scale)
                lineTo(size.width/2+cx*scale,size.height/2-cy2*scale)
                close()
            }
            if(wireframe)drawPath(path,Color(0xFFBDE6D8),style=Stroke(width=1f))
            else drawPath(path,Color(red,green,blue))
        }
    }
}
