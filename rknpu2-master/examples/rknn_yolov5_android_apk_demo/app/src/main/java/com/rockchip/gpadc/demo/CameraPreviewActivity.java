package com.rockchip.gpadc.demo;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Typeface;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.rockchip.gpadc.demo.rga.RGA;
import com.rockchip.gpadc.demo.yolo.InferenceWrapper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.util.List;

import static com.rockchip.gpadc.demo.rga.HALDefine.CAMERA_PREVIEW_HEIGHT;
import static com.rockchip.gpadc.demo.rga.HALDefine.CAMERA_PREVIEW_WIDTH;
import static com.rockchip.gpadc.demo.rga.HALDefine.IM_HAL_TRANSFORM_FLIP_H;
import static com.rockchip.gpadc.demo.rga.HALDefine.RK_FORMAT_RGBA_8888;
import static com.rockchip.gpadc.demo.rga.HALDefine.RK_FORMAT_YCrCb_420_SP;
import static java.lang.Thread.sleep;

public class CameraPreviewActivity extends Activity implements Camera.PreviewCallback {
    private final String TAG = "rkyolo";
    TSurfaceHolderCallback mSurfaceHolderCallback = null;
    private Camera mCamera0 = null;
    private SurfaceHolder mSurfaceHolder = null;
    public int flip = -1;
    private boolean mIsCameraOpened = false;

    // --- 推理相关 ---
    private String mModelName = "best_densenet121.rknn"; // 确保assets里有它
    private InferenceWrapper mInferenceWrapper;
    private String fileDirPath;
    private ImageBufferQueue mImageBufferQueue;
    private int mWidth;
    private int mHeight;
    private volatile boolean mStopInference = false;

    // --- 新增：App 控制功能变量 ---
    private volatile boolean mEnableDetection = true; // 检测开关
    private volatile long mDetectIntervalMs = 1000;   // 检测频率（默认 1000ms = 1秒）
    private long mLastDetectTime = 0;                 // 上次检测时间

    // 存放诊断结果 ID (-1代表未知，-2代表已暂停)
    private volatile int mCurrentClassId = -1;

    // --- UI 相关 ---
    private TextView mFpsNum1, mFpsNum2, mFpsNum3, mFpsNum4;
    private ImageView mTrackResultView;
    private Bitmap mTrackResultBitmap = null;
    private Canvas mTrackResultCanvas = null;
    private Paint mTrackResultTextPaint = null;
    private Paint mInfoPaint = null;
    private Paint mBackgroundPaint = null;

    private PorterDuffXfermode mPorterDuffXfermodeClear;
    private PorterDuffXfermode mPorterDuffXfermodeSRC;

    // 实际训练的光照数据集标签
    private final String[] LABELS = {"5000xl", "2000xl", "500xl"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mFpsNum1 = findViewById(R.id.fps_num1);
        mFpsNum2 = findViewById(R.id.fps_num2);
        mFpsNum3 = findViewById(R.id.fps_num3);
        mFpsNum4 = findViewById(R.id.fps_num4);
        mTrackResultView = findViewById(R.id.canvasView);

        fileDirPath = getCacheDir().getAbsolutePath();
        createFile(mModelName, mModelName);
        mInferenceWrapper = new InferenceWrapper();
    }

    // --- 新增：触控屏幕改变设置 ---
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            float y = event.getY();
            int screenHeight = getWindowManager().getDefaultDisplay().getHeight();

            if (y < screenHeight / 2.0f) {
                // 点击上半屏：开关检测
                mEnableDetection = !mEnableDetection;
                Toast.makeText(this, mEnableDetection ? "✅ AI 检测已开启" : "⏸️ AI 检测已暂停", Toast.LENGTH_SHORT).show();
            } else {
                // 点击下半屏：切换频率
                if (mDetectIntervalMs == 1000) {
                    mDetectIntervalMs = 500;
                } else if (mDetectIntervalMs == 500) {
                    mDetectIntervalMs = 0; // 极速模式，不延迟
                } else {
                    mDetectIntervalMs = 1000;
                }
                Toast.makeText(this, "⏱️ 频率已切换至: " + (mDetectIntervalMs == 0 ? "极速" : mDetectIntervalMs + "ms"), Toast.LENGTH_SHORT).show();
            }
            updateMainUI(1, 0); // 立即刷新UI
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDestroy() { destroyPreviewView(); super.onDestroy(); }
    @Override
    protected void onPause() { stopTrack(); stopCamera(); destroyPreviewView(); super.onPause(); }
    @Override
    protected void onResume() { createPreviewView(); super.onResume(); }

