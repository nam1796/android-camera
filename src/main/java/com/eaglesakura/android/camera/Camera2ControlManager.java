package com.eaglesakura.android.camera;

import com.eaglesakura.android.camera.error.CameraAccessFailedException;
import com.eaglesakura.android.camera.error.CameraException;
import com.eaglesakura.android.camera.error.CameraSecurityException;
import com.eaglesakura.android.camera.error.PictureFailedException;
import com.eaglesakura.android.camera.log.CameraLog;
import com.eaglesakura.android.camera.preview.CameraSurface;
import com.eaglesakura.android.camera.spec.CameraType;
import com.eaglesakura.android.camera.spec.CaptureFormat;
import com.eaglesakura.android.camera.spec.FocusMode;
import com.eaglesakura.android.thread.async.AsyncHandler;
import com.eaglesakura.android.util.AndroidThreadUtil;
import com.eaglesakura.android.util.ContextUtil;
import com.eaglesakura.thread.Holder;
import com.eaglesakura.util.Util;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.location.Location;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Android 5.0以降のCamera2 APIに対応したマネージャ
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class Camera2ControlManager extends CameraControlManager {
    final Camera2SpecImpl mSpec;

    final CameraCharacteristics mCharacteristics;

    /**
     * Openされたカメラ
     */
    @NonNull
    private CameraDevice mCamera;

    /**
     * 撮影用セッション
     *
     * MEMO: 基本的に使いまわさなければ撮影とプレビューを両立できない
     */
    private CameraCaptureSession mCaptureSession;

    private CaptureRequest.Builder mPreviewCaptureRequest;

    /**
     * プレビュー用バッファ
     */
    private CameraSurface mPreviewSurface;

    /**
     * 撮影用バッファ
     */
    private ImageReader mImageReader;

    /**
     * カメラ制御用のコールバックスレッド
     */
    private AsyncHandler mControlHandler;

    /**
     * 画像処理用のコールバックスレッド
     */
    private AsyncHandler mProcessingHandler;

    /**
     * 処理順を制御するためのキューイング
     */
    private AsyncHandler mTaskQueue;

    /**
     * 接続時に確定されたプレビューリクエスト
     */
    private CameraPreviewRequest mPreviewRequest;

    /**
     * 接続時に確定された撮影リクエスト
     */
    private CameraPictureShotRequest mPictureShotRequest;

    /**
     * プレビュー中である場合true
     */
    private static final int FLAG_NOW_PREVIEW = 0x01 << 0;

    private int mFlags;

    Camera2ControlManager(Context context, CameraConnectRequest request) throws CameraException {
        super(context, request);
        mSpec = new Camera2SpecImpl(context);
        mCharacteristics = mSpec.getCameraSpec(request.getCameraType());
    }

    @NonNull
    @Override
    public CameraApi getSupportApi() {
        return CameraApi.Camera2;
    }

    @Override
    public boolean connect(@Nullable CameraSurface previewSurface, @Nullable CameraPreviewRequest previewRequest, @Nullable CameraPictureShotRequest shotRequest) throws CameraException {
        AndroidThreadUtil.assertBackgroundThread();

        mControlHandler = AsyncHandler.createInstance("camera-control");
        mProcessingHandler = AsyncHandler.createInstance("camera-processing");
        mTaskQueue = AsyncHandler.createInstance("camera-queue");

        mCamera = mTaskQueue.await(() -> {
            mPreviewSurface = previewSurface;
            mPreviewRequest = previewRequest;
            mPictureShotRequest = shotRequest;

            Holder<CameraException> errorHolder = new Holder<>();
            Holder<CameraDevice> cameraDeviceHolder = new Holder<>();
            try {
                mSpec.getCameraManager().openCamera(mSpec.getCameraId(), new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(CameraDevice camera) {
                        cameraDeviceHolder.set(camera);
                        CameraLog.hardware("onOpened[%s]", camera.getId());
                    }

                    @Override
                    public void onDisconnected(CameraDevice camera) {
                        CameraLog.hardware("onDisconnected[%s]", camera.getId());
                        camera.close();
                        mCamera = null;
                    }

                    @Override
                    public void onError(CameraDevice camera, int error) {
                        errorHolder.set(new CameraSecurityException("Error :: " + error));
                        camera.close();
                        mCamera = null;
                    }
                }, mControlHandler);

                // データ待ちを行う
                while (errorHolder.get() == null && cameraDeviceHolder.get() == null) {
                    Util.sleep(1);
                }

                if (errorHolder.get() != null) {
                    throw errorHolder.get();
                }

                return cameraDeviceHolder.get();
            } catch (CameraAccessException e) {
                throw new CameraAccessFailedException(e);
            } catch (SecurityException e) {
                throw new CameraSecurityException(e);
            }
        });
        return true;
    }

    @Override
    public void startPreview(@Nullable CameraEnvironmentRequest env) throws CameraException {
        mTaskQueue.await(() -> {
            startPreviewImpl(env);
            return this;
        });
    }

    @Override
    public boolean isPreviewNow() {
        return (mFlags & FLAG_NOW_PREVIEW) != 0;
    }

    @Override
    public boolean isConnected() {
        return mCamera != null;
    }

    @Override
    public void disconnect() {
        AndroidThreadUtil.assertBackgroundThread();

        mTaskQueue.await(() -> {
            if (!isConnected()) {
                throw new IllegalStateException("not conencted");
            }

            try {
                stopPreviewImpl();
            } catch (Exception e) {

            }

            if (mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
            }

            try {
                mCamera.close();
                mCamera = null;
            } catch (Exception e) {
                e.printStackTrace();
            }

            return this;
        });

        // ハンドラを廃棄する
        mControlHandler.dispose();
        mProcessingHandler.dispose();
        mTaskQueue.dispose();

        mControlHandler = null;
        mProcessingHandler = null;
        mTaskQueue = null;
    }

    private int getJpegOrientation() {
        int sensorOrientation = mCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int deviceRotateDegree = ContextUtil.getDeviceRotateDegree(mContext);

        if (mConnectRequest.getCameraType() == CameraType.Back) {
            deviceRotateDegree = (360 - sensorOrientation + deviceRotateDegree) % 360;
        } else {
            deviceRotateDegree = (sensorOrientation + deviceRotateDegree + 360) % 360;
        }
        return deviceRotateDegree;
    }

    private CaptureRequest.Builder newCaptureRequest(CameraEnvironmentRequest env, int template) throws CameraAccessException {
        CaptureRequest.Builder request = mCamera.createCaptureRequest(template);

        if (env != null) {
            if (env.getFlashMode() != null) {
                request.set(CaptureRequest.FLASH_MODE, Camera2SpecImpl.toFlashModeInt(env.getFlashMode()));
            }

            if (env.getFocusMode() != null) {
                FocusMode mode = env.getFocusMode();
                if (mode == FocusMode.SETTING_INFINITY) {
                    // https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics.html
                    // LEGACY devices will support OFF mode only if they support focusing to infinity (by also setting android.lens.focusDistance to 0.0f).
                    request.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f);
                }

                request.set(CaptureRequest.CONTROL_AF_MODE, Camera2SpecImpl.toAeModeInt(mode));
            }

            if (env.getScene() != null) {
                request.set(CaptureRequest.CONTROL_SCENE_MODE, Camera2SpecImpl.toSceneInt(env.getScene()));
            }

            if (env.getWhiteBalance() != null) {
                request.set(CaptureRequest.CONTROL_AWB_MODE, Camera2SpecImpl.toAwbInt(env.getWhiteBalance()));
            }
        }

        return request;
    }

    @NonNull
    private CameraCaptureSession getSession() throws CameraException {
        if (mCaptureSession != null) {
            return mCaptureSession;
        }

        List<Surface> surfaces = new ArrayList<>();
        if (mPreviewRequest != null) {
            surfaces.add(mPreviewSurface.getNativeSurface(mPreviewRequest.getPreviewSize()));
        }
        if (mPictureShotRequest != null) {
            mImageReader = ImageReader.newInstance(
                    mPictureShotRequest.getCaptureSize().getWidth(), mPictureShotRequest.getCaptureSize().getHeight(),
                    mPictureShotRequest.getCaptureFormat() == CaptureFormat.Raw ? ImageFormat.RAW_SENSOR : ImageFormat.JPEG,
                    2
            );
            surfaces.add(mImageReader.getSurface());
        }

        Holder<CameraException> errorHolder = new Holder<>();
        Holder<CameraCaptureSession> sessionHolder = new Holder<>();

        try {
            mCamera.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    sessionHolder.set(session);
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    errorHolder.set(new CameraException("Session create failed"));
                }
            }, mControlHandler);
        } catch (CameraAccessException e) {
            throw new CameraAccessFailedException(e);
        }

        while (errorHolder.get() == null && sessionHolder.get() == null) {
            Util.sleep(1);
        }

        if (errorHolder.get() != null) {
            throw errorHolder.get();
        }

        mCaptureSession = sessionHolder.get();
        return mCaptureSession;
    }

    private void startPreviewImpl(@Nullable CameraEnvironmentRequest env) throws CameraException {
        try {
            // セッションを生成する
            CameraCaptureSession previewSession = getSession();

            // プレビューを開始する
            if (mPreviewCaptureRequest == null) {
                CaptureRequest.Builder builder = newCaptureRequest(env, CameraDevice.TEMPLATE_PREVIEW);
                builder.addTarget(mPreviewSurface.getNativeSurface(mPreviewRequest.getPreviewSize()));
                mPreviewCaptureRequest = builder;
            }
            previewSession.stopRepeating();
            previewSession.setRepeatingRequest(mPreviewCaptureRequest.build(), null, null);

            mFlags |= FLAG_NOW_PREVIEW;
        } catch (CameraAccessException e) {
            throw new CameraAccessFailedException(e);
        }
    }


    private void stopPreviewImpl() {
        synchronized (this) {
            try {
                if (mCaptureSession != null) {
                    mCaptureSession.stopRepeating();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            mFlags &= (~FLAG_NOW_PREVIEW);
        }
    }

    @Override
    public final void stopPreview() {
        mTaskQueue.await(() -> {
            stopPreviewImpl();
            return this;
        });
    }

    private void startPreCapture(CameraCaptureSession session, Surface imageSurface, @Nullable CameraEnvironmentRequest env) throws CameraException, CameraAccessException {
        CaptureRequest.Builder builder = newCaptureRequest(env, CameraDevice.TEMPLATE_PREVIEW);
        builder.addTarget(imageSurface);
        builder.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation());
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
        builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);

        Holder<CameraException> errorHolder = new Holder<>();
        Holder<Boolean> completedHolder = new Holder<>();
        session.stopRepeating();
        session.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                CameraLog.hardware("onCaptureCompleted :: pre-capture");
                CameraLog.hardware("  - AE State :: " + aeState);

                completedHolder.set(Boolean.TRUE);
            }

            @Override
            public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                errorHolder.set(new PictureFailedException("PreCapture Failed"));
            }
        }, mControlHandler);

        while (errorHolder.get() == null && completedHolder.get() == null) {
            Util.sleep(1);
        }

        if (errorHolder.get() != null) {
            throw errorHolder.get();
        }
    }

    PictureData takePictureImpl(@Nullable CameraEnvironmentRequest env) throws CameraException {
        if (!isPreviewNow()) {
            throw new IllegalStateException("Preview not started");
        }

        CameraCaptureSession session = getSession();
        try {
            startPreCapture(session, mPreviewSurface.getNativeSurface(mPreviewRequest.getPreviewSize()), env);

            Holder<CameraException> errorHolder = new Holder<>();
            Holder<PictureData> resultHolder = new Holder<>();
            Holder<Boolean> captureCompletedHolder = new Holder<>();

            // 撮影コールバック
            CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
                    captureCompletedHolder.set(Boolean.TRUE);
                }

                @Override
                public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
                    errorHolder.set(new PictureFailedException("Fail :: " + failure.getReason()));
                }
            };

            // 画像圧縮完了コールバック
            mImageReader.setOnImageAvailableListener(it -> {
                Image image = mImageReader.acquireLatestImage();
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] onMemoryFile = new byte[buffer.capacity()];
                buffer.get(onMemoryFile);

                resultHolder.set(new PictureData(image.getWidth(), image.getHeight(), onMemoryFile));

                image.close();
            }, mProcessingHandler);

            CaptureRequest.Builder builder = newCaptureRequest(env, CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation());

            // Lat/Lng
            if (mPictureShotRequest.hasLocation()) {
                Location loc = new Location("camera");
                loc.setLatitude(mPictureShotRequest.getLatitude());
                loc.setLongitude(mPictureShotRequest.getLongitude());
                builder.set(CaptureRequest.JPEG_GPS_LOCATION, loc);
            }

            builder.addTarget(mImageReader.getSurface());
            session.stopRepeating();
            session.capture(builder.build(), captureCallback, mControlHandler);

            while (errorHolder.get() == null && resultHolder.get() == null) {
                Util.sleep(1);
            }

            if (errorHolder.get() != null) {
                throw errorHolder.get();
            }

            while (captureCompletedHolder.get() == null) {
                Util.sleep(1);
            }

            return resultHolder.get();
        } catch (CameraAccessException e) {
            throw new CameraAccessFailedException(e);
        } finally {
            mImageReader.setOnImageAvailableListener(null, null);
            if ((mFlags & FLAG_NOW_PREVIEW) != 0) {
                startPreviewImpl(env);
            }
        }
    }

    @Override
    public final PictureData takePicture(@Nullable CameraEnvironmentRequest env) throws CameraException {
        return mTaskQueue.await(() -> takePictureImpl(env));
    }
}
