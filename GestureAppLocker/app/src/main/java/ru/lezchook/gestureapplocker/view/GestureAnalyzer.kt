package ru.lezchook.gestureapplocker.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.room.Room
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import org.tensorflow.lite.Interpreter
import ru.lezchook.gestureapplocker.R
import ru.lezchook.gestureapplocker.database.AppDB
import ru.lezchook.gestureapplocker.databinding.FragmentMainBinding
import ru.lezchook.gestureapplocker.model.BlockModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

class GestureAnalyzer(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results: HandLandmarkerResult? = null
    private var linePaint = Paint()
    private var bigPoint = Paint()

    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    private var points: ArrayList<Array<NormalizedLandmark>> = ArrayList()
    private var count: Int = 0
    private var blockList: List<BlockModel>? = null
    private var database: AppDB? = null

    init {
        linePaint.color =
            ContextCompat.getColor(context!!, R.color.lines_color)
        linePaint.strokeWidth = 8F
        linePaint.style = Paint.Style.STROKE

        bigPoint.color = Color.RED
        bigPoint.style = Paint.Style.FILL
        bigPoint.strokeWidth = 15F
        database = context.let {
            Room.databaseBuilder(it, AppDB::class.java, "AppLockerDB")
                .allowMainThreadQueries()
                .build()
        }
        blockList = database!!.getAppDao().getBlockInfo()
    }

    private fun addToPointArray(point: Array<NormalizedLandmark>) {
        if (points.size >= 45) {
            points.removeAt(0)
        }
        points.add(point)
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (!blockList.isNullOrEmpty()) {
            if (blockList!![0].blockedFlag == 0) {
                return
            }
            results?.let { handLandmarkerResult ->
                for (landmark in handLandmarkerResult.landmarks()) {
                    val coordinates = arrayOf(
                        landmark[0], landmark[1], landmark[2], landmark[3], landmark[4],
                        landmark[5], landmark[6], landmark[7], landmark[8], landmark[9],
                        landmark[10], landmark[11], landmark[12], landmark[13], landmark[14],
                        landmark[15], landmark[16], landmark[17], landmark[18], landmark[19],
                        landmark[20])
                    addToPointArray(coordinates)

                    if (points.size == 45) {
                        gestureClassifier(normalizeArray())
                    }
                    HandLandmarker.HAND_CONNECTIONS.forEach {
                        canvas.drawLine(
                            handLandmarkerResult.landmarks()[0][it!!.start()]
                                .x() * imageWidth * scaleFactor,
                            handLandmarkerResult.landmarks()[0][it.start()]
                                .y() * imageHeight * scaleFactor,
                            handLandmarkerResult.landmarks()[0][it.end()]
                                .x() * imageWidth * scaleFactor,
                            handLandmarkerResult.landmarks()[0][it.end()]
                                .y() * imageHeight * scaleFactor,
                            linePaint
                        )
                    }
                }
            }
        }
    }

    private fun normalizeArray(): ArrayList<Float> {
        val numericDataList: ArrayList<Float> = ArrayList()
        val x_first = points[0][0].x() * imageHeight
        val y_first = points[0][0].y() * imageWidth
        for (i in 0 until 45) {
            for (j in 0 until 21) {
                val landmarkData = floatArrayOf(
                    (points[i][j].x() * imageHeight - x_first) / (imageHeight),
                    (points[i][j].y() * imageWidth - y_first) / (imageWidth)
                )
                numericDataList.addAll(landmarkData.toList())
            }
        }
        return numericDataList
    }

    private fun gestureClassifier(numericDataList: ArrayList<Float> ) {
        if (!blockList.isNullOrEmpty()) {
            val bb = ByteBuffer.allocateDirect(blockList!![0].tfliteModel.size)
            bb.order(ByteOrder.nativeOrder())
            bb.put(blockList!![0].tfliteModel)
            val inputVal = FloatArray(numericDataList.size)
            for (i in numericDataList.indices) {
                inputVal[i] = numericDataList[i]
            }
            val outputVal: ByteBuffer = ByteBuffer.allocateDirect(12)
            outputVal.order(ByteOrder.nativeOrder())
            val interpreter = Interpreter(bb)
            interpreter.run(inputVal, outputVal)
            outputVal.rewind()
            val result_1 = outputVal.getFloat(0)
            val result_2 = outputVal.getFloat(4)
            val result_3 = outputVal.getFloat(8)
            if (result_1 > result_2 && result_1 > result_3) {
                count -= 1
            } else if (result_2 > result_1 && result_2 > result_3) {
                count -= 1
            } else if ((result_3 > result_1) and (result_3 > result_2) and (result_3 > 0.90)) {
                count += 1
            }
            if (count == 20) {
                count = 0
                blockList!![0].blockedFlag = 0
                database?.getAppDao()?.insertBlockInfo(blockList!![0])
                Toast.makeText(context, "Доступ открыт!", Toast.LENGTH_LONG).show()
            }
            if (count == -100) {
                Toast.makeText(context, "Некорректный жест", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun setResults(handLandmarkerResults: HandLandmarkerResult, imageHeight: Int, imageWidth: Int, ) {
        results = handLandmarkerResults
        this.imageHeight = imageHeight
        this.imageWidth = imageWidth
        scaleFactor = max(width * 1f / imageWidth, height * 1f / imageHeight)
    }
}