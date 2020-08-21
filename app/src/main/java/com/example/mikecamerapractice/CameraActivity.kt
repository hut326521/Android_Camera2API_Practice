package com.example.mikecamerapractice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.icu.text.SimpleDateFormat
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.*
import android.os.VibrationEffect.*
import android.provider.MediaStore
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.widget.Gallery
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.preference.PreferenceManager
import kotlinx.android.synthetic.main.activity_camera.*
import java.io.FileDescriptor
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.ArrayList


class CameraActivity : Activity() {

    /** cameraDevice session變數*/
    private var myCameraDevice: CameraDevice? = null
    var mySession: CameraCaptureSession? = null
    private val cameraThread = HandlerThread("cameraThread")
    private var cameraHandler: Handler? = null
    private val cameraThreadForSaving = HandlerThread("cameraThreadForSaving")
    private var cameraHandlerForSaving: Handler? = null
    private val cameraThreadForYUVtoNV21 = HandlerThread("cameraThreadForYUVtoNV21")
    private var cameraHandlerForYUVtoNV21: Handler? = null
    var finalPreviewSizeHeight = 0
    var finalPreviewSizeWidth = 0
    private val myThreadLock = ReentrantLock()
    private var cameraDeviceOpened = false
    private var theAngleYouNeedToRotate = 0F
    val cameraDeviceOpenedCondition: Condition = myThreadLock.newCondition()
    private val isTakingPicCondition: Condition = myThreadLock.newCondition()
    private val myNowSettingContent = CameraNowSettingContent()
    private lateinit var myImageReader: ImageReader
    private var isTakingPic = false
    private var countMaxBurst = 0
    private var myMaxImageCountInImageReader = 20
    private var myImageWaitingArrayList: Queue<CustomImageObject> = LinkedList<CustomImageObject>()
    private var countNowBuffer = 0
    private var isTakingBurstPic = false

    /** Extended Function */
    private fun Date.getNowDateTime(): String {
        val sdf = SimpleDateFormat("yyyyMMddHHmmssSSS", Locale.TAIWAN)
        return sdf.format(this)
    }

    fun Context.getVibrator(): Vibrator {
        return getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }


    /** Thread Control Function*/
    private fun runCameraHandlerThread() {
        if (!cameraThread.isAlive) {
            cameraThread.start()
        }
        cameraHandler = Handler(cameraThread.looper)
        if (!cameraThreadForSaving.isAlive) {
            cameraThreadForSaving.start()
        }
        cameraHandlerForSaving = Handler(cameraThreadForSaving.looper)
        if (!cameraThreadForYUVtoNV21.isAlive) {
            cameraThreadForYUVtoNV21.start()
        }
        cameraHandlerForYUVtoNV21 = Handler(cameraThreadForYUVtoNV21.looper)

    }

    private fun removeCameraHandlerMessages() {
        Log.e("CameraTestForThread", "RemoveHandlerMessages")
        cameraHandler?.removeCallbacksAndMessages(null)
        cameraHandlerForSaving?.removeCallbacksAndMessages(null)
        cameraHandlerForYUVtoNV21?.removeCallbacksAndMessages(null)
    }

    /** Activity Life Cycle */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.e("CameraTest", "BuildSDKVer:${Build.VERSION.SDK_INT}")
        setContentView(R.layout.activity_camera)


