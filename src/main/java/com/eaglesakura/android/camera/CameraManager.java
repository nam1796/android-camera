package com.eaglesakura.android.camera;

import com.eaglesakura.android.camera.error.CameraException;
import com.eaglesakura.android.camera.spec.CameraType;

import android.content.Context;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.view.Surface;

public abstract class CameraManager {
    protected final Context mContext;

    protected final CameraConnectRequest mRequest;

    public CameraManager(Context context, CameraConnectRequest request) {
        mContext = context.getApplicationContext();
        mRequest = request;
    }

    public abstract boolean connect() throws CameraException;

    public abstract boolean isConnected();

    public abstract void disconnect();

    /**
     * 環境設定をリクエストする
     */
    public abstract void request(CameraEnvironmentRequest env) throws CameraException;

    /**
     * カメラプレビューを開始する
     *
     * MEMO: プレビューの開始はサーフェイスと同期しなければならないため、実装的にはUIスレッド・バックグラウンドスレッドどちらでも動作できる。
     */
    public abstract void startPreview(@NonNull Surface surface, @NonNull CameraPreviewRequest preview, @Nullable CameraEnvironmentRequest env) throws CameraException;

    /**
     * カメラプレビューを停止する
     *
     * MEMO: プレビューの停止はサーフェイスと同期して削除しなければならないため、実装的にはUIスレッド・バックグラウンドスレッドどちらでも動作できる。
     */
    public abstract void stopPreview() throws CameraException;

    /**
     * 写真撮影を行わせる
     */
    @NonNull
    public abstract PictureData takePicture(@NonNull CameraPictureShotRequest request, @Nullable CameraEnvironmentRequest env) throws CameraException;

    public static CameraManager newInstance(Context context, CameraConnectRequest request) throws CameraException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Camera2
            return new Camera2ManagerImpl(context, request);
        } else {
            // Camera1
            throw new IllegalStateException();
        }
    }
}
