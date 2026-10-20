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
import org.json.JSONObject
import kotlin.math.*

private data class SurfaceDisplay(val xyz: FloatArray, val color: IntArray, val faces: IntArray)

/**
 * A bounded diagnostic triangle renderer. Export retains ALL indexed triangles;
 * this preview samples faces on large meshes to keep Canvas responsive.
 * M09+ should replace it with Filament and physically based, UV-baked materials.
 */
@Composable
fun SurfaceMeshViewer(mesh: JSONObject, modifier: Modifier = Modifier, wireframe: Boolean = false) {
    val geometry = remember(mesh) {
        val vertices = mesh.getJSONArray("vertices")
        val faces = mesh.getJSONArray("triangles")
        val coords = FloatArray(vertices.length() * 3)
        val colors = IntArray(vertices.length())
        for (i in 0 until vertices.length()) {
            val v = vertices.getJSONArray(i)
            coords[i*3] = v.getDouble(0).toFloat()
            coords[i*3+1] = -v.getDouble(1).toFloat()
            coords[i*3+2] = -v.getDouble(2).toFloat()
            colors[i] = (v.getInt(3).coerceIn(0,255) shl 16) or
                (v.getInt(4).coerceIn(0,255) shl 8) or v.getInt(5).coerceIn(0,255)
        }
        val indices = IntArray(faces.length() * 3)
        for (i in 0 until faces.length()) {
            val f = faces.getJSONArray(i)
            for(k in 0..2)indices[i*3+k]=f.getInt(k)
        }
        if (vertices.length()>0) {
            val count=vertices.length()
            val x=(0 until count).map { coords[it*3] }.sorted()
            val y=(0 until count).map { coords[it*3+1] }.sorted()
            val z=(0 until count).map { coords[it*3+2] }.sorted()
            val cx=x[count/2];val cy=y[count/2];val cz=z[count/2]
            val radius=(0 until count).map {
                sqrt((coords[it*3]-cx).pow(2)+(coords[it*3+1]-cy).pow(2)+(coords[it*3+2]-cz).pow(2))
            }.sorted()[(count*.95).toInt().coerceAtMost(count-1)].coerceAtLeast(.001f)
            for (i in 0 until count) {
                coords[i*3]=(coords[i*3]-cx)/radius
                coords[i*3+1]=(coords[i*3+1]-cy)/radius
                coords[i*3+2]=(coords[i*3+2]-cz)/radius
            }
        }
        SurfaceDisplay(coords,colors,indices)
    }
    var yaw by remember(mesh) { mutableFloatStateOf(0f) }
    var pitch by remember(mesh) { mutableFloatStateOf(0f) }
    var zoom by remember(mesh) { mutableFloatStateOf(1f) }
    Canvas(modifier.background(Color(0xFF0A1218))
        .semantics { contentDescription = "Observed triangle mesh with vertex colors; drag to orbit and pinch to zoom" }
        .pointerInput(mesh) {
            detectTransformGestures { _,pan,magnify,_ ->
                yaw += pan.x*.008f
                pitch=(pitch+pan.y*.008f).coerceIn(-1.5f,1.5f)
                zoom=(zoom*magnify).coerceIn(.3f,5f)
            }
        }) {
        val count=geometry.xyz.size/3
        val positioned=FloatArray(geometry.xyz.size)
        val cy=cos(yaw);val sy=sin(yaw);val cp=cos(pitch);val sp=sin(pitch)
        for(i in 0 until count){
            val x=geometry.xyz[i*3];val y=geometry.xyz[i*3+1];val z=geometry.xyz[i*3+2]
            val rotatedX=cy*x+sy*z
            val rotatedZ=-sy*x+cy*z
            positioned[i*3]=rotatedX
            positioned[i*3+1]=cp*y-sp*rotatedZ
            positioned[i*3+2]=sp*y+cp*rotatedZ
        }
        val total=geometry.faces.size/3
        val stride=max(1,ceil(total/12000.0).toInt())
        val sorted=ArrayList<Pair<Int,Float>>(total/stride+1)
        for(f in 0 until total step stride){
            val k=f*3
            val a=geometry.faces[k];val b=geometry.faces[k+1];val c=geometry.faces[k+2]
            if(a !in 0 until count || b !in 0 until count || c !in 0 until count)continue
            sorted.add(f to ((positioned[a*3+2]+positioned[b*3+2]+positioned[c*3+2])/3f))
        }
        sorted.sortBy { it.second }
        val scale=min(size.width,size.height)*.43f*zoom
        for ((f,_) in sorted){
            val k=f*3
            val a=geometry.faces[k];val b=geometry.faces[k+1];val c=geometry.faces[k+2]
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
            val rgb=geometry.color[a]
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
