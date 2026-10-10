package com.realityforge.scanner.engine

import android.content.Context
import android.net.Uri
import com.realityforge.scanner.data.ProjectRepository
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point3
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** No network. The user must capture a printed 9x6 INNER-CORNER chessboard from many angles. */
object LensCalibration {
    const val BOARD_COLUMNS = 9
    const val BOARD_ROWS = 6
    private const val MAX_IMAGE_BYTES = 42L * 1024 * 1024
    private const val MAX_IMAGES = 40
    const val SCHEMA_VERSION = 1

    fun file(repository: ProjectRepository, projectId: String) =
        File(repository.directory(projectId), "camera-calibration-v1.json")

    fun read(repository: ProjectRepository, projectId: String): LensProfile? {
        val source = file(repository, projectId)
        if (!source.exists()) return null
        require(source.length() in 30..10_000) { "Calibration profile is corrupt" }
        val json = JSONObject(source.readText(Charsets.UTF_8))
        require(json.getInt("schema") == SCHEMA_VERSION && json.getString("model") == "brown-conrady-5") {
            "Unrecognized calibration version"
        }
        return LensProfile(
            json.getInt("width"), json.getInt("height"),
            json.getDouble("fx"), json.getDouble("fy"), json.getDouble("cx"), json.getDouble("cy"),
            json.getDouble("k1"), json.getDouble("k2"), json.getDouble("p1"), json.getDouble("p2"), json.getDouble("k3"),
            json.getDouble("reprojectionRmsPixels"), json.getInt("acceptedViews"),
            if (json.isNull("focal35mm")) null else json.getDouble("focal35mm"),
        )
    }

    fun json(profile: LensProfile) = JSONObject()
        .put("schema", SCHEMA_VERSION).put("model", "brown-conrady-5")
        .put("orientation", "exif-upright")
        .put("width", profile.width).put("height", profile.height)
        .put("fx", profile.fx).put("fy", profile.fy).put("cx", profile.cx).put("cy", profile.cy)
        .put("k1", profile.k1).put("k2", profile.k2).put("p1", profile.p1).put("p2", profile.p2).put("k3", profile.k3)
        .put("reprojectionRmsPixels", profile.reprojectionRmsPixels)
        .put("acceptedViews", profile.acceptedViews)
        .put("focal35mm", profile.focal35mm ?: JSONObject.NULL)
        .toString()

