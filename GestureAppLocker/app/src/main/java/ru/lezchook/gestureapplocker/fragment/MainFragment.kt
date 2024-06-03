package ru.lezchook.gestureapplocker.fragment

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.room.Room
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import org.tensorflow.lite.Interpreter
import ru.lezchook.gestureapplocker.model.BlockModel
import ru.lezchook.gestureapplocker.database.AppDB
import ru.lezchook.gestureapplocker.databinding.FragmentMainBinding
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.IllegalArgumentException
import java.lang.IndexOutOfBoundsException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MainFragment : Fragment() {

    private lateinit var fragmentMainBinding: FragmentMainBinding
    private val READ_REQUEST_CODE: Int = 42
    private var handLandmarker: HandLandmarker? = null
    private var database: AppDB? = null
    private var wantToUnlock: Int = 0

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        fragmentMainBinding = FragmentMainBinding.inflate(inflater, container, false)
        initializeDatabase()
        setupHandLandmarker()
        initializeCamera()
        with(fragmentMainBinding) {
            buttonBlock.setOnClickListener {
                blockAccess()
            }
            buttonUnlock.setOnClickListener {
                unlockAccess()
            }
            buttonLoadModel.setOnClickListener {
                openDocument()
            }
            buttonGenerateCode.setOnClickListener {
                generateRecoveryCode()
            }
            buttonWriteCode.setOnClickListener {
                showRecoveryCodeForm()
            }
            buttonResetApps.setOnClickListener {
                resetAppList()
            }
        }
        return fragmentMainBinding.root
    }

    private fun initializeDatabase() {
        database = context?.let {
            Room.databaseBuilder(it, AppDB::class.java, "AppLockerDB")
                .allowMainThreadQueries()
                .build()
        }
    }

    private fun initializeCamera() {
        val cameraProvider = ProcessCameraProvider.getInstance(requireContext()).get()
        val cameraSelector = CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_FRONT).build()
        val preview = Preview.Builder().build()
        val imageAnalyzer = ImageAnalysis.Builder()
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply {
                setAnalyzer(Executors.newSingleThreadExecutor()) { image ->
                    val startTime = System.currentTimeMillis()
                    val bitmapBuffer = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                    image.use { bitmapBuffer.copyPixelsFromBuffer(image.planes[0].buffer) }
                    val rotationDegrees = image.imageInfo.rotationDegrees.toFloat()
                    val matrix = Matrix().apply {
                        postRotate(rotationDegrees)
                        postScale(-1f, 1f, image.width.toFloat(), image.height.toFloat())
                    }
                    val rotatedBitmap = Bitmap.createBitmap(bitmapBuffer, 0, 0, image.width, image.height, matrix, true)
                    val processedImage = BitmapImageBuilder(rotatedBitmap).build()
                    handLandmarker?.detectAsync(processedImage, System.currentTimeMillis())
                    val elapsedTime = System.currentTimeMillis() - startTime
                    val targetTimePerFrame = TimeUnit.SECONDS.toMillis(1) / 15
                    val sleepTime = targetTimePerFrame - elapsedTime
                    if (sleepTime > 0) {
                        try {
                            Thread.sleep(sleepTime)
                        } catch (e: InterruptedException) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
        preview.setSurfaceProvider(fragmentMainBinding.viewFinder.surfaceProvider)
    }

    private fun setupHandLandmarker() {
        val baseOptionBuilder = BaseOptions.builder()
        baseOptionBuilder.setDelegate(Delegate.GPU)
        baseOptionBuilder.setModelAssetPath("hand_landmarker.task")
        val baseOptions = baseOptionBuilder.build()
        val optionsBuilder =
            HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinHandDetectionConfidence(0.5F)
                .setMinTrackingConfidence(0.5F)
                .setNumHands(1)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener(this::drawAndAnalyze)

        val options = optionsBuilder.build()
        handLandmarker = HandLandmarker.createFromOptions(context, options)
    }

    private fun blockAccess() {
        val blockList = database?.getAppDao()?.getBlockInfo()
        if (blockList != null) {
            try {
                if (blockList[0].recoveryCode != "NULL") {
                    blockList[0].blockedFlag = 1
                    database?.getAppDao()?.insertBlockInfo(blockList[0])
                    Toast.makeText(context, "Доступ закрыт", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Создайте ключ восстановления перед блокировкой", Toast.LENGTH_LONG).show()
                }
            } catch (e: IndexOutOfBoundsException) {
                Toast.makeText(context, "Ошибка, загрузите модель", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun unlockAccess() {
        val blockList = database?.getAppDao()?.getBlockInfo()
        if (blockList != null) {
            try {
                if (blockList[0].blockedFlag == 1) {
                    wantToUnlock = 1
                    fragmentMainBinding.statusText.text = "Пройдите авторизацию"
                } else {
                    Toast.makeText(context, "Приложение не заблокировано", Toast.LENGTH_LONG).show()
                }
            } catch (e: IndexOutOfBoundsException) {
                Toast.makeText(context, "Приложение не заблокировано", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openDocument() {
        val blockList = database?.getAppDao()?.getBlockInfo()
        if (blockList != null) {
            try {
                if (blockList[0].blockedFlag == 1) {
                    Toast.makeText(context, "Отказ в доступе", Toast.LENGTH_LONG).show()
                } else {
                    openDocument_()
                }
            } catch (e: IndexOutOfBoundsException) {
                openDocument_()
            }
        }
    }

    private fun openDocument_() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        startActivityForResult(intent, READ_REQUEST_CODE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == READ_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            resultData?.data?.let { uri ->
                readFile(uri)
            }
        }
    }

    private fun readFile(uri: Uri) {
        try {
            val inputStream = context?.contentResolver?.openInputStream(uri)
            val buffer = ByteArray(2097152)
            var bytesRead: Int
            val output = ByteArrayOutputStream()
            try {
                while (inputStream!!.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
            } catch (e: IOException) {
                e.printStackTrace()
            }
            val file = output.toByteArray()
            try {
                val bb = ByteBuffer.allocateDirect(file.size)
                bb.order(ByteOrder.nativeOrder())
                bb.put(file)
                val interpreter = Interpreter(bb)
                val blockModel = BlockModel(
                    id = 0,
                    tfliteModel = file,
                    blockedFlag = 0,
                    recoveryCode = "NULL",
                    attemptCount = 0
                )
                database?.getAppDao()?.insertBlockInfo(blockModel)
            } catch (e: IllegalArgumentException) {
                Toast.makeText(context, "Ошибка, загрузите файл формата .tflite", Toast.LENGTH_LONG).show()
            }
            inputStream?.close()
            Toast.makeText(context, "Модель успешно сохранена", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun showRecoveryCodeForm() {
        val blockList = database?.getAppDao()?.getBlockInfo()
        val input = EditText(requireContext())
        input.hint = "Введите резервный код"
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Резервный код")
        builder.setView(input)
        builder.setPositiveButton("Подтвердить") { dialog, which ->
            val code = input.text.toString()
            if (code == "") { return@setPositiveButton }
            if (blockList != null) {
                try {
                    if (blockList[0].blockedFlag == 0) {
                        Toast.makeText(context, "Приложение не заблокировано", Toast.LENGTH_LONG).show()
                    } else {
                        if (blockList[0].attemptCount == 3) {
                            Toast.makeText(context, "Исчерпан лимит попыток", Toast.LENGTH_LONG).show()
                        } else {
                            val decryptedCode = decrypt(blockList[0].recoveryCode, "o4vjw9aqmck2kved")
                            if (code == decryptedCode) {
                                blockList[0].blockedFlag = 0
                                blockList[0].attemptCount = 0
                                database?.getAppDao()?.insertBlockInfo(blockList[0])
                                Toast.makeText(context, "Доступ открыт!", Toast.LENGTH_LONG).show()
                            } else {
                                if (blockList[0].blockedFlag == 1) {
                                    blockList[0].attemptCount += 1
                                    database?.getAppDao()?.insertBlockInfo(blockList[0])
                                }
                                Toast.makeText(context, "Неверный код!", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                } catch (e: IndexOutOfBoundsException) {
                    Toast.makeText(context, "Приложение не заблокировано", Toast.LENGTH_LONG).show()
                }
            }
            dialog.dismiss()
        }
        builder.setNegativeButton("Отмена") { dialog, which ->
            dialog.cancel()
        }
        builder.show()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun generateRecoveryCode() {
        val blockList = database?.getAppDao()?.getBlockInfo()
        if (blockList != null) {
            try {
                if (blockList[0].blockedFlag == 1) {
                    Toast.makeText(context, "Отказ в доступе", Toast.LENGTH_LONG).show()
                } else {
                    val recoveryCode = generateRandomCode() + "-" + generateRandomCode() + "-" +
                            generateRandomCode() + "-" + generateRandomCode() + "-" +
                            generateRandomCode() + "-" + generateRandomCode()
                    val builder = AlertDialog.Builder(requireContext()).apply {
                        setTitle("Код восстановления")
                        setMessage(recoveryCode)
                        setPositiveButton("OK") { dialog, which ->
                            val encryptedRecoveryCode = encrypt(recoveryCode, "o4vjw9aqmck2kved")
                            blockList[0].recoveryCode = encryptedRecoveryCode
                            database?.getAppDao()?.insertBlockInfo(blockList[0])
                            dialog.dismiss()
                        }
                    }
                    builder.show()
                }
            } catch (e: IndexOutOfBoundsException) {
                Toast.makeText(context, "Ошибка, загрузите модель", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun generateRandomCode(): String {
        val codeLength = 5
        val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val random = SecureRandom()
        return (1..codeLength)
            .map { alphabet[random.nextInt(alphabet.length)] }
            .joinToString("")
    }

    private fun resetAppList() {
        val blockList = database?.getAppDao()?.getBlockInfo()
        if (blockList != null) {
            try {
                if (blockList[0].blockedFlag == 1) {
                    Toast.makeText(context, "Отказ в доступе", Toast.LENGTH_LONG).show()
                } else {
                    database?.getAppDao()?.deleteAppList()
                    Toast.makeText(context, "Список приложений сброшен", Toast.LENGTH_LONG).show()
                }
            } catch (e: IndexOutOfBoundsException) {
                database?.getAppDao()?.deleteAppList()
                Toast.makeText(context, "Список приложений сброшен", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun drawAndAnalyze(mpResult: HandLandmarkerResult, input: MPImage) {
        if (wantToUnlock == 1) {
            fragmentMainBinding.gestureAnalyzer.setResults(
                listOf(mpResult).first(),
                input.height,
                input.width
            )
        }
        fragmentMainBinding.gestureAnalyzer.invalidate()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun encrypt(text: String, key: String): String {
        val ivBytes = ByteArray(16)
        SecureRandom().nextBytes(ivBytes)
        val ivSpec = IvParameterSpec(ivBytes)
        val secretKeySpec = SecretKeySpec(key.toByteArray(), "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivSpec)
        val encryptedBytes = cipher.doFinal(text.toByteArray())
        val encryptedBase64 = Base64.getEncoder().encodeToString(encryptedBytes)
        val ivBase64 = Base64.getEncoder().encodeToString(ivBytes)
        return "$ivBase64:$encryptedBase64"
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun decrypt(text: String, key: String): String {
        val parts = text.split(":")
        val ivBytes = Base64.getDecoder().decode(parts[0])
        val encryptedBytes = Base64.getDecoder().decode(parts[1])
        val ivSpec = IvParameterSpec(ivBytes)
        val secretKeySpec = SecretKeySpec(key.toByteArray(), "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes)
    }
}