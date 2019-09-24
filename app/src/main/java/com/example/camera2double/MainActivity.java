package com.example.camera2double;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;

import com.huawei.emui.himedia.camera.HwCamera;
import com.huawei.emui.himedia.camera.HwCameraInitSuccessCallback;

public class MainActivity extends AppCompatActivity {
    private AutoFitTextureView mTextureView;
    private AutoFitTextureView mTextureView2;
    private CameraInstance mCameraInstance;
    private CameraInstance mCameraInstance2;
    private Button mButton;
    private boolean mIsRecordering = false;
    private HwCamera mHwCamera = new HwCamera();
    Surface previewSurface;
    private static final int REQUEST_VIDEO_PERMISSIONS = 1;
    private static final String FRAGMENT_DIALOG = "dialog";
    private boolean flag=false;
    private Handler mHandler;
    private static final String[] VIDEO_PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
    };

    private boolean hasPermissionsGranted(String[] permissions) {
        for (String permission : permissions) {
            if (ActivityCompat.checkSelfPermission(getApplicationContext(), permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (!hasPermissionsGranted(VIDEO_PERMISSIONS)) {
            requestVideoPermissions();
        }
        mTextureView2 = (AutoFitTextureView) findViewById(R.id.textrue2);
        mTextureView = (AutoFitTextureView) findViewById(R.id.textrue);
        mCameraInstance = new CameraInstance(this, mTextureView,mTextureView2, "0", mHwCamera);
        //mCameraInstance2 = new CameraInstance(this,mTextureView ,mTextureView2 , "2",mHwCamera);
        mButton = (Button) findViewById(R.id.button);
        mHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                if (msg.what == 1) {
                    //mCameraInstance.openCamera(mTextureView.getWidth(),mTextureView.getHeight());
                } else {
                    mCameraInstance.openCamera(mTextureView2.getWidth(),mTextureView2.getHeight());
                }
            }
        };
        mButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mIsRecordering) {
                    mCameraInstance.startRecordingVideo();
                    //mCameraInstance2.startRecordingVideo();
                    mIsRecordering = true;
                } else {
                    mCameraInstance.stopRecordingVideo();
                   // mCameraInstance2.stopRecordingVideo();
                    mIsRecordering = false;
                }
            }
        });
    }

    /**
     * Requests permissions needed for recording video.
     */
    private void requestVideoPermissions() {
        if (shouldShowRequestPermissionRationale(VIDEO_PERMISSIONS)) {
            new ConfirmationDialog().show(getSupportFragmentManager(), FRAGMENT_DIALOG);
        } else {
            ActivityCompat.requestPermissions(this, VIDEO_PERMISSIONS, REQUEST_VIDEO_PERMISSIONS);
        }
    }

    /**
     * Gets whether you should show UI with rationale for requesting permissions.
     *
     * @param permissions The permissions your app wants to request.
     * @return Whether you can show permission rationale UI.
     */
    private boolean shouldShowRequestPermissionRationale(String[] permissions) {
        for (String permission : permissions) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected  void onResume() {
        super.onResume();

        mCameraInstance.startBackgroundThread();
        //mCameraInstance2.startBackgroundThread();
        if(mTextureView2.isAvailable()) {
            //mCameraInstance2.openCamera(mTextureView2.getWidth(),mTextureView2.getHeight());
            mHandler.sendMessage(Message.obtain(mHandler,2));
        } else {
            mTextureView2.setSurfaceTextureListener(mSurfaceTextureListener2);
        }
        if(mTextureView.isAvailable()) {
            mHandler.sendMessage(Message.obtain(mHandler,1));
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }

    }

    @Override
    protected  void onPause() {
        super.onPause();
        mCameraInstance.closeCamera();
        mCameraInstance.stopBackgroundThread();
    }

    private TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
            mHandler.sendMessage(Message.obtain(mHandler,1));
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

        }
    };

    private TextureView.SurfaceTextureListener mSurfaceTextureListener2
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
            //mCameraInstance2.openCamera(i, i1);
            mHandler.sendMessage(Message.obtain(mHandler,2));
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

        }
    };

    public static class ConfirmationDialog extends DialogFragment {

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Fragment parent = getParentFragment();
            return new AlertDialog.Builder(getActivity())
                    .setMessage(R.string.permission_request)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            ActivityCompat.requestPermissions(getActivity(), VIDEO_PERMISSIONS,
                                    REQUEST_VIDEO_PERMISSIONS);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    parent.getActivity().finish();
                                }
                            })
                    .create();
        }

    }
}