    /**
     * Process every image once and discard temporary pixels; retain only chessboard corners.
     * All photos must be captured with the same physical camera, zoom, crop and orientation.
     * A chessboard of 9x6 internal corners needs 10x7 alternating squares.
     */
    suspend fun calculate(context: Context, uris: List<Uri>, progress: (Int, Int) -> Unit): LensProfile {
        require(uris.size in 10..MAX_IMAGES) { "Select 10–40 different photos of a 9x6-inner-corner chessboard." }
        require(OpenCVLoader.initLocal()) { "OpenCV could not load." }
        val objectPoints = ArrayList<Mat>()
        val imagePoints = ArrayList<Mat>()
        val focalObservations = ArrayList<Double>()
        val centers = ArrayList<Pair<Double, Double>>()
        val sizes = ArrayList<Double>()
        val checkPattern = Size(BOARD_COLUMNS.toDouble(), BOARD_ROWS.toDouble())
        var width=0; var height=0
        try {
            for ((index,uri) in uris.withIndex()) {
                coroutineContext.ensureActive()
                progress(index,uris.size)
                val temp = File.createTempFile("rf-calib-", ".img", context.cacheDir)
                try {
                    requireNotNull(context.contentResolver.openInputStream(uri)) { "Calibration image cannot be opened" }.use { input ->
                        FileOutputStream(temp).use { output ->
                            val buffer=ByteArray(64*1024);var total=0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val read=input.read(buffer);if(read<0)break
                                total+=read;require(total<=MAX_IMAGE_BYTES) { "Calibration image exceeds 42 MB" }
                                output.write(buffer,0,read)
                            }
                        }
                    }
                    // The EXIF focal ratio catches lens changes when metadata is present.
                    ImageLoader.focal35(temp)?.let { focalObservations.add(it) }
                    val bitmap=ImageLoader.upright(temp,1600)
                    val rgba=Mat();val gray=Mat();val corners=MatOfPoint2f()
                    try {
                        if (width==0) {width=bitmap.width;height=bitmap.height}
                        require(bitmap.width==width&&bitmap.height==height) {
                            "All calibration images must have the same upright dimensions, crop and orientation."
                        }
                        Utils.bitmapToMat(bitmap,rgba)
                        Imgproc.cvtColor(rgba,gray,Imgproc.COLOR_RGBA2GRAY)
                        val found=Calib3d.findChessboardCorners(
                            gray,checkPattern,corners,
                            Calib3d.CALIB_CB_ADAPTIVE_THRESH or Calib3d.CALIB_CB_NORMALIZE_IMAGE,
                        )
                        if (!found) continue
                        Imgproc.cornerSubPix(gray,corners,Size(7.0,7.0),Size(-1.0,-1.0),
                            TermCriteria(TermCriteria.COUNT+TermCriteria.EPS,35,.015))
                        val xy=corners.toArray()
                        if (xy.size!=BOARD_COLUMNS*BOARD_ROWS)continue
                        val left=xy.minOf{it.x};val right=xy.maxOf{it.x}
                        val top=xy.minOf{it.y};val bottom=xy.maxOf{it.y}
                        val area=((right-left)*(bottom-top))/(width.toDouble()*height)
                        if (area !in .018..0.90)continue
                        centers.add(((right+left)/2/width) to ((bottom+top)/2/height))
                        sizes.add(area)
                        imagePoints.add(MatOfPoint2f(*xy))
                        objectPoints.add(MatOfPoint3f(*Array(BOARD_ROWS*BOARD_COLUMNS) { i ->
                            Point3((i%BOARD_COLUMNS).toDouble(),(i/BOARD_COLUMNS).toDouble(),0.0)
                        }))
                    } finally {
                        corners.release();gray.release();rgba.release();bitmap.recycle()
                    }
                } finally {temp.delete()}
            }
            progress(uris.size,uris.size)
            require(imagePoints.size>=10) {
                "Only ${imagePoints.size} useful checkerboard views; at least 10 of your photos must clearly show all 9x6 inner corners."
            }
            // Reject uninformative calibration sets (e.g. identical front-on board photos).
            val xRange=(centers.maxOf{it.first}-centers.minOf{it.first})
            val yRange=(centers.maxOf{it.second}-centers.minOf{it.second})
            val sRange=sizes.maxOrNull()!!/max(sizes.minOrNull()!!,1e-9)
            require(xRange>=.12 && yRange>=.10 && sRange>=1.25) {
                "Checkerboard viewpoints lack variety. Move the board to image edges and vary tilt/distance."
            }
            if (focalObservations.size>1) {
                val low=focalObservations.minOrNull()!!;val high=focalObservations.maxOrNull()!!
                require(high/low<=1.08) { "Different lenses or zoom settings detected in calibration photos." }
            }
            val k=Mat();val distortion=Mat()
            val rvecs=ArrayList<Mat>();val tvecs=ArrayList<Mat>()
            try {
                // Model intentionally only uses k1,k2,p1,p2,k3; no unsupported rational terms.
                val rms=Calib3d.calibrateCamera(objectPoints,imagePoints,
                    Size(width.toDouble(),height.toDouble()),k,distortion,rvecs,tvecs)
                val flat=DoubleArray((distortion.total()*distortion.channels()).toInt())
                require(flat.size>=5) { "Calibration did not return the five distortion coefficients" }
                distortion.get(0,0,flat)
                val coeff=flat.take(5)
                return LensProfile(width,height,
                    k.get(0,0)[0],k.get(1,1)[0],k.get(0,2)[0],k.get(1,2)[0],
                    coeff[0],coeff[1],coeff[2],coeff[3],coeff[4],rms,imagePoints.size,
                    if (focalObservations.isEmpty()) null else focalObservations.sorted()[focalObservations.size/2])
            } finally {
                k.release();distortion.release();rvecs.forEach(Mat::release);tvecs.forEach(Mat::release)
            }
        } finally {objectPoints.forEach(Mat::release);imagePoints.forEach(Mat::release)}
    }
}