    private boolean createPreviewView() {
        mSurfaceHolder = ((SurfaceView) findViewById(R.id.surfaceViewCamera1)).getHolder();
        mSurfaceHolderCallback = new TSurfaceHolderCallback();
        mSurfaceHolder.addCallback(mSurfaceHolderCallback);
        return true;
    }

    private void destroyPreviewView() {
        if (mSurfaceHolder != null) {
            mSurfaceHolder.removeCallback(mSurfaceHolderCallback);
            mSurfaceHolderCallback = null;
            mSurfaceHolder = null;
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        mCamera0.addCallbackBuffer(data);
        ImageBufferQueue.ImageBuffer imageBuffer = mImageBufferQueue.getFreeBuffer();
        if (imageBuffer != null) {
            RGA.colorConvertAndFlip(data, RK_FORMAT_YCrCb_420_SP,
                    imageBuffer.mImage, RK_FORMAT_RGBA_8888,
                    CAMERA_PREVIEW_WIDTH, CAMERA_PREVIEW_HEIGHT, this.flip);
            mImageBufferQueue.postBuffer(imageBuffer);
        }
    }

    private class TSurfaceHolderCallback implements SurfaceHolder.Callback {
        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { mWidth = width; mHeight = height; }
        @Override
        public void surfaceCreated(SurfaceHolder holder) { startCamera(); startTrack(); }
        @Override
        public void surfaceDestroyed(SurfaceHolder holder) { stopTrack(); stopCamera(); }
    }

    private boolean startCamera() {
        if (mIsCameraOpened) return true;
        int num = Camera.getNumberOfCameras();
        Camera.CameraInfo camInfo = new Camera.CameraInfo();
        try {
            Camera.getCameraInfo(num > 2 ? 2 : 0, camInfo);
            mCamera0 = Camera.open(num > 2 ? 2 : 0);
            if (Camera.CameraInfo.CAMERA_FACING_FRONT == camInfo.facing) this.flip = IM_HAL_TRANSFORM_FLIP_H;
        } catch (RuntimeException e) { return false; }

        Camera.Parameters parameters = mCamera0.getParameters();
        List<Camera.Size> sizes = parameters.getSupportedPreviewSizes();
        for (Camera.Size size : sizes) {
            if (size.width == CAMERA_PREVIEW_WIDTH && size.height == CAMERA_PREVIEW_HEIGHT) {
                parameters.setPreviewSize(CAMERA_PREVIEW_WIDTH, CAMERA_PREVIEW_HEIGHT);
                mCamera0.setParameters(parameters);
                break;
            }
        }

        try {
            mCamera0.setPreviewDisplay(mSurfaceHolder);
            mCamera0.setDisplayOrientation(0);
            int BUFFER_SIZE0 = CAMERA_PREVIEW_WIDTH * CAMERA_PREVIEW_HEIGHT * 3 / 2;
            byte[][] mPreviewData0 = new byte[][]{new byte[BUFFER_SIZE0], new byte[BUFFER_SIZE0], new byte[BUFFER_SIZE0]};
            for (byte[] buffer : mPreviewData0) mCamera0.addCallbackBuffer(buffer);
            mCamera0.setPreviewCallbackWithBuffer(this);
            mCamera0.startPreview();
        } catch (Exception e) { mCamera0.release(); return false; }
        mIsCameraOpened = true;
        return true;
    }

    private void stopCamera() {
        if (mIsCameraOpened) {
            mCamera0.setPreviewCallback(null);
            mCamera0.stopPreview();
            mCamera0.release();
            mCamera0 = null;
            mIsCameraOpened = false;
        }
    }

    private void startTrack() {
        mImageBufferQueue = new ImageBufferQueue(3, CAMERA_PREVIEW_WIDTH, CAMERA_PREVIEW_HEIGHT);
        mStopInference = false;
        mInferenceThread = new Thread(mInferenceRunnable);
        mInferenceThread.start();
    }

    private void stopTrack() {
        mStopInference = true;
        try { if (mInferenceThread != null) mInferenceThread.join(); } catch (InterruptedException e) {}
        if (mImageBufferQueue != null) { mImageBufferQueue.release(); mImageBufferQueue = null; }
    }

    private Thread mInferenceThread;
    private Runnable mInferenceRunnable = new Runnable() {
        public void run() {
            int count = 0;
            long oldTime = System.currentTimeMillis();
            String paramPath = fileDirPath + "/" + mModelName;

            try {
                mInferenceWrapper.native_init(224, 224, 3, paramPath);
            } catch (Exception e) { e.printStackTrace(); System.exit(1); }

            while (!mStopInference) {
                // --- 频率与开关控制逻辑 ---
                if (!mEnableDetection) {
                    // 如果已关闭检测，清空队列防止内存堆积，并休眠
                    ImageBufferQueue.ImageBuffer buffer = mImageBufferQueue.getReadyBuffer();
                    if (buffer != null) mImageBufferQueue.releaseBuffer(buffer);
                    mCurrentClassId = -2; // 状态设为已暂停
                    updateMainUI(1, 0);
                    try { sleep(100); } catch (InterruptedException e) {}
                    continue;
                }

                long currentTime = System.currentTimeMillis();
                if (currentTime - mLastDetectTime < mDetectIntervalMs) {
                    // 如果还没到下次检测时间，丢弃当前帧并等待
                    ImageBufferQueue.ImageBuffer buffer = mImageBufferQueue.getReadyBuffer();
                    if (buffer != null) mImageBufferQueue.releaseBuffer(buffer);
                    try { sleep(30); } catch (InterruptedException e) {}
                    continue;
                }
                mLastDetectTime = currentTime;

                ImageBufferQueue.ImageBuffer buffer = mImageBufferQueue.getReadyBuffer();
                if (buffer == null) continue;

                // --- 数据格式化处理 ---
                Bitmap bitmap = Bitmap.createBitmap(CAMERA_PREVIEW_WIDTH, CAMERA_PREVIEW_HEIGHT, Bitmap.Config.ARGB_8888);
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(buffer.mImage));
                Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, 224, 224, true);

                byte[] rgbBytes = new byte[224 * 224 * 3];
                int[] pixels = new int[224 * 224];
                resizedBitmap.getPixels(pixels, 0, 224, 0, 0, 224, 224);

                // 【核心修复：转为 BGR 格式】
                for (int i = 0; i < pixels.length; i++) {
                    int color = pixels[i];
                    rgbBytes[i * 3]     = (byte) (color & 0xFF);         // B: 蓝通道在前
                    rgbBytes[i * 3 + 1] = (byte) ((color >> 8) & 0xFF);  // G: 绿通道
                    rgbBytes[i * 3 + 2] = (byte) ((color >> 16) & 0xFF); // R: 红通道在后
                }

                bitmap.recycle();
                if (bitmap != resizedBitmap) resizedBitmap.recycle();

                // 送入底层推理
                mCurrentClassId = mInferenceWrapper.native_run(rgbBytes);

                mImageBufferQueue.releaseBuffer(buffer);

                if (++count >= 30) {
                    float fps = count * 1000.f / (System.currentTimeMillis() - oldTime);
                    oldTime = System.currentTimeMillis();
                    count = 0;
                    updateMainUI(0, fps);
                }
                updateMainUI(1, 0);
            }
            mInferenceWrapper.native_deinit();
        }
    };

    private void createFile(String fileName, String assetsName) {
        String filePath = fileDirPath + "/" + fileName;
        try {
            File file = new File(filePath);
            if (!file.exists() || isFirstRun()) {
                InputStream ins = getAssets().open(assetsName);
                FileOutputStream fos = new FileOutputStream(file);
                byte[] buffer = new byte[8192];
                int count;
                while ((count = ins.read(buffer)) > 0) fos.write(buffer, 0, count);
                fos.close(); ins.close();
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private boolean isFirstRun() {
        SharedPreferences sharedPreferences = getSharedPreferences("setting", MODE_PRIVATE);
        boolean isFirstRun = sharedPreferences.getBoolean("isFirstRun", true);
        if (isFirstRun) sharedPreferences.edit().putBoolean("isFirstRun", false).commit();
        return isFirstRun;
    }

    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            if (msg.what == 0) {
                // FPS 刷新逻辑保持不变...
            } else { showClassificationResults(); }
        }
    };

    private void updateMainUI(int type, Object data) {
        Message msg = mHandler.obtainMessage();
        msg.what = type; msg.obj = data; mHandler.sendMessage(msg);
    }

    private void showClassificationResults() {
        int width = CAMERA_PREVIEW_WIDTH;
        int height = CAMERA_PREVIEW_HEIGHT;

        if (mTrackResultBitmap == null) {
            mTrackResultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            mTrackResultCanvas = new Canvas(mTrackResultBitmap);

            mTrackResultTextPaint = new Paint();
            mTrackResultTextPaint.setColor(0xffffffff);
            mTrackResultTextPaint.setTextAlign(Paint.Align.CENTER);
            mTrackResultTextPaint.setTextSize(sp2px(28));
            mTrackResultTextPaint.setTypeface(Typeface.DEFAULT_BOLD);

            mInfoPaint = new Paint();
            mInfoPaint.setColor(0xff00ff00); // 绿色小字提示
            mInfoPaint.setTextAlign(Paint.Align.CENTER);
            mInfoPaint.setTextSize(sp2px(16));

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(0x88000000);

            mPorterDuffXfermodeClear = new PorterDuffXfermode(PorterDuff.Mode.CLEAR);
            mPorterDuffXfermodeSRC = new PorterDuffXfermode(PorterDuff.Mode.SRC);
        }

        mTrackResultTextPaint.setXfermode(mPorterDuffXfermodeClear);
        mTrackResultCanvas.drawPaint(mTrackResultTextPaint);
        mTrackResultTextPaint.setXfermode(mPorterDuffXfermodeSRC);

        // --- 绘制诊断结果 ---
        if (mCurrentClassId == -2) {
            mTrackResultCanvas.drawRect(0, height / 2 - sp2px(35), width, height / 2 + sp2px(10), mBackgroundPaint);
            mTrackResultTextPaint.setColor(0xffaaaaaa); // 灰色
            mTrackResultCanvas.drawText("检测已暂停 (点击上半屏开启)", width / 2, height / 2, mTrackResultTextPaint);
            mTrackResultTextPaint.setColor(0xffffffff);
        } else if (mCurrentClassId >= 0 && mCurrentClassId < LABELS.length) {
            String resultText = "光照诊断: " + LABELS[mCurrentClassId];
            mTrackResultCanvas.drawRect(0, height / 2 - sp2px(35), width, height / 2 + sp2px(10), mBackgroundPaint);
            mTrackResultCanvas.drawText(resultText, width / 2, height / 2, mTrackResultTextPaint);
        } else if (mCurrentClassId == -1) {
            mTrackResultCanvas.drawRect(0, height / 2 - sp2px(35), width, height / 2 + sp2px(10), mBackgroundPaint);
            mTrackResultTextPaint.setColor(0xffffff00); // 黄色
            mTrackResultCanvas.drawText("无法测出 (非目标或置信度低)", width / 2, height / 2, mTrackResultTextPaint);
            mTrackResultTextPaint.setColor(0xffffffff);
        }

        // --- 绘制底部设置提示 ---
        String infoText = "设置 | 状态: " + (mEnableDetection ? "检测中" : "已暂停") +
                " | 频率: " + (mDetectIntervalMs == 0 ? "极速" : mDetectIntervalMs + "ms");
        mTrackResultCanvas.drawText(infoText, width / 2, height - sp2px(20), mInfoPaint);

        mTrackResultView.setImageBitmap(mTrackResultBitmap);
    }

    private int sp2px(float spValue) {
        return (int) (spValue * getResources().getDisplayMetrics().scaledDensity + 0.5f);
    }
}