package com.personlines.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker

enum class Part(val label: String, val color: Int) {
    PIERNAS("Piernas", 0xFF00E5FF.toInt()),
    BRAZOS("Brazos", 0xFFFF4081.toInt()),
    TORSO("Torso", 0xFFFFEA00.toInt()),
    CABEZA("Cabeza", 0xFF76FF03.toInt()),
    OJOS("Ojos", 0xFFFFFFFF.toInt()),
    CABELLO("Cabello", 0xFFE040FB.toInt())
}

// Un trazo suelto entre dos puntos (coordenadas normalizadas 0..1)
class Seg(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val color: Int)

class Detection(
    val w: Int,
    val h: Int,
    val segs: List<Seg>,
    val hair: FloatArray?,
    val hairColor: Int
) {
    var mirror = false
    var fit = false
}

// Achica a máx 480 px y rota para que quede derecha
fun prepBitmap(src: Bitmap, rotation: Int): Bitmap {
    val m = Matrix()
    val s = minOf(1f, 480f / maxOf(src.width, src.height))
    m.postScale(s, s)
    if (rotation != 0) m.postRotate(rotation.toFloat())
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
}

class PersonDetector(private val context: Context) {

    private var pose: PoseLandmarker? = null
    private var face: FaceLandmarker? = null
    private var seg: ImageSegmenter? = null

    private fun base(name: String) = BaseOptions.builder().setModelAssetPath(name).build()

    private fun getPose(): PoseLandmarker {
        pose?.let { return it }
        val o = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(base("pose_landmarker_lite.task"))
            .setRunningMode(RunningMode.IMAGE)
            .setNumPoses(4)
            .build()
        return PoseLandmarker.createFromOptions(context, o).also { pose = it }
    }

    private fun getFace(): FaceLandmarker {
        face?.let { return it }
        val o = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base("face_landmarker.task"))
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(4)
            .build()
        return FaceLandmarker.createFromOptions(context, o).also { face = it }
    }

    private fun getSeg(): ImageSegmenter {
        seg?.let { return it }
        val o = ImageSegmenter.ImageSegmenterOptions.builder()
            .setBaseOptions(base("selfie_multiclass_256x256.tflite"))
            .setRunningMode(RunningMode.IMAGE)
            .setOutputCategoryMask(true)
            .setOutputConfidenceMasks(false)
            .build()
        return ImageSegmenter.createFromOptions(context, o).also { seg = it }
    }

    fun detect(bmp: Bitmap, parts: Set<Part>): Detection {
        val img = BitmapImageBuilder(bmp).build()
        val out = ArrayList<Seg>()
        var hair: FloatArray? = null

        if (Part.PIERNAS in parts || Part.BRAZOS in parts || Part.TORSO in parts) {
            for (lm in getPose().detect(img).landmarks()) {
                if (Part.PIERNAS in parts) {
                    chain(lm, intArrayOf(23, 25, 27, 31), false, true, Part.PIERNAS.color, out)
                    chain(lm, intArrayOf(24, 26, 28, 32), false, true, Part.PIERNAS.color, out)
                    chain(lm, intArrayOf(27, 29, 31), true, true, Part.PIERNAS.color, out)
                    chain(lm, intArrayOf(28, 30, 32), true, true, Part.PIERNAS.color, out)
                }
                if (Part.BRAZOS in parts) {
                    chain(lm, intArrayOf(11, 13, 15, 19), false, true, Part.BRAZOS.color, out)
                    chain(lm, intArrayOf(12, 14, 16, 20), false, true, Part.BRAZOS.color, out)
                    chain(lm, intArrayOf(15, 17), false, true, Part.BRAZOS.color, out)
                    chain(lm, intArrayOf(16, 18), false, true, Part.BRAZOS.color, out)
                }
                if (Part.TORSO in parts) {
                    chain(lm, intArrayOf(11, 12, 24, 23), true, true, Part.TORSO.color, out)
                }
            }
        }

        if (Part.CABEZA in parts || Part.OJOS in parts) {
            for (lm in getFace().detect(img).faceLandmarks()) {
                if (Part.CABEZA in parts) {
                    chain(lm, FACE_OVAL, true, false, Part.CABEZA.color, out)
                    chain(lm, BROW_L, false, false, Part.CABEZA.color, out)
                    chain(lm, BROW_R, false, false, Part.CABEZA.color, out)
                }
                if (Part.OJOS in parts) {
                    chain(lm, EYE_L, true, false, Part.OJOS.color, out)
                    chain(lm, EYE_R, true, false, Part.OJOS.color, out)
                    chain(lm, IRIS_R, true, false, Part.OJOS.color, out)
                    chain(lm, IRIS_L, true, false, Part.OJOS.color, out)
                }
            }
        }

        if (Part.CABELLO in parts) {
            hair = hairEdges(img)
        }

        return Detection(bmp.width, bmp.height, out, hair, Part.CABELLO.color)
    }

    // Une puntos consecutivos con trazos
    private fun chain(
        lm: List<NormalizedLandmark>,
        idx: IntArray,
        closed: Boolean,
        useVis: Boolean,
        color: Int,
        out: MutableList<Seg>
    ) {
        val n = idx.size
        val pairs = if (closed) n else n - 1
        for (i in 0 until pairs) {
            val a = idx[i]
            val b = idx[(i + 1) % n]
            if (a >= lm.size || b >= lm.size) continue
            val pa = lm[a]
            val pb = lm[b]
            if (useVis) {
                val va: Float = pa.visibility().orElse(1f)
                val vb: Float = pb.visibility().orElse(1f)
                if (va < 0.4f || vb < 0.4f) continue
            }
            out.add(Seg(pa.x(), pa.y(), pb.x(), pb.y(), color))
        }
    }

    // Borde de la máscara de cabello (clase 1 del modelo multiclase)
    private fun hairEdges(img: com.google.mediapipe.framework.image.MPImage): FloatArray? {
        val res = getSeg().segment(img)
        val mask = res.categoryMask().orElse(null) ?: return null
        val buf = ByteBufferExtractor.extract(mask)
        val mw = mask.width
        val mh = mask.height

        fun hair(x: Int, y: Int): Boolean {
            if (x < 0 || y < 0 || x >= mw || y >= mh) return false
            return buf.get(y * mw + x).toInt() == 1
        }

        val pts = ArrayList<Float>()
        for (y in 0 until mh) {
            for (x in 0 until mw) {
                if (hair(x, y) && (!hair(x - 1, y) || !hair(x + 1, y) || !hair(x, y - 1) || !hair(x, y + 1))) {
                    pts.add((x + 0.5f) / mw)
                    pts.add((y + 0.5f) / mh)
                }
            }
        }
        return pts.toFloatArray()
    }

    companion object {
        val FACE_OVAL = intArrayOf(
            10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378,
            400, 377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21,
            54, 103, 67, 109
        )
        val EYE_L = intArrayOf(263, 249, 390, 373, 374, 380, 381, 382, 362, 398, 384, 385, 386, 387, 388, 466)
        val EYE_R = intArrayOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246)
        val BROW_L = intArrayOf(276, 283, 282, 295, 285)
        val BROW_R = intArrayOf(46, 53, 52, 65, 55)
        val IRIS_R = intArrayOf(469, 470, 471, 472)
        val IRIS_L = intArrayOf(474, 475, 476, 477)
    }
}
