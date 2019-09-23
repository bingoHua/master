package com.example.camera2double;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresPermission;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Toast;

import com.huawei.emui.himedia.camera.HwCamera;
import com.huawei.emui.himedia.camera.HwCameraCaptureSession;
import com.huawei.emui.himedia.camera.HwCameraDevice;
import com.huawei.emui.himedia.camera.HwCameraEngineDieRecipient;
import com.huawei.emui.himedia.camera.HwCameraInitSuccessCallback;
import com.huawei.emui.himedia.camera.HwCameraManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CameraInstance {
    private CameraDevice mCameraDevice;
    private HwCameraDevice mhwCameraDevice;
    private HwCameraDevice mHWCameraDevice;
    private CameraDevice.StateCallback mDeviceStateCallback;
    private AutoFitTextureView mTextureView;
    private CameraCaptureSession mCameraCaptureSession;
    private HwCameraCaptureSession mHwCameraCaptureSession;
    private CameraManager mManager;
    private Activity mActivity;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);
    private CameraCharacteristics mCharacteristics;
    private String mCameraId;
    private CaptureRequest.Builder mPreviewBuilder;
    private Surface mSurface;
    private MediaRecorder mMediaRecorder;
    private String mNextVideoAbsolutePath;
    private Integer mSensorOrientation;
    private static final int SENSOR_ORIENTATION_DEFAULT_DEGREES = 90;
    private static final int SENSOR_ORIENTATION_INVERSE_DEGREES = 270;
    private static final SparseIntArray DEFAULT_ORIENTATIONS = new SparseIntArray();
    private static final SparseIntArray INVERSE_ORIENTATIONS = new SparseIntArray();

    static {
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_0, 90);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_90, 0);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_180, 270);
        DEFAULT_ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    static {
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_0, 270);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_90, 180);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_180, 90);
        INVERSE_ORIENTATIONS.append(Surface.ROTATION_270, 0);
    }
    /**
     * The {@link android.util.Size} of camera preview.
     */
    private Size mPreviewSize;

    /**
     * The {@link android.util.Size} of video recording.
     */
    private Size mVideoSize;

    public CameraInstance(Activity activity, AutoFitTextureView textureView, String cameraId, HwCamera camera){
        mActivity = activity;
        mManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        mTextureView = textureView;
        mCameraId = cameraId;
        hwCamera = camera;
        mDeviceStateCallback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice cameraDevice) {
                mCameraDevice = cameraDevice;
                startPreview();
            }

            @Override
            public void onDisconnected(CameraDevice cameraDevice) {
                cameraDevice.close();
                mCameraDevice = null;
            }

            @Override
            public void onError(CameraDevice cameraDevice, int i) {
                if (null != mActivity) {
                    mActivity.finish();
                }
            }
        };
        try {
            mCharacteristics = mManager.getCameraCharacteristics(cameraId);
        } catch (CameraAccessException e){

        }
    }

    private HwCameraDevice.StateCallback mStateCallback = new HwCameraDevice.StateCallback() {

        @Override
        public void onOpened(HwCameraDevice hwCameraDevice) {
            mhwCameraDevice = hwCameraDevice;
            startPreview();
            mCameraOpenCloseLock.release();
            if (null != mTextureView) {
                configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
            }
        }

        @Override
        public void onError(HwCameraDevice hwCameraDevice, int i) {

        }

        @Override
        public void onClosed(HwCameraDevice camera) {
            super.onClosed(camera);
        }

        @Override
        public void onDisconnected(HwCameraDevice hwCameraDevice) {

        }
    };

    private void createHWCaptureSession(HwCameraDevice cameraDevice) {
        try {
            cameraDevice.createCaptureSession(Collections.singletonList(mSurface), new HwCameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(HwCameraCaptureSession hwCameraCaptureSession) {
                    mHwCameraCaptureSession = hwCameraCaptureSession;
                    hwupdatePreview();
                }

                @Override
                public void onConfigureFailed(HwCameraCaptureSession hwCameraCaptureSession) {

                }
            }, mBackgroundHandler);
        }catch (CameraAccessException e) {}
    }

    private void createCaptureSession(CameraDevice cameraDevice) {
            try {
                cameraDevice.createCaptureSession(Collections.singletonList(mSurface), new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession cameraCaptureSession) {
                        mCameraCaptureSession = cameraCaptureSession;
                        updatePreview();
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {

                    }
                }, mBackgroundHandler);
            } catch (CameraAccessException e) {}
    }

    /**
     * Update the camera preview. {@link #startPreview()} needs to be called in advance.
     */
    private void updatePreview() {
        if (null == mCameraDevice) {
            return;
        }
        try {
            setUpCaptureRequestBuilder(mPreviewBuilder);
            HandlerThread thread = new HandlerThread("CameraPreview" + mCameraId);
            thread.start();
            mCameraCaptureSession.setRepeatingRequest(mPreviewBuilder.build(), new CameraCaptureSession.CaptureCallback(){
                @Override
                public void onCaptureStarted(@NonNull CameraCaptureSession session,
                                             @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                    Log.println(0, this.toString(), "test");
                }
            } , mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    void hwupdatePreview() {
        if (null == mHWCameraDevice) {
            return;
        }
        try {
            setUpCaptureRequestBuilder(mPreviewBuilder);
            HandlerThread thread = new HandlerThread("CameraPreview" + mCameraId);
            thread.start();
            mHwCameraCaptureSession.setRepeatingRequest(mPreviewBuilder.build(), new HwCameraCaptureSession.CaptureCallback(){
                @Override
                public void onCaptureStarted(@NonNull HwCameraCaptureSession session,
                                             @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                    Log.println(0, this.toString(), "test");
                }
            } , mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder) {
        //builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        if(mCameraId == "3") {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        } else {
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        }
       /* builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF);
        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);*/
    }

    @SuppressWarnings("MissingPermission")
    public void openCamera(int width, int height) {
        CameraManager manager = (CameraManager) mActivity.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            mSurface = new Surface(mTextureView.getSurfaceTexture());
            StreamConfigurationMap map = mCharacteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            mVideoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
            mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                    width, height, mVideoSize);
            mSensorOrientation = mCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            manager.openCamera(mCameraId, mDeviceStateCallback, null);
            mMediaRecorder = new MediaRecorder();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.");
        } catch (CameraAccessException e) {
            Toast.makeText(mActivity, "Cannot access the camera.", Toast.LENGTH_SHORT).show();
        }
    }

    HwCamera hwCamera;
    CameraCharacteristics characteristics;
    HwCameraManager manager;
    private String[] mCameraIdList;

    public void openHWCamera(int width, int height) {
        mSurface = new Surface(mTextureView.getSurfaceTexture());

        hwCamera.setHwCameraEngineDieCallBack(new EngineDieCallback());
        hwCamera.getHwCameraManager();
        manager =  hwCamera.getHwCameraManager();
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            String cameraId;
            int cameraIndex = -1;
            mCameraIdList = manager.getCameraIdList();

            //get backCameraId
    /*        for (int i = 0; i < mCameraIdList.length; i++) {
                characteristics = manager.getCameraCharacteristics(mCameraIdList[i]);
                if (mCurrentCameraFacing == characteristics.get(CameraCharacteristics.LENS_FACING)) {
                    Byte ret = manager.isFeatureSupported(i, HwCameraMetadata.CharacteristicKey.HUAWEI_IS_60FPS_SUPPORTED);
                    if (ret != 1) {
                        showToast("60fps not supported");
                        //getActivity().onBackPressed();
                        return;
                    }
                    cameraIndex = i;
                    break;
                }
            }*/
            cameraId = mCameraId;
            cameraIndex = Integer.parseInt(cameraId);
            characteristics = manager.getCameraCharacteristics(mCameraIdList[cameraIndex]);
            assert (characteristics != null && cameraIndex != -1);
            // Choose the sizes for camera preview and video recording

            StreamConfigurationMap map = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (map == null) {
                throw new RuntimeException("Cannot get available preview/video sizes");
            }

            //mVideoSize = chooseHighSpeedVideoSize(map.getHighSpeedVideoSizes());
            mVideoSize = new Size(1920, 1080);
            mPreviewSize = mVideoSize;

            int orientation = mActivity.getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            } else {
                mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
            }
            manager.openCamera(cameraId, mHwStateCallback, null, HwCameraManager.DEFAULT_MODE);
        } catch (CameraAccessException e) {
            Toast.makeText(mActivity,"Cannot access the camera.", Toast.LENGTH_SHORT).show();
            mActivity.finish();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.");
        }
    }

    /**
     * {@link CameraDevice.StateCallback} is called when {@link CameraDevice} changes its status.
     */
    private HwCameraDevice.StateCallback mHwStateCallback = new HwCameraDevice.StateCallback() {

        @Override
        public void onOpened(HwCameraDevice hwCameraDevice) {
            mHWCameraDevice = hwCameraDevice;

            startPreview();

            mCameraOpenCloseLock.release();
            if (null != mTextureView) {
                configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
            }
        }

        @Override
        public void onError(HwCameraDevice hwCameraDevice, int i) {
            Log.e("error", "error");

        }

        @Override
        public void onClosed(HwCameraDevice camera) {
            super.onClosed(camera);
        }

        @Override
        public void onDisconnected(HwCameraDevice hwCameraDevice) {
            Log.e("error", "error");
        }
    };

    private void configureTransform(int viewWidth, int viewHeight) {
        Activity activity = mActivity;
        if (null == mTextureView || null == mPreviewSize || null == activity) {
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    class EngineDieCallback implements HwCameraEngineDieRecipient {

        @Override
        public void onEngineDie() {
            closeCamera();

        }
    }

    public void startPreview() {
        if (null == mHWCameraDevice || null == mPreviewSize) {
            return;
        }
        try {
            closePreviewSession();
            mPreviewBuilder =mHWCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewBuilder.addTarget(mSurface);
            //createCaptureSession(mCameraDevice);

            createHWCaptureSession(mHWCameraDevice);

        } catch (CameraAccessException e) {}
        catch (RemoteException e) {}
    }

    private void closePreviewSession() {
        if (mCameraCaptureSession != null) {
            mCameraCaptureSession.close();
            mCameraCaptureSession = null;
        }
    }

    public void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            closePreviewSession();
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (hwCamera != null) {
                hwCamera.deInitialize();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.");
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    public void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground"+ mCameraId);
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    public void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * In this sample, we choose a video size with 3x4 aspect ratio. Also, we don't use sizes
     * larger than 1080p, since MediaRecorder cannot handle such a high-resolution video.
     *
     * @param choices The list of available sizes
     * @return The video size
     */
    private static Size chooseVideoSize(Size[] choices) {
        for (Size size : choices) {
            if (size.getWidth() == size.getHeight() * 4 / 3 && size.getWidth() <= 1080) {
                return size;
            }
        }
        return choices[choices.length - 1];
    }

    /**
     * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the respective requested values, and whose aspect
     * ratio matches with the specified value.
     *
     * @param choices     The list of sizes that the camera supports for the intended output class
     * @param width       The minimum desired width
     * @param height      The minimum desired height
     * @param aspectRatio The aspect ratio
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w &&
                    option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            return choices[0];
        }
    }

    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    private void setUpMediaRecorder() {
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        if (mNextVideoAbsolutePath == null || mNextVideoAbsolutePath.isEmpty()) {
            mNextVideoAbsolutePath = getVideoFilePath(mActivity);
        }
        mMediaRecorder.setOutputFile(mNextVideoAbsolutePath);
        mMediaRecorder.setVideoEncodingBitRate(10000000);
        mMediaRecorder.setVideoFrameRate(30);
        mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        int rotation = mActivity.getWindowManager().getDefaultDisplay().getRotation();
        switch (mSensorOrientation) {
            case SENSOR_ORIENTATION_DEFAULT_DEGREES:
                mMediaRecorder.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation));
                break;
            case SENSOR_ORIENTATION_INVERSE_DEGREES:
                mMediaRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation));
                break;
        }
        //mMediaRecorder.prepare();
    }
    public void startRecordingVideo() {

            closePreviewSession();
            setUpMediaRecorder();
            //surfaces.add(previewSurface);


    }

    private String getVideoFilePath(Context context) {
        final File dir = context.getExternalFilesDir(null);
        return (dir == null ? "" : (dir.getAbsolutePath() + "/"))
                + "Camera:" +mCameraId+ ".mp4";
    }
}