        /** Request Permission */
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            CameraPermissionHelper.requestCameraPermission(this)
            return
        }
        /** For SurfaceView Callback Define */
        CameraPreviewSurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) {
                //p2 height p3 width p1 4p2??
                Log.d("CameraTestLifeCycle", "SurfaceView Changed")
                if (finalPreviewSizeWidth == p2 && finalPreviewSizeHeight == p3) {
                    myThreadLock.lock()
                    if (myCameraDevice == null) {
                        cameraDeviceOpenedCondition.await()
                    }
                    Log.e("CameraTestForSaving", "p2:${p2},p3:${p3}")
                    try {
                        myImageReader = ImageReader.newInstance(
                            p2,
                            p3,
                            ImageFormat.YUV_420_888,
                            myMaxImageCountInImageReader
                        )
                    } catch (e: IllegalArgumentException) {
                        Log.e("CameraTestForSaving", "NewImageError${e}")
                    }
                    myImageReader.setOnImageAvailableListener(
                        { toProcessAndSavingPic(it) },
                        cameraHandlerForSaving
                    )
                    startPreview()
                    myThreadLock.unlock()
                }
            }

            override fun surfaceDestroyed(p0: SurfaceHolder) {
                Log.d("CameraTestLifeCycle", "SurfaceView Destroyed")
                cameraHandler?.post {
                    myThreadLock.lock()
                    cameraDeviceOpenedCondition.signal()
                    stopCameraSession()
                }

            }

            override fun surfaceCreated(p0: SurfaceHolder) {
                Log.d("CameraTestLifeCycle", "SurfaceView Created")
                runCameraHandlerThread()
                theAngleYouNeedToRotate = getRotationAngle()
                startCameraDevice()
                changePreviewSize()
            }

        })
        /** 點選按鈕切換至Setting頁面 */
        toPreferenceButton.setOnClickListener {
            gotoPrefActivity()

        }
        SwitchCamButton.setOnClickListener {
            switchCam()
            recreate()
        }
        CameraImageButton.setOnClickListener {
            takePicture()
        }

        CameraImageButton.setOnLongClickListener(takePictureContinueListener)
        CameraImageButton.setOnTouchListener(releaseButtonListener)

        toGalleryImageButton.setOnClickListener {
            toGallery()
        }

        //toGalleryImageButton.setImageResource()


    }

    /** Function of Switch Camera Button*/
    private fun switchCam() {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val nowCamID =
            myNowSettingContent.nowCameraID
        var newCamID = cameraManager.cameraIdList[0]
        if ((nowCamID.toInt() + 1).toString() in cameraManager.cameraIdList) {
            newCamID = (nowCamID.toInt() + 1).toString()
        }
        Log.w("CameraTest", newCamID)
        PreferenceManager.getDefaultSharedPreferences(this).edit().apply()
        {
            putString("cameraID", newCamID)
            apply()
        }
    }

    /** 拍照時前後鏡頭需要旋轉的角度 */
    private fun getRotationAngle(): Float {
        val displayRotation: Int = windowManager.defaultDisplay.rotation
        val whichCam: Boolean = myNowSettingContent.nowCameraID == "0"
        when (displayRotation) {
            0 -> {
                return if (whichCam) 90F else 270F
            }
            1 -> {
                return if (whichCam) 0F else 0F
            }
            2 -> {
                return if (whichCam) 270F else 90F
            }
            3 -> {
                return if (whichCam) 180F else 180F
            }
            else -> {
                return 90F
            }
        }
    }

    /** Activity Life Cycle */
    override fun onStart() {
        myNowSettingContent.getNowSettingGo(this)
        super.onStart()
        Log.d("CameraTestLifeCycle", "CameraActivity onStart")
    }

    override fun onResume() {
        super.onResume()
        Log.d("CameraTestLifeCycle", "CameraActivity onResume")
    }

    override fun onRestart() {
        super.onRestart()
        myThreadLock.unlock()
        Log.d("CameraTestLifeCycle", "CameraActivity onRestart")
    }

    override fun onPause() {
        super.onPause()
        myThreadLock.lock()
        if (isTakingPic) {
            isTakingPicCondition.await()
        }
        myThreadLock.unlock()
        Log.d("CameraTestLifeCycle", "CameraActivity onPause")
    }

    override fun onStop() {
        myThreadLock.lock()
        if (cameraDeviceOpened) {
            cameraDeviceOpenedCondition.await()
        }
        removeCameraHandlerMessages()
        super.onStop()
        Log.d("CameraTestLifeCycle", "CameraActivity onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        myThreadLock.unlock()
        cameraThread.quit()
        cameraThreadForSaving.quit()
        cameraThreadForYUVtoNV21.quit()
        Log.d("CameraTestLifeCycle", "CameraActivity onDestroy")
    }


    /** 開啟相機Device */
    private fun startCameraDevice() {

        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (cameraManager.cameraIdList.isEmpty()) {
            // no cameras
            return
        }
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        Log.e("CameraTestForThread", "OpenCamera")
        cameraManager.openCamera(
            myNowSettingContent.nowCameraID,
            {
                cameraHandler?.post(it)
            }
            , object : CameraDevice.StateCallback() {
                override fun onDisconnected(p0: CameraDevice) {
                    Log.w("CameraTestFlow", "Camera.openCamera Disconnecting")
                }

                override fun onError(p0: CameraDevice, p1: Int) {
                    Log.w("CameraTestFlow", "Camera.openCamera onError")
                    onDisconnected(p0)
                }

                override fun onOpened(cameraDevice: CameraDevice) {
                    Log.w("CameraTestFlow", "Camera.openCamera onOpened")
                    myThreadLock.lock()
                    myCameraDevice = cameraDevice
                    cameraDeviceOpened = true
                    cameraDeviceOpenedCondition.signal()
                    myThreadLock.unlock()

                }

                override fun onClosed(camera: CameraDevice) {
                    Log.e("CameraTestForThread", "Device Closed")
                    super.onClosed(camera)
                    cameraDeviceOpened = false
                    myThreadLock.unlock()
                }
            }
        )
    }

    /** 關閉相機Device&session */
    private fun stopCameraSession() {
        mySession?.close()
        myCameraDevice?.close()
        mySession = null
        myCameraDevice = null
    }

    /** 開啟相機Preview */
    private fun startPreview() {
        Log.w("CameraTestFlow", "Start Preview")
        val previewSurface = CameraPreviewSurfaceView.holder.surface
        val recordingSurface = myImageReader.surface
        val stateCallback =
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.d("CameraTestInStartPreview", "ConfigureFailed")
                }

                override fun onConfigured(session: CameraCaptureSession) {
                    mySession = session
                    myBuilderFactory(1)?.build()?.let {
                        session.setRepeatingRequest(
                            it,
                            object : CameraCaptureSession.CaptureCallback() {},
                            null
                        )
                    }
                }

                override fun onClosed(session: CameraCaptureSession) {
                    Log.e("CameraTestForThread", "Session Closed")
                    super.onClosed(session)

                }

                override fun onActive(session: CameraCaptureSession) {
                    Log.w("CameraTestFlow", "Start Session")
                    super.onActive(session)
                }
            }
        val previewOC = OutputConfiguration(previewSurface)
        val recordOC = OutputConfiguration(recordingSurface)
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            mutableListOf(previewOC, recordOC),
            Executor
            {
                cameraHandler?.post(it)
            },
            stateCallback
        )
        myCameraDevice?.createCaptureSession(sessionConfig)


    }

    /** 抓取目前設定之Preview Size並設定SurfaceView*/
    fun changePreviewSize() {
        /** 坑:session長寬要照sensor oriention轉一次 螢幕旋轉時不用跟著轉 但surfaceview不用照Sensor Oriention轉 每次螢幕旋轉時再轉就好...*/
        Log.w("CameraTestFlow", "Start Get and Change PreviewSize")
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (cameraManager.cameraIdList.isEmpty()) {
            // no cameras
            return
        }
        val nowPreviewSizeWidth = myNowSettingContent.width
        val nowPreviewSizeHeight = myNowSettingContent.height


        /** Sensor Rotate */
        val swappedSensors = when (howmuchSensorSwapped()) {
            90, 270 -> true
            else -> false
        }
        val rotatedPreviewWidth =
            if (swappedSensors) nowPreviewSizeHeight else nowPreviewSizeWidth
        val rotatedPreviewHeight =
            if (swappedSensors) nowPreviewSizeWidth else nowPreviewSizeHeight

        /** Set Size to SurfaceView */
        CameraPreviewSurfaceView.holder.setFixedSize(
            rotatedPreviewWidth,
            rotatedPreviewHeight
        )
        finalPreviewSizeWidth = rotatedPreviewWidth
        finalPreviewSizeHeight = rotatedPreviewHeight

        /** Swapped Checked */
        val swappedDimensions = when (windowManager.defaultDisplay.rotation) {
            0, 2 -> false
            else -> true
        }
        val rotatedTwicePreviewWidth =
            if (swappedDimensions) nowPreviewSizeHeight else nowPreviewSizeWidth
        val rotatedTwicePreviewHeight =
            if (swappedDimensions) nowPreviewSizeWidth else nowPreviewSizeHeight
        /////////////

        /** 過大或過小Preview處理 並設為layout大小 */
        //Get Screen Size
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels

        var setToScreenWidth = rotatedTwicePreviewWidth
        var setToScreenHeight = rotatedTwicePreviewHeight
        //Calculate True Size(Preview > Window)
        if (rotatedTwicePreviewWidth > screenWidth || rotatedTwicePreviewHeight > screenHeight) {
            if (rotatedTwicePreviewWidth - screenWidth > rotatedTwicePreviewHeight - screenHeight) {
                setToScreenHeight =
                    (rotatedTwicePreviewHeight.toDouble() * (screenWidth.toDouble() / rotatedTwicePreviewWidth.toDouble())).toInt()
                setToScreenWidth = screenWidth

            } else {
                setToScreenWidth =
                    (rotatedTwicePreviewWidth.toDouble() * (screenHeight.toDouble() / rotatedTwicePreviewHeight.toDouble())).toInt()
                setToScreenHeight = screenHeight
            }
        }
        //Calculate True Size(Preview < Window)
        if (rotatedTwicePreviewWidth < screenWidth && rotatedTwicePreviewHeight < screenHeight) {
            if (rotatedTwicePreviewWidth - screenWidth > rotatedTwicePreviewHeight - screenHeight) {
                setToScreenHeight =
                    (rotatedTwicePreviewHeight.toDouble() * (screenWidth.toDouble() / rotatedTwicePreviewWidth.toDouble())).toInt()
                setToScreenWidth = screenWidth

            } else {
                setToScreenWidth =
                    (rotatedTwicePreviewWidth.toDouble() * (screenHeight.toDouble() / rotatedTwicePreviewHeight.toDouble())).toInt()
                setToScreenHeight = screenHeight
            }
        }
        CameraPreviewSurfaceView.layoutParams.width = setToScreenWidth
        CameraPreviewSurfaceView.layoutParams.height = setToScreenHeight
        Log.d("CameraTestSetLayout", "$setToScreenWidth*$setToScreenHeight")


        /** Show Info On Screen*/
        CamDetailView.text = getString(
            R.string.setPreviewLog,
            myNowSettingContent.nowCameraID,
            nowPreviewSizeWidth,
            nowPreviewSizeHeight
        )
    }

    /** Check Sensor Swapped 確認Sensor方向*/
    private fun howmuchSensorSwapped(): Int {
        var swappedSensor = 90
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (cameraManager.cameraIdList.isEmpty()) {
            return swappedSensor
        }
        val cameraCharacteristics =
            cameraManager.getCameraCharacteristics(myCameraDevice?.id ?: "0")
        swappedSensor = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        return swappedSensor
    }

    /**　Take Pic and trigger onImageAvailaableListener */
    private fun takePicture() {
        if (myImageWaitingArrayList.isNotEmpty() or isTakingPic) {
            Toast.makeText(this, "Please Wait For Process Image", Toast.LENGTH_SHORT).show()
            return
        }
        myThreadLock.lock()
        countNowBuffer = 0
        countMaxBurst = 0
        isTakingPic = true
        endBurstSignal = false
        getVibrator().vibrate(createOneShot(50, 255))
        myBuilderFactory(0)?.build()?.let {
            mySession?.capture(
                it,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        super.onCaptureCompleted(session, request, result)
                        Log.e("CameraTestTakingPic", "Capture Complete")

                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        super.onCaptureFailed(session, request, failure)
                        Log.e(
                            "CameraTestTakingPic",
                            "Capture Failure, FailReason: ${failure.reason}"
                        )
                    }

                    override fun onCaptureStarted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        timestamp: Long,
                        frameNumber: Long
                    ) {
                        super.onCaptureStarted(session, request, timestamp, frameNumber)
                        Log.e("CameraTestTakingPic", "Capture Started")
                    }
                },
                cameraHandler
            )
        }
        myThreadLock.unlock()
    }

    private fun takePictureCon() {
        if (myImageWaitingArrayList.isNotEmpty() or isTakingPic) {
            Toast.makeText(this, "Please Wait For Process Image", Toast.LENGTH_SHORT).show()
            return
        }
        isTakingBurstPic = true
        myThreadLock.lock()
        countNowBuffer = 0
        countMaxBurst = 0
        isTakingPic = true
        endBurstSignal = false
        myBuilderFactory(2)?.build()?.let {
            mySession?.setRepeatingBurst(
                mutableListOf(it),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        super.onCaptureCompleted(session, request, result)
                        Log.e("CameraTestTakingPic", "Capture Complete")
                        Log.e("CameraTestTakingPic", "Frame Number:${result.frameNumber}")
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        super.onCaptureFailed(session, request, failure)
                        Log.e(
                            "CameraTestTakingPic",
                            "Capture Failure, FailReason: ${failure.reason}"
                        )
                    }

                    override fun onCaptureStarted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        timestamp: Long,
                        frameNumber: Long
                    ) {
                        super.onCaptureStarted(session, request, timestamp, frameNumber)
                        if (countNowBuffer <= myMaxImageCountInImageReader - 2) {
                            getVibrator().vibrate(createOneShot(50, 255))
                        }
                        Log.e("CameraTestTakingPic", "Capture Started")
                    }
                },
                cameraHandler
            )
        }
        myThreadLock.unlock()
    }

    /** Function of Pref Button */
    private fun gotoPrefActivity() {
        val toPrefIntent = Intent(this, PreferenceActivity::class.java)
        toPrefIntent.putExtra("cameraAvailableSizeBundle", getCameraAvailableSizeAndID())
        startActivity(toPrefIntent)
    }

    /** 取得相機可使用之Resolution 包成Bundle */
    private fun getCameraAvailableSizeAndID(): Bundle {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (cameraManager.cameraIdList.isEmpty()) {
            return Bundle()
        }
        val cameraCharacteristics =
            cameraManager.getCameraCharacteristics(myCameraDevice?.id ?: "0")
        val streamConfigurationMap =
            cameraCharacteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        val yuvSize = streamConfigurationMap?.getOutputSizes(SurfaceHolder::class.java)
        val heightArrayList: ArrayList<Int> = ArrayList()
        val widthArrayList: ArrayList<Int> = ArrayList()
        val cameraSizeBundle = Bundle()
        if (yuvSize != null) {
            for (everySize in yuvSize) {
                heightArrayList.add(everySize.height)
                widthArrayList.add(everySize.width)
            }
        }
        val iDList = cameraManager.cameraIdList
        val iDArrayList: ArrayList<String> = ArrayList()
        for (everySize in iDList) {
            iDArrayList.add(everySize)
        }
        cameraManager.cameraIdList
        cameraSizeBundle.putSerializable("cameraAvailableSizeBundleHeight", heightArrayList)
        cameraSizeBundle.putSerializable("cameraAvailableSizeBundleWidth", widthArrayList)
        cameraSizeBundle.putSerializable("cameraAvailableIdBundle", iDArrayList)

        return cameraSizeBundle

    }


    /** Choose to create Preview or Capture Builder, also easy to change the setting*/
    /** Image Save Listener, set to onImageAvailableListener*/
    private var endBurstSignal = false
    private fun toProcessAndSavingPic(newImage: ImageReader) {
        val myImage: Image = newImage.acquireNextImage()
        Log.e("CameraTestForSaving", "DEBUG:countNowBuffer:${countNowBuffer}")
        val width = myImage.width
        val height = myImage.height
        val rowStrideY = myImage.planes[0].rowStride
        val rowStrideUV = myImage.planes[2].rowStride
        val yBuffer: ByteBuffer = myImage.planes[0].buffer
        val uBuffer: ByteBuffer = myImage.planes[1].buffer
        val vBuffer: ByteBuffer = myImage.planes[2].buffer
        val myYBArray = ByteArray(yBuffer.capacity())
        val myUBArray = ByteArray(uBuffer.capacity())
        val myVBArray = ByteArray(vBuffer.capacity())
        val pixelStride = myImage.planes[2].pixelStride
        yBuffer.get(myYBArray, 0, yBuffer.capacity())
        uBuffer.get(myUBArray, 0, uBuffer.capacity())
        vBuffer.get(myVBArray, 0, vBuffer.capacity())
        val myNewCustomImageObject = CustomImageObject(
            myYBArray,
            myUBArray,
            myVBArray,
            width,
            height,
            rowStrideY,
            rowStrideUV,
            pixelStride
        )
        myImage.close()
        when {
            myImageWaitingArrayList.size == myMaxImageCountInImageReader - 2 -> {
                if (!endBurstSignal) {
                    //第一次滿
                    Toast.makeText(this, "ImageBuffer is Full", Toast.LENGTH_SHORT).show()
                    mySession?.stopRepeating()
                    this.mainExecutor.execute { startPreview() }
                    endBurstSignal = true
                }
            }
            myImageWaitingArrayList.size > myMaxImageCountInImageReader - 2 -> {
                //已滿 消化中
                endBurstSignal = true
            }
            else -> {
                if (!endBurstSignal) {
                    //未滿 加入
                    myImageWaitingArrayList.add(myNewCustomImageObject)
                    cameraHandlerForYUVtoNV21?.post { reProcess() }
                }
            }
        }
    }

    private fun reProcess() {
        Log.e("CameraTestForCrash", "ReProcessDEBUG")
        val myImage = myImageWaitingArrayList.remove()
        val rect = Rect(0, 0, myNowSettingContent.height, myNowSettingContent.width)
        val newNV21Bytes: ByteArray = funYUVToNV21(myImage)
        //val newNV21BytesAfterRotate = rotateCaptureImage(newNV21Bytes,90F,myImage.width,myImage.height)
        //由於Sensor Orientation為90or270 高寬反過來 讀取NV21格式才會正確
        val myYUVImage = YuvImage(
            newNV21Bytes,
            ImageFormat.NV21,
            myNowSettingContent.height,
            myNowSettingContent.width,
            null
        )

        /** Content Resolver Test*/
        val myResolver = this.baseContext.contentResolver
        val thisPicName = "TestImg${Date().getNowDateTime()}.jpg"
        val myContentValues = ContentValues()
        myContentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, thisPicName)
        myContentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpg")
        myContentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")

        val myStream: OutputStream?
        val insertUri: Uri?
        val myUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        insertUri = myResolver.insert(myUri, myContentValues)
        myStream = insertUri?.let { myResolver.openOutputStream(it) }
        myYUVImage.compressToJpeg(rect, 100, myStream)

        this.mainExecutor.execute { toGalleryImageButton.setImageURI(insertUri) }

        val parcelFileDescriptor = insertUri?.let { myResolver.openFileDescriptor(it, "rw") }
        val myFileDescriptor = parcelFileDescriptor?.fileDescriptor
        if (myFileDescriptor != null) {
            writeFileMetaData(myFileDescriptor)
        }

        if (myStream != null) {
            myStream.flush()
            myStream.close()
        }
        parcelFileDescriptor?.close()
        if (myImageWaitingArrayList.isEmpty()) {
            isTakingPic = false
            myImageWaitingArrayList.clear()
            isTakingBurstPic = false
            myThreadLock.lock()
            isTakingPicCondition.signal()
            myThreadLock.unlock()


        }
    }

    /** 擷取YUV圖片後轉換成NV21格式(清理垃圾資訊並將VU排列一起) */
    private fun funYUVToNV21(image: CustomImageObject): ByteArray {
        Log.w("CameraTestForSaving", "StartYUVtoNV21")
        val width = image.mWidth
        val height = image.mHeight
        val ySize: Int = width * height
        val uvSize: Int = width * height / 4
        val newNV21Image = ByteArray(ySize + (uvSize * 2))
        val rowStrideY = image.mYRowstride
        val rowStrideUV = image.mUVRowstride
        val pixelStride = image.mUVPixelStride
        //rowStride: 每行資料抓取道的寬度(含垃圾) width&height: 正確資料的大小(無垃圾) pixelStride: 有效資料的間隔 若為1則每個pixel皆為有效資料 若為2則第1 3 5...為有效資料
        //先處理Y Planes 由於Y Planes的pixelStride一定是1 因此只需判斷rwoStride為多少
        Log.w("CameraTestTimingYUVTONV21", "DEBUG1")
        if (rowStrideY == width) {

            for (i in 0 until ySize) {
                newNV21Image[i] = image.mYArray[i] //rowStride與寬相等 則整個yBuffer內無垃圾 直接排進newNV21Image即可
            }

        } else {
            var posYBuffer = 0 //需存資料之位置
            for (i in 0 until ySize step width) {
                for (j in 0 until width) {
                    newNV21Image[j + i] = image.mYArray[j + posYBuffer]
                }
                posYBuffer += rowStrideY
            }
        }
        Log.w("CameraTestTimingYUVTONV21", "DEBUG2")

        //開始擷取u與v planes
        var posNV21Array = ySize //nv21Image之Index
        //已經排成NV21 若滿足pixelStride = 2且rowStride = width 則v與u已排好為vuvuvu... or uvuvuvuvuv... 則擷取完後return
        if (pixelStride == 2 && rowStrideUV == width && image.mUArray[0] == image.mVArray[1]) {

            for (i in ySize until ySize + (uvSize * 2) - 1) {
                newNV21Image[i] =
                    image.mVArray[i - ySize] //rowStride與寬相等 則整個yBuffer內無垃圾 直接排進newNV21Image即可
            }
            newNV21Image[ySize + (uvSize * 2) - 1] = image.mUArray[uvSize * 2 - 2]
            Log.w("CameraTestTimingYUVTONV21", "DEBUG3")

            return newNV21Image
        }

        //未排成NV21 則分別取v與u之有效值分入NV21Image index:pos
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val vuPos = col * pixelStride + row * rowStrideUV
                newNV21Image[posNV21Array++] = image.mVArray[vuPos]
                newNV21Image[posNV21Array++] = image.mUArray[vuPos]
            }
        }
        Log.w("CameraTestTimingYUVTONV21", "DEBUG4")
        return newNV21Image
    }

    /** 寫入儲存之圖片的metadata 目前僅有增加model屬性 */
    private fun writeFileMetaData(photoInputStream: FileDescriptor) {
        try {

            val myPhotoExif = ExifInterface(photoInputStream)
            myPhotoExif.setAttribute(ExifInterface.TAG_MODEL, "Mike")
            /** 旋轉圖片 */
            Log.w("CameraTest", "rotate:${windowManager.defaultDisplay.rotation}")
            when (howmuchSensorSwapped()) {
                //0 -> myPhotoExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                90 -> {
                    when (windowManager.defaultDisplay.rotation) {
                        0 -> myPhotoExif.setAttribute(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_ROTATE_90.toString()
                        )
                        1 -> myPhotoExif.setAttribute(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL.toString()
                        )
                        2 -> myPhotoExif.setAttribute(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_ROTATE_270.toString()
                        )
                        3 -> myPhotoExif.setAttribute(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_ROTATE_180.toString()
                        )
                    }

                }
                //180 -> myPhotoExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_180.toString())
                270 -> {
                    when (windowManager.defaultDisplay.rotation) {
                        0 -> myPhotoExif.setAttribute(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_ROTATE_270.toString()
                        )
                        1 -> myPhotoExif.setAttribute(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL.toString()
                        )
                        2 -> myPhotoExif.setAttribute(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_ROTATE_90.toString()
                        )
                        3 -> myPhotoExif.setAttribute(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_ROTATE_180.toString()
                        )
                    }
                }
            }

            myPhotoExif.saveAttributes()
        } catch (e: IOException) {
            Log.e("CameraTestForMetadata", "IOException:${e}")
        } catch (e: NullPointerException) {
            Log.e("CameraTestForMetadata", "NullPointerException:${e}")
        }
    }


    private var takePictureContinueListener = View.OnLongClickListener {
        takePictureCon()
        return@OnLongClickListener true
    }

    @SuppressLint("ClickableViewAccessibility")
    private var releaseButtonListener = View.OnTouchListener { _: View, motionEvent: MotionEvent ->
        when (motionEvent.action) {
            MotionEvent.ACTION_UP -> {
                if (isTakingBurstPic) {
                    startPreview()
                    isTakingBurstPic = false
                }

            }
            MotionEvent.ACTION_DOWN -> {
                //CameraImageButton.performClick()
            }
        }
        return@OnTouchListener false
    }


    private fun myBuilderFactory(mode: Int): CaptureRequest.Builder? {
        val myCameraDeviceHere = myCameraDevice
        if (myCameraDeviceHere != null) {
            when (mode) {
                /** take picture builder */
                0 -> {
                    val captureRequestBuilder: CaptureRequest.Builder =
                        myCameraDeviceHere.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    captureRequestBuilder.addTarget(CameraPreviewSurfaceView.holder.surface)
                    captureRequestBuilder.addTarget(myImageReader.surface)
                    //自動對焦
                    captureRequestBuilder.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )
                    //自動曝光
                    captureRequestBuilder.set(
                        CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                    )
                    // 手機方向

                    return captureRequestBuilder
                }
                /** preview builder */
                1 -> {
                    return myCameraDeviceHere.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        .apply {
                            addTarget(CameraPreviewSurfaceView.holder.surface)
                        }
                }
                /** Burst capture builder */
                2 -> {
                    val captureRequestBuilder =
                        myCameraDeviceHere.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    captureRequestBuilder.addTarget(CameraPreviewSurfaceView.holder.surface)
                    captureRequestBuilder.addTarget(myImageReader.surface)

                    return captureRequestBuilder
                }
            }
        }
        return null
    }

    private fun toGallery() {
        val intent = Intent()
        intent.action = Intent.ACTION_VIEW
        intent.type = "image/*"
        intent.flags = (Intent.FLAG_ACTIVITY_NEW_TASK)
        this.startActivity(intent)
    }

    /** 讓user選完Permission allow後直接自動重啟Camera，不須手動重開 */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(
                this,
                "Camera permission is needed to run this application",
                Toast.LENGTH_LONG
            )
                .show()
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this)
            }
            finish()
        }
        recreate()
    }

    /** Requesting Permission Helper*/
    //After Android 6.0, Android App must request permission from user
    //Helper to ask camera permission
    object CameraPermissionHelper {
        private const val allCamera_PERMISSION_CODE = 0
        private const val CAMERA_PERMISSION = Manifest.permission.CAMERA
        private const val writeStorage_PERMISSION = Manifest.permission.WRITE_EXTERNAL_STORAGE
        private const val readStorage_PERMISSION = Manifest.permission.READ_EXTERNAL_STORAGE

        //Check to see we have the necessary permissions for this app.
        fun hasCameraPermission(activity: Activity): Boolean {
            val cameraPermission = ContextCompat.checkSelfPermission(
                activity,
                CAMERA_PERMISSION
            ) == PackageManager.PERMISSION_GRANTED
            val writeStoragePermission = ContextCompat.checkSelfPermission(
                activity,
                writeStorage_PERMISSION
            ) == PackageManager.PERMISSION_GRANTED
            val readStoragePermission = ContextCompat.checkSelfPermission(
                activity,
                readStorage_PERMISSION
            ) == PackageManager.PERMISSION_GRANTED

            return (cameraPermission or writeStoragePermission or readStoragePermission)
        }

        //Check to see we have the necessary permissions for this app, and ask for them if we don't.
        fun requestCameraPermission(activity: Activity) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(CAMERA_PERMISSION, writeStorage_PERMISSION, readStorage_PERMISSION),
                allCamera_PERMISSION_CODE
            )
        }

        //Check to see if we need to show the rationale for this permission
        fun shouldShowRequestPermissionRationale(activity: Activity): Boolean {
            return ActivityCompat.shouldShowRequestPermissionRationale(activity, CAMERA_PERMISSION)
        }

        //Launch Application Setting to grant permission
        fun launchPermissionSettings(activity: Activity) {
            val intent = Intent()
            intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            intent.data = Uri.fromParts("package", activity.packageName, null)
            activity.startActivity(intent)
        }
    }
}