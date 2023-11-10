/* Copyright 2021 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================
*/

package org.tensorflow.lite.examples.poseestimation.ml

import android.content.Context
import android.graphics.*
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.graphics.minus
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.examples.poseestimation.data.*
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.lang.Math.toDegrees
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

enum class ModelType {
    Lightning,
    Thunder
}

class MoveNet(private val interpreter: Interpreter, private var gpuDelegate: GpuDelegate?) :
    PoseDetector {

    companion object {
        private const val MIN_CROP_KEYPOINT_SCORE = .2f
        private const val CPU_NUM_THREADS = 4

        // Parameters that control how large crop region should be expanded from previous frames'
        // body keypoints.
        private const val TORSO_EXPANSION_RATIO = 1.9f
        private const val BODY_EXPANSION_RATIO = 1.2f

        // TFLite file names.
        private const val LIGHTNING_FILENAME = "movenet_lightning.tflite"
        private const val THUNDER_FILENAME = "movenet_thunder.tflite"

        private val INTERCEPTS: HashMap<String, Double> = hashMapOf(
//            "shoabd" to 8.766180213838261,
//            "shoflex" to 18.582027934758237,
//            "shoext" to 19.141679991541952,
//            "elbflex" to -5.739176564877553,
//            "hipabd" to 35.47208356016854,
//            "hipflex" to 99.11530075690126,
//            "hipext" to 23.765319920430954,
//            "kneeflex" to 5.02104718633808,

        //Non- linear model
            "shoabd" to 11.682313403366742,
            "shoflex" to 22.76828402868039,
            "shoext" to 21.473304496313325,
            "elbflex" to 28.905730477585266
        )

       // private val COEFFICIENTS: HashMap<String, Double> = hashMapOf(

//            "shoabd" to 0.7965293931770755,
//            "shoflex" to 0.6162105709567219,
//            "shoext" to 0.8246886287763818,
//            "elbflex" to 0.9927441984883095,
//            "hipabd" to 0.7123440406268039,
//            "hipflex" to 0.350340881927831,
//            "hipext" to 0.7566034116148802,
//            "kneeflex" to 0.9651127014683116


       // )

        private val COEFFICIENTS: HashMap<String, DoubleArray> = hashMapOf(
            "shoabd" to doubleArrayOf(0.0, 5.08856581e-01, 7.38623277e-03, -4.88007171e-05),
            "shoflex" to doubleArrayOf(0.0, 1.22546415e-01, 8.12350362e-03, -3.15136962e-05),
            "shoext" to doubleArrayOf(0.0, 3.57593199e-01, 1.75362702e-02, -1.70752374e-04),
            "elbflex" to doubleArrayOf(0.0, -2.42472583e-01, 1.27017453e-02, -3.94075183e-05)
        )



        // allow specifying model type.
        fun create(context: Context, device: Device, modelType: ModelType): MoveNet {
            val options = Interpreter.Options()
            var gpuDelegate: GpuDelegate? = null
            options.setNumThreads(CPU_NUM_THREADS)
            when (device) {
                Device.CPU -> {
                }
                Device.GPU -> {
                    gpuDelegate = GpuDelegate()
                    options.addDelegate(gpuDelegate)
                }
                Device.NNAPI -> options.setUseNNAPI(true)
            }
            return MoveNet(
                Interpreter(
                    FileUtil.loadMappedFile(
                        context,
                        if (modelType == ModelType.Lightning) LIGHTNING_FILENAME
                        else THUNDER_FILENAME
                    ), options
                ),
                gpuDelegate
            )
        }

        // default to lightning.
        fun create(context: Context, device: Device): MoveNet =
            create(context, device, ModelType.Lightning)
    }

    private var cropRegion: RectF? = null
    private var lastInferenceTimeNanos: Long = -1
    private val inputWidth = interpreter.getInputTensor(0).shape()[1]
    private val inputHeight = interpreter.getInputTensor(0).shape()[2]
    private var outputShape: IntArray = interpreter.getOutputTensor(0).shape()

    @RequiresApi(Build.VERSION_CODES.N)
    override fun estimatePoses(bitmap: Bitmap): List<Person> {
        val inferenceStartTimeNanos = SystemClock.elapsedRealtimeNanos()
        if (cropRegion == null) {
            cropRegion = initRectF(bitmap.width, bitmap.height)
        }
        var totalScore = 0f

        val numKeyPoints = outputShape[2]
        val keyPoints = mutableListOf<KeyPoint>()
        val jointToAngle = HashMap<String, Float>()

        //Calculate angle with non -linear Polynomial feature model intercept and coefficients
        //Formula: y=f(x)=β0+β1x+β2x2+β3x3+… +βdxd+ + β d x d + ϵ , where d is called the degree of the polynomial.

        val calculateAngle: (PointF, PointF, Double, DoubleArray) -> Float = { ba, bc, intercept, coef ->
            val dotProduct = ba.x * bc.x + ba.y * bc.y
            val normBC = sqrt(bc.x * bc.x + bc.y * bc.y)
            val normBA = sqrt(ba.x * ba.y + ba.y * ba.y)
            val cosineAngle = dotProduct / (normBA * normBC)
            val degreeAngle = toDegrees(acos(cosineAngle).toDouble())
            val predAngle = intercept +
                    degreeAngle * coef[1] +
                    degreeAngle * degreeAngle * coef[2] +
                    degreeAngle * degreeAngle * degreeAngle * coef[3]
            predAngle.toFloat()
        }

        //Calculate angle with linear Polynomial feature model intercept and coefficients

//        val calculateAngle: (PointF, PointF, Double, Double) -> Float = { ba, bc, intercept, coef ->
//            val dotProduct = ba.x * bc.x + ba.y * bc.y
//            val normBC = sqrt(bc.x * bc.x + bc.y * bc.y)
//            val normBA = sqrt(ba.x * ba.y + ba.y * ba.y)
//            val cosineAngle = dotProduct / (normBA * normBC)
//            val predAngle = intercept + toDegrees(acos(cosineAngle).toDouble()) * coef
//            predAngle.toFloat()
//        }

        cropRegion?.run {
            val rect = RectF(
                (left * bitmap.width),
                (top * bitmap.height),
                (right * bitmap.width),
                (bottom * bitmap.height)
            )
            val detectBitmap = Bitmap.createBitmap(
                rect.width().toInt(),
                rect.height().toInt(),
                Bitmap.Config.ARGB_8888
            )
            Canvas(detectBitmap).drawBitmap(
                bitmap,
                -rect.left,
                -rect.top,
                null
            )
            val inputTensor = processInputImage(detectBitmap, inputWidth, inputHeight)
            val outputTensor = TensorBuffer.createFixedSize(outputShape, DataType.FLOAT32)
            val widthRatio = detectBitmap.width.toFloat() / inputWidth
            val heightRatio = detectBitmap.height.toFloat() / inputHeight

            val positions = mutableListOf<Float>()

            val bodyPartToXY = HashMap<BodyPart, PointF>()

            inputTensor?.let { input ->
                interpreter.run(input.buffer, outputTensor.buffer.rewind())
                val output = outputTensor.floatArray
                for (idx in 0 until numKeyPoints) { // numKeyPoints = 17.
                    val x = output[idx * 3 + 1] * inputWidth * widthRatio
                    val y = output[idx * 3 + 0] * inputHeight * heightRatio

                    positions.add(x)
                    positions.add(y)

                    val score = output[idx * 3 + 2]
                    keyPoints.add(KeyPoint(BodyPart.fromInt(idx), PointF(x, y), score))
                    totalScore += score

                    bodyPartToXY[BodyPart.fromInt(idx)] = PointF(x, y)
                }
            }

            // Calculate joint angles.
            bodyPartToXY.let {
                var ba = it[BodyPart.LEFT_SHOULDER]?.minus(it[BodyPart.LEFT_ELBOW]!!)
                var bc = it[BodyPart.LEFT_WRIST]?.minus(it[BodyPart.LEFT_ELBOW]!!)
                if (ba != null && bc != null)
                    jointToAngle["leftElbow"] = calculateAngle(
                        ba, bc,
                        INTERCEPTS["elbflex"]!!,
                        COEFFICIENTS["elbflex"]!!
                    )

                ba = it[BodyPart.LEFT_ELBOW]?.minus(it[BodyPart.LEFT_SHOULDER]!!)
                bc = it[BodyPart.LEFT_HIP]?.minus(it[BodyPart.LEFT_SHOULDER]!!)
                if (ba != null && bc != null)
                    jointToAngle["leftShoulder"] = calculateAngle(
                        ba, bc,
                        INTERCEPTS["shoflex"]!!,
                        COEFFICIENTS["shoflex"]!!
                    )

//                ba = it[BodyPart.LEFT_SHOULDER]?.minus(it[BodyPart.LEFT_HIP]!!)
//                bc = it[BodyPart.LEFT_KNEE]?.minus(it[BodyPart.LEFT_HIP]!!)
//                if (ba != null && bc != null)
//                    jointToAngle["leftHip"] = calculateAngle(
//                        ba, bc,
//                        INTERCEPTS["hipabd"]!!,
//                        COEFFICIENTS["hipabd"]!!
//                    )

//                ba = it[BodyPart.LEFT_HIP]?.minus(it[BodyPart.LEFT_KNEE]!!)
//                bc = it[BodyPart.LEFT_ANKLE]?.minus(it[BodyPart.LEFT_KNEE]!!)
//                if (ba != null && bc != null)
//                    jointToAngle["leftKnee"] = calculateAngle(
//                        ba, bc,
//                        INTERCEPTS["kneeflex"]!!,
//                        COEFFICIENTS["kneeflex"]!!
//                    )

                ba = it[BodyPart.RIGHT_SHOULDER]?.minus(it[BodyPart.RIGHT_ELBOW]!!)
                bc = it[BodyPart.RIGHT_WRIST]?.minus(it[BodyPart.RIGHT_ELBOW]!!)
                if (ba != null && bc != null)
                    jointToAngle["rightElbow"] = calculateAngle(
                        ba, bc,
                        INTERCEPTS["elbflex"]!!,
                        COEFFICIENTS["elbflex"]!!
                    )

                ba = it[BodyPart.RIGHT_ELBOW]?.minus(it[BodyPart.RIGHT_SHOULDER]!!)
                bc = it[BodyPart.RIGHT_HIP]?.minus(it[BodyPart.RIGHT_SHOULDER]!!)
                if (ba != null && bc != null)
                    jointToAngle["rightShoulder"] = calculateAngle(
                        ba, bc,
                        INTERCEPTS["shoflex"]!!,
                        COEFFICIENTS["shoflex"]!!
                    )

//                ba = it[BodyPart.RIGHT_SHOULDER]?.minus(it[BodyPart.RIGHT_HIP]!!)
//                bc = it[BodyPart.RIGHT_KNEE]?.minus(it[BodyPart.RIGHT_HIP]!!)
//                if (ba != null && bc != null)
//                    jointToAngle["rightHip"] = calculateAngle(
//                        ba, bc,
//                        INTERCEPTS["hipabd"]!!,
//                        COEFFICIENTS["hipabd"]!!
//                    )

//                ba = it[BodyPart.RIGHT_HIP]?.minus(it[BodyPart.RIGHT_KNEE]!!)
//                bc = it[BodyPart.RIGHT_ANKLE]?.minus(it[BodyPart.RIGHT_KNEE]!!)
//                if (ba != null && bc != null)
//                    jointToAngle["rightKnee"] = calculateAngle(
//                        ba, bc,
//                        INTERCEPTS["kneeflex"]!!,
//                        COEFFICIENTS["kneeflex"]!!
//                    )
            }

            val matrix = Matrix()
            val points = positions.toFloatArray()

            matrix.postTranslate(rect.left, rect.top)
            matrix.mapPoints(points)
            keyPoints.forEachIndexed { index, keyPoint ->
                keyPoint.coordinate =
                    PointF(
                        points[index * 2],
                        points[index * 2 + 1]
                    )
            }
            // new crop region
            cropRegion = determineRectF(keyPoints, bitmap.width, bitmap.height)
        }
        lastInferenceTimeNanos =
            SystemClock.elapsedRealtimeNanos() - inferenceStartTimeNanos
        return listOf(
            Person(
                keyPoints = keyPoints,
                score = totalScore / numKeyPoints,
                jointToAngle = jointToAngle
            )
        )
    }

    override fun lastInferenceTimeNanos(): Long = lastInferenceTimeNanos

    override fun close() {
        gpuDelegate?.close()
        interpreter.close()
        cropRegion = null
    }

    /**
     * Prepare input image for detection
     */
    private fun processInputImage(bitmap: Bitmap, inputWidth: Int, inputHeight: Int): TensorImage? {
        val width: Int = bitmap.width
        val height: Int = bitmap.height

        val size = if (height > width) width else height
        val imageProcessor = ImageProcessor.Builder().apply {
            add(ResizeWithCropOrPadOp(size, size))
            add(ResizeOp(inputWidth, inputHeight, ResizeOp.ResizeMethod.BILINEAR))
        }.build()
        val tensorImage = TensorImage(DataType.UINT8)
        tensorImage.load(bitmap)
        return imageProcessor.process(tensorImage)
    }

    /**
     * Defines the default crop region.
     * The function provides the initial crop region (pads the full image from both
     * sides to make it a square image) when the algorithm cannot reliably determine
     * the crop region from the previous frame.
     */
    private fun initRectF(imageWidth: Int, imageHeight: Int): RectF {
        val xMin: Float
        val yMin: Float
        val width: Float
        val height: Float
        if (imageWidth > imageHeight) {
            width = 1f
            height = imageWidth.toFloat() / imageHeight
            xMin = 0f
            yMin = (imageHeight / 2f - imageWidth / 2f) / imageHeight
        } else {
            height = 1f
            width = imageHeight.toFloat() / imageWidth
            yMin = 0f
            xMin = (imageWidth / 2f - imageHeight / 2) / imageWidth
        }
        return RectF(
            xMin,
            yMin,
            xMin + width,
            yMin + height
        )
    }

    /**
     * Checks whether there are enough torso keypoints.
     * This function checks whether the model is confident at predicting one of the
     * shoulders/hips which is required to determine a good crop region.
     */
    private fun torsoVisible(keyPoints: List<KeyPoint>): Boolean {
        return ((keyPoints[BodyPart.LEFT_HIP.position].score > MIN_CROP_KEYPOINT_SCORE).or(
            keyPoints[BodyPart.RIGHT_HIP.position].score > MIN_CROP_KEYPOINT_SCORE
        )).and(
            (keyPoints[BodyPart.LEFT_SHOULDER.position].score > MIN_CROP_KEYPOINT_SCORE).or(
                keyPoints[BodyPart.RIGHT_SHOULDER.position].score > MIN_CROP_KEYPOINT_SCORE
            )
        )
    }

    /**
     * Determines the region to crop the image for the model to run inference on.
     * The algorithm uses the detected joints from the previous frame to estimate
     * the square region that encloses the full body of the target person and
     * centers at the midpoint of two hip joints. The crop size is determined by
     * the distances between each joints and the center point.
     * When the model is not confident with the four torso joint predictions, the
     * function returns a default crop which is the full image padded to square.
     */
    private fun determineRectF(
        keyPoints: List<KeyPoint>,
        imageWidth: Int,
        imageHeight: Int
    ): RectF {
        val targetKeyPoints = mutableListOf<KeyPoint>()
        keyPoints.forEach {
            targetKeyPoints.add(
                KeyPoint(
                    it.bodyPart,
                    PointF(
                        it.coordinate.x,
                        it.coordinate.y
                    ),
                    it.score
                )
            )
        }
        if (torsoVisible(keyPoints)) {
            val centerX =
                (targetKeyPoints[BodyPart.LEFT_HIP.position].coordinate.x +
                        targetKeyPoints[BodyPart.RIGHT_HIP.position].coordinate.x) / 2f
            val centerY =
                (targetKeyPoints[BodyPart.LEFT_HIP.position].coordinate.y +
                        targetKeyPoints[BodyPart.RIGHT_HIP.position].coordinate.y) / 2f

            val torsoAndBodyDistances =
                determineTorsoAndBodyDistances(keyPoints, targetKeyPoints, centerX, centerY)

            val list = listOf(
                torsoAndBodyDistances.maxTorsoXDistance * TORSO_EXPANSION_RATIO,
                torsoAndBodyDistances.maxTorsoYDistance * TORSO_EXPANSION_RATIO,
                torsoAndBodyDistances.maxBodyXDistance * BODY_EXPANSION_RATIO,
                torsoAndBodyDistances.maxBodyYDistance * BODY_EXPANSION_RATIO
            )

            var cropLengthHalf = list.maxOrNull() ?: 0f
            val tmp = listOf(centerX, imageWidth - centerX, centerY, imageHeight - centerY)
            cropLengthHalf = min(cropLengthHalf, tmp.maxOrNull() ?: 0f)
            val cropCorner = Pair(centerY - cropLengthHalf, centerX - cropLengthHalf)

            return if (cropLengthHalf > max(imageWidth, imageHeight) / 2f) {
                initRectF(imageWidth, imageHeight)
            } else {
                val cropLength = cropLengthHalf * 2
                RectF(
                    cropCorner.second / imageWidth,
                    cropCorner.first / imageHeight,
                    (cropCorner.second + cropLength) / imageWidth,
                    (cropCorner.first + cropLength) / imageHeight,
                )
            }
        } else {
            return initRectF(imageWidth, imageHeight)
        }
    }

    /**
     * Calculates the maximum distance from each keypoints to the center location.
     * The function returns the maximum distances from the two sets of keypoints:
     * full 17 keypoints and 4 torso keypoints. The returned information will be
     * used to determine the crop size. See determineRectF for more detail.
     */
    private fun determineTorsoAndBodyDistances(
        keyPoints: List<KeyPoint>,
        targetKeyPoints: List<KeyPoint>,
        centerX: Float,
        centerY: Float
    ): TorsoAndBodyDistance {
        val torsoJoints = listOf(
            BodyPart.LEFT_SHOULDER.position,
            BodyPart.RIGHT_SHOULDER.position,
            BodyPart.LEFT_HIP.position,
            BodyPart.RIGHT_HIP.position
        )

        var maxTorsoYRange = 0f
        var maxTorsoXRange = 0f
        torsoJoints.forEach { joint ->
            val distY = abs(centerY - targetKeyPoints[joint].coordinate.y)
            val distX = abs(centerX - targetKeyPoints[joint].coordinate.x)
            if (distY > maxTorsoYRange) maxTorsoYRange = distY
            if (distX > maxTorsoXRange) maxTorsoXRange = distX
        }

        var maxBodyYRange = 0f
        var maxBodyXRange = 0f
        for (joint in keyPoints.indices) {
            if (keyPoints[joint].score < MIN_CROP_KEYPOINT_SCORE) continue
            val distY = abs(centerY - keyPoints[joint].coordinate.y)
            val distX = abs(centerX - keyPoints[joint].coordinate.x)

            if (distY > maxBodyYRange) maxBodyYRange = distY
            if (distX > maxBodyXRange) maxBodyXRange = distX
        }
        return TorsoAndBodyDistance(
            maxTorsoYRange,
            maxTorsoXRange,
            maxBodyYRange,
            maxBodyXRange
        )
    }
}
