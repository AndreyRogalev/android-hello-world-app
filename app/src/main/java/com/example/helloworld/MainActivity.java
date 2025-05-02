package com.example.helloworld;

import android.Manifest;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends AppCompatActivity implements LayerAdapter.OnLayerVisibilityChangedListener {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 100;
    private static final int REQUEST_STORAGE_PERMISSION = 101;
    private static final int PICK_IMAGE_REQUEST = 1;

    // --- UI Elements ---
    private SurfaceView cameraSurfaceView;
    private ImageView imageView;
    private SeekBar transparencySeekBar;
    private Button pickImageButton;
    private Switch pencilModeSwitch;
    private Button layerSelectButton;
    private CheckBox controlsVisibilityCheckbox;
    private CheckBox hideImageCheckbox;
    private Button saveParametersButton;
    private Button loadParametersButton;
    private Button switchCameraButton;

    // --- Camera ---
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private String cameraId;
    private Size previewSize;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private final Object cameraOpenCloseLock = new Object();
    private volatile boolean isSurfaceAvailable = false;
    private volatile boolean isCameraOpen = false;
    private String[] cameraIds;
    private int currentCameraIndex = 0;

    // --- Image Manipulation ---
    private Bitmap originalBitmap;
    private Bitmap pencilBitmap;
    private Bitmap[] layerBitmaps;
    private boolean[] layerVisibility;
    private Matrix matrix = new Matrix();
    private Matrix savedMatrix = new Matrix();
    private boolean isPencilMode = false;
    private boolean isImageVisible = true;

    // --- Gesture Detection ---
    private ScaleGestureDetector scaleGestureDetector;
    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    private int touchMode = NONE;
    private final PointF startPoint = new PointF();
    private final PointF midPoint = new PointF();
    private float initialAngle = 0f;

    // --- Pencil Mode Layers ---
    private static final String[] PENCIL_HARDNESS = {
        "9H", "7H", "5H", "3H", "H", "HB", "B", "3B", "6B", "9B" // Reduced to 10 layers for memory optimization
    };

    //==========================================================================
    // SurfaceHolder Callback
    //==========================================================================
    private final SurfaceHolder.Callback surfaceHolderCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.d(TAG, "Surface created");
            isSurfaceAvailable = true;
            final Size finalSize = previewSize != null ? previewSize : new Size(1280, 720);
            runOnUiThread(() -> {
                try {
                    holder.setFixedSize(finalSize.getWidth(), finalSize.getHeight());
                    Log.d(TAG, "Set SurfaceHolder fixed size: " + finalSize);
                } catch (Exception e) {
                    Log.e(TAG, "Error setting fixed size", e);
                }
            });
            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                openCamera();
            } else {
                checkPermissionsAndSetupCamera();
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.d(TAG, "Surface changed: " + width + "x" + height);
            if (isCameraOpen && cameraDevice != null) {
                previewSize = previewSize != null ? previewSize : chooseOptimalPreviewSize(getPreviewSizes(), width, height);
                startCameraPreview();
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.d(TAG, "Surface destroyed");
            isSurfaceAvailable = false;
            closeCamera();
        }
    };

    //==========================================================================
    // Activity Lifecycle & Initialization
    //==========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initializeUI();
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());
        imageView.setOnTouchListener(new TouchAndRotateListener());
        imageView.setScaleType(ImageView.ScaleType.MATRIX);
        cameraSurfaceView.getHolder().addCallback(surfaceHolderCallback);
        setupUIListeners();
        checkPermissionsAndSetupCamera();
        layerVisibility = new boolean[PENCIL_HARDNESS.length];
        Arrays.fill(layerVisibility, true);
        updateControlsVisibility(controlsVisibilityCheckbox.isChecked());
    }

    private void initializeUI() {
        cameraSurfaceView = findViewById(R.id.cameraSurfaceView);
        imageView = findViewById(R.id.imageView);
        transparencySeekBar = findViewById(R.id.transparencySeekBar);
        pickImageButton = findViewById(R.id.pickImageButton);
        pencilModeSwitch = findViewById(R.id.pencilModeSwitch);
        layerSelectButton = findViewById(R.id.layerSelectButton);
        controlsVisibilityCheckbox = findViewById(R.id.controlsVisibilityCheckbox);
        hideImageCheckbox = findViewById(R.id.hideImageCheckbox);
        saveParametersButton = findViewById(R.id.saveParametersButton);
        loadParametersButton = findViewById(R.id.loadParametersButton);
        switchCameraButton = findViewById(R.id.switchCameraButton);
    }

    private void setupUIListeners() {
        transparencySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean u) { setImageAlpha(p); }
            @Override
            public void onStartTrackingTouch(SeekBar s) {}
            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });
        pickImageButton.setOnClickListener(v -> pickImage());
        pencilModeSwitch.setOnCheckedChangeListener((v, isChecked) -> {
            isPencilMode = isChecked;
            layerSelectButton.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (isPencilMode) processPencilEffect();
            else recyclePencilBitmaps();
            updateImageDisplay();
        });
        layerSelectButton.setOnClickListener(v -> showLayerSelectionDialog());
        controlsVisibilityCheckbox.setOnCheckedChangeListener((v, c) -> updateControlsVisibility(c));
        hideImageCheckbox.setOnCheckedChangeListener((v, c) -> {
            isImageVisible = !c;
            updateImageDisplay();
        });
        saveParametersButton.setOnClickListener(v -> saveParameters());
        loadParametersButton.setOnClickListener(v -> loadParameters());
        switchCameraButton.setOnClickListener(v -> switchCamera());
    }

    //==========================================================================
    // Gesture Handling
    //==========================================================================

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(@NonNull ScaleGestureDetector d) {
            return originalBitmap != null && !originalBitmap.isRecycled();
        }
        @Override
        public boolean onScale(@NonNull ScaleGestureDetector d) {
            if (originalBitmap == null || originalBitmap.isRecycled() || touchMode != ZOOM) return false;
            float scale = d.getScaleFactor();
            matrix.postScale(scale, scale, d.getFocusX(), d.getFocusY());
            applyTransformations();
            return true;
        }
    }

    private class TouchAndRotateListener implements View.OnTouchListener {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (originalBitmap == null || originalBitmap.isRecycled()) return false;
            scaleGestureDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    savedMatrix.set(matrix);
                    startPoint.set(event.getX(), event.getY());
                    touchMode = DRAG;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (event.getPointerCount() >= 2) {
                        savedMatrix.set(matrix);
                        midPoint(midPoint, event);
                        initialAngle = rotation(event);
                        touchMode = ZOOM;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (touchMode == DRAG && event.getPointerCount() == 1) {
                        matrix.set(savedMatrix);
                        matrix.postTranslate(event.getX() - startPoint.x, event.getY() - startPoint.y);
                        applyTransformations();
                    } else if (touchMode == ZOOM && event.getPointerCount() >= 2 && !scaleGestureDetector.isInProgress()) {
                        float currentAngle = rotation(event);
                        float deltaAngle = currentAngle - initialAngle;
                        if (Math.abs(deltaAngle) > 2.0f) {
                            matrix.postRotate(deltaAngle, midPoint.x, midPoint.y);
                            applyTransformations();
                            savedMatrix.set(matrix);
                            initialAngle = currentAngle;
                            midPoint(midPoint, event);
                        }
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    savedMatrix.set(matrix);
                    if (event.getPointerCount() <= 2) {
                        touchMode = NONE;
                        int remIdx = (event.getActionIndex() == 0) ? 1 : 0;
                        if (event.getPointerCount() > remIdx) {
                            startPoint.set(event.getX(remIdx), event.getY(remIdx));
                            touchMode = DRAG;
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    touchMode = NONE;
                    break;
            }
            return true;
        }
        private void midPoint(PointF p, MotionEvent e) {
            if (e.getPointerCount() < 2) return;
            p.set((e.getX(0) + e.getX(1)) / 2f, (e.getY(0) + e.getY(1)) / 2f);
        }
        private float rotation(MotionEvent e) {
            if (e.getPointerCount() < 2) return 0f;
            return (float) Math.toDegrees(Math.atan2(e.getY(0) - e.getY(1), e.getX(0) - e.getX(1)));
        }
    }

    //==========================================================================
    // UI Updates & Display Logic
    //==========================================================================

    private void updateControlsVisibility(boolean show) {
        int v = show ? View.VISIBLE : View.GONE;
        transparencySeekBar.setVisibility(v);
        pickImageButton.setVisibility(v);
        pencilModeSwitch.setVisibility(v);
        layerSelectButton.setVisibility(show && isPencilMode ? View.VISIBLE : View.GONE);
        hideImageCheckbox.setVisibility(v);
        saveParametersButton.setVisibility(v);
        loadParametersButton.setVisibility(v);
        switchCameraButton.setVisibility(v);
    }

    private void applyTransformations() {
        if (imageView != null) {
            runOnUiThread(() -> imageView.setImageMatrix(matrix));
        }
    }

    private void setImageAlpha(int p) {
        float a = Math.max(0.0f, Math.min(1.0f, p / 100.0f));
        if (imageView != null) {
            runOnUiThread(() -> imageView.setAlpha(a));
        }
    }

    private void updateImageDisplay() {
        if (imageView == null) return;
        if (!isImageVisible || originalBitmap == null || originalBitmap.isRecycled()) {
            runOnUiThread(() -> {
                imageView.setImageBitmap(null);
                imageView.setVisibility(View.GONE);
            });
            return;
        }
        new Thread(() -> {
            Bitmap bmp = null;
            boolean displayOrig = true;
            if (isPencilMode && layerBitmaps != null) {
                try {
                    bmp = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(bmp);
                    c.drawColor(Color.TRANSPARENT);
                    Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
                    boolean drawn = false;
                    for (int i = 0; i < layerBitmaps.length; i++)
                        if (layerVisibility[i] && layerBitmaps[i] != null && !layerBitmaps[i].isRecycled()) {
                            c.drawBitmap(layerBitmaps[i], 0, 0, p);
                            drawn = true;
                        }
                    if (!drawn) {
                        if (bmp != null && !bmp.isRecycled()) bmp.recycle();
                        bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
                        bmp.eraseColor(Color.TRANSPARENT);
                    }
                    displayOrig = false;
                } catch (Exception e) {
                    bmp = null;
                    runOnUiThread(() -> Toast.makeText(this, "Layer Err", Toast.LENGTH_SHORT).show());
                }
            }
            if (displayOrig) bmp = originalBitmap;
            final Bitmap finalBmp = bmp;
            final boolean finalOrig = displayOrig;
            runOnUiThread(() -> {
                if (imageView != null) {
                    if (finalBmp != null && !finalBmp.isRecycled()) {
                        imageView.setImageBitmap(finalBmp);
                        imageView.setVisibility(View.VISIBLE);
                        imageView.setImageMatrix(matrix);
                        setImageAlpha(transparencySeekBar.getProgress());
                        imageView.invalidate();
                    } else {
                        imageView.setImageBitmap(null);
                        imageView.setVisibility(View.GONE);
                    }
                }
            });
        }).start();
    }

    //==========================================================================
    // Permission Handling
    //==========================================================================

    private void checkPermissionsAndSetupCamera() {
        String[] ps = {Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE};
        List<String> n = new ArrayList<>();
        for (String p : ps)
            if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) n.add(p);
        if (!n.isEmpty())
            ActivityCompat.requestPermissions(this, n.toArray(new String[0]), REQUEST_CAMERA_PERMISSION);
        else setupCamera();
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        boolean ok = results.length > 0;
        for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
        if (ok) {
            setupCamera();
            if (isSurfaceAvailable && !isCameraOpen) openCamera();
        } else Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show();
    }

    //==========================================================================
    // Camera Setup & Control
    //==========================================================================

    private void setupCamera() {
        CameraManager m = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            cameraIds = m.getCameraIdList();
            if (cameraIds.length > 0) {
                String r = null;
                for (String id : cameraIds) {
                    Integer f = m.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                    if (f != null && f == CameraCharacteristics.LENS_FACING_BACK) {
                        r = id;
                        break;
                    }
                }
                cameraId = (r != null) ? r : cameraIds[0];
                currentCameraIndex = Arrays.asList(cameraIds).indexOf(cameraId);
            } else cameraId = null;
        } catch (Exception e) {
            Log.e(TAG, "Cam setup err", e);
        }
        if (cameraId == null) Toast.makeText(this, "No cams", Toast.LENGTH_LONG).show();
    }

    private void startBackgroundThread() {
        if (backgroundThread == null || !backgroundThread.isAlive()) {
            stopBackgroundThread();
            backgroundThread = new HandlerThread("CameraBg");
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());
        }
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    private void openCamera() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (cameraId == null || !isSurfaceAvailable || isCameraOpen) return;
        startBackgroundThread();
        if (backgroundHandler == null) return;
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            CameraCharacteristics ch = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            previewSize = chooseOptimalPreviewSize(map.getOutputSizes(SurfaceHolder.class), cameraSurfaceView.getWidth(), cameraSurfaceView.getHeight());
            synchronized (cameraOpenCloseLock) {
                if (!isCameraOpen) {
                    manager.openCamera(cameraId, cameraStateCallback, backgroundHandler);
                    isCameraOpen = true;
                }
            }
        } catch (Exception e) {
            isCameraOpen = false;
            runOnUiThread(() -> Toast.makeText(this, "Open cam fail", Toast.LENGTH_SHORT).show());
            Log.e(TAG, "Open cam err", e);
        }
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            synchronized (cameraOpenCloseLock) {
                cameraDevice = camera;
                isCameraOpen = true;
            }
            startCameraPreview();
        }
        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            closeCamera();
        }
        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Cam Err: " + error, Toast.LENGTH_LONG).show());
            closeCamera();
        }
    };

    private Size[] getPreviewSizes() {
        if (cameraId == null) return new Size[]{new Size(1280, 720)};
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            return map != null ? map.getOutputSizes(SurfaceHolder.class) : new Size[]{new Size(1280, 720)};
        } catch (Exception e) {
            Log.e(TAG, "Error getting preview sizes", e);
            return new Size[]{new Size(1280, 720)};
        }
    }

    private Size chooseOptimalPreviewSize(Size[] choices, int w, int h) {
        if (choices == null || choices.length == 0) return new Size(1280, 720);
        double targetRatio = (w > 0 && h > 0) ? (double) w / h : (16.0 / 9.0);
        Size opt = null;
        double minDiff = Double.MAX_VALUE;
        int maxArea = 1920 * 1080;
        List<Size> suitable = new ArrayList<>();
        for (Size s : choices) if ((long)s.getWidth() * s.getHeight() <= maxArea) suitable.add(s);
        if (suitable.isEmpty()) return Collections.min(Arrays.asList(choices), Comparator.comparingLong(s -> (long)s.getWidth() * s.getHeight()));
        for (Size s : suitable) {
            double ratio = (double) s.getWidth() / s.getHeight();
            double diff = Math.abs(ratio - targetRatio);
            if (diff < minDiff) {
                minDiff = diff;
                opt = s;
            } else if (diff == minDiff && opt != null && (long)s.getWidth() * s.getHeight() > (long)opt.getWidth() * opt.getHeight()) {
                opt = s;
            }
        }
        if (opt == null) opt = Collections.max(suitable, Comparator.comparingLong(s -> (long)s.getWidth() * s.getHeight()));
        Log.d(TAG, "Chosen preview size: " + opt);
        return opt;
    }

    private void startCameraPreview() {
        synchronized (cameraOpenCloseLock) {
            if (cameraDevice == null || !isSurfaceAvailable || !isCameraOpen || backgroundHandler == null) {
                Log.w(TAG, "Cannot start preview - conditions not met.");
                return;
            }
            try {
                closeCameraPreviewSession();
                if (previewSize == null) {
                    previewSize = chooseOptimalPreviewSize(getPreviewSizes(), cameraSurfaceView.getWidth(), cameraSurfaceView.getHeight());
                    if (previewSize == null) {
                        Log.e(TAG, "Failed to choose preview size!");
                        return;
                    }
                }

                Surface surface = cameraSurfaceView.getHolder().getSurface();
                if (!surface.isValid()) {
                    Log.e(TAG, "Preview surface is invalid!");
                    return;
                }

                runOnUiThread(() -> {
                    SurfaceHolder holder = cameraSurfaceView.getHolder();
                    if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
                        try {
                            holder.setFixedSize(previewSize.getWidth(), previewSize.getHeight());
                            Log.d(TAG, "Set SurfaceHolder fixed size for preview: " + previewSize);
                        } catch (Exception e) {
                            Log.e(TAG, "Error setting fixed size for SurfaceHolder", e);
                        }
                    } else {
                        Log.w(TAG, "SurfaceHolder or Surface invalid when setting fixed size.");
                    }
                });

                previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                previewRequestBuilder.addTarget(surface);
                cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        synchronized (cameraOpenCloseLock) {
                            if (cameraDevice == null || !isCameraOpen) {
                                session.close();
                                return;
                            }
                            cameraCaptureSession = session;
                            try {
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                            } catch (Exception e) {
                                Log.e(TAG, "Preview repeat err", e);
                            }
                        }
                    }
                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Preview cfg fail", Toast.LENGTH_LONG).show());
                    }
                }, backgroundHandler);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Preview start err", Toast.LENGTH_SHORT).show());
                Log.e(TAG, "Preview start exception", e);
            }
        }
    }

    private void closeCameraPreviewSession() {
        synchronized (cameraOpenCloseLock) {
            if (cameraCaptureSession != null) {
                try {
                    cameraCaptureSession.close();
                } catch (Exception e) {}
                finally {
                    cameraCaptureSession = null;
                }
            }
        }
    }

    private void closeCamera() {
        try {
            synchronized(cameraOpenCloseLock) {
                closeCameraPreviewSession();
                if (cameraDevice != null) {
                    cameraDevice.close();
                    cameraDevice = null;
                }
                isCameraOpen = false;
            }
        } catch(Exception e) {
            Log.e(TAG, "Cam close err", e);
        } finally {
            stopBackgroundThread();
        }
    }

    private void switchCamera() {
        if (cameraIds == null || cameraIds.length < 2) {
            Toast.makeText(this, "1 cam", Toast.LENGTH_SHORT).show();
            return;
        }
        closeCamera();
        currentCameraIndex = (currentCameraIndex + 1) % cameraIds.length;
        cameraId = cameraIds[currentCameraIndex];
        previewSize = null;
        openCamera();
    }

    // --- Image Picking ---
    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        checkPermissionsAndSetupCamera();
        try {
            startActivityForResult(i, PICK_IMAGE_REQUEST);
        } catch (Exception e) {
            Toast.makeText(this, "No gallery", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK_IMAGE_REQUEST && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) loadImageFromUri(uri);
            else Toast.makeText(this, "No URI", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadImageFromUri(Uri uri) {
        try {
            new Thread(() -> {
                Bitmap bmp = null;
                try (InputStream is = getContentResolver().openInputStream(uri)) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(is, null, opts);
                    int inWidth = opts.outWidth, inHeight = opts.outHeight;
                    int reqWidth = 1080, reqHeight = 1920;
                    opts.inSampleSize = calculateInSampleSize(inWidth, inHeight, reqWidth, reqHeight);
                    opts.inJustDecodeBounds = false;
                    try (InputStream is2 = getContentResolver().openInputStream(uri)) {
                        bmp = BitmapFactory.decodeStream(is2, null, opts);
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "Load err", Toast.LENGTH_SHORT).show());
                }
                final Bitmap finalBmp = bmp;
                runOnUiThread(() -> {
                    if (finalBmp != null) {
                        recycleBitmaps();
                        originalBitmap = finalBmp;
                        if (isPencilMode) {
                            isPencilMode = false;
                            pencilModeSwitch.setChecked(false);
                            layerSelectButton.setVisibility(View.GONE);
                        }
                        layerVisibility = new boolean[PENCIL_HARDNESS.length];
                        Arrays.fill(layerVisibility, true);
                        resetTransformationsAndFit();
                        updateImageDisplay();
                    } else Toast.makeText(this, "Load fail", Toast.LENGTH_SHORT).show();
                });
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "Load thread start err", e);
        }
    }

    private int calculateInSampleSize(int width, int height, int reqWidth, int reqHeight) {
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    // --- Image Transformation & Display ---
    private void resetTransformationsAndFit() {
        matrix.reset();
        if (originalBitmap == null || originalBitmap.isRecycled() || imageView.getWidth() == 0 || imageView.getHeight() == 0) {
            if (imageView != null) runOnUiThread(() -> imageView.setImageMatrix(matrix));
            return;
        }
        float viewWidth = imageView.getWidth();
        float viewHeight = imageView.getHeight();
        float bitmapWidth = originalBitmap.getWidth();
        float bitmapHeight = originalBitmap.getHeight();
        float scale = Math.min(viewWidth / bitmapWidth, viewHeight / bitmapHeight);
        float dx = (viewWidth - bitmapWidth * scale) / 2f;
        float dy = (viewHeight - bitmapHeight * scale) / 2f;
        matrix.setScale(scale, scale); // Maintain aspect ratio
        matrix.postTranslate(dx, dy);
        Log.d(TAG, "Reset & fit. Scale: " + scale + ", Offset: (" + dx + ", " + dy + ")");
        applyTransformations();
    }

    // --- Pencil Effect Logic ---
    private void processPencilEffect() {
        if (originalBitmap == null || originalBitmap.isRecycled()) return;
        new Thread(() -> {
            recyclePencilBitmaps();
            Bitmap gray = null;
            Bitmap[] layers = new Bitmap[PENCIL_HARDNESS.length];
            boolean ok = false;
            try {
                int w = originalBitmap.getWidth(), h = originalBitmap.getHeight();
                gray = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas cg = new Canvas(gray);
                Paint pg = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                ColorMatrix cm = new ColorMatrix();
                cm.setSaturation(0);
                pg.setColorFilter(new ColorMatrixColorFilter(cm));
                cg.drawBitmap(originalBitmap, 0, 0, pg);
                int[] pix = new int[w * h];
                gray.getPixels(pix, 0, w, 0, 0, w, h);
                int[][] lpix = new int[layers.length][w * h];
                for (int i = 0; i < layers.length; i++) Arrays.fill(lpix[i], Color.TRANSPARENT);
                for (int i = 0; i < pix.length; i++) {
                    int layerIdx = getLayerIndex(Color.red(pix[i]));
                    if (layerIdx >= 0 && layerIdx < layers.length) lpix[layerIdx][i] = pix[i];
                }
                pix = null;
                gray.recycle();
                gray = null;
                for (int i = 0; i < layers.length; i++) {
                    layers[i] = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    layers[i].setPixels(lpix[i], 0, w, 0, 0, w, h);
                    lpix[i] = null;
                }
                lpix = null;
                ok = true;
            } catch (OutOfMemoryError e) {
                if (gray != null && !gray.isRecycled()) gray.recycle();
                for (Bitmap b : layers) if (b != null && !b.isRecycled()) b.recycle();
                runOnUiThread(() -> Toast.makeText(this, "OOM Pencil", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Pencil Err", Toast.LENGTH_SHORT).show());
            }
            final boolean finalOk = ok;
            final Bitmap[] finalLayers = layers;
            runOnUiThread(() -> {
                if (finalOk) {
                    pencilBitmap = null;
                    layerBitmaps = finalLayers;
                } else {
                    isPencilMode = false;
                    pencilModeSwitch.setChecked(false);
                    layerSelectButton.setVisibility(View.GONE);
                    layerBitmaps = null;
                }
                updateImageDisplay();
            });
        }).start();
    }

    private int getLayerIndex(int grayValue) {
        int numLayers = PENCIL_HARDNESS.length;
        if (numLayers <= 0) return -1;
        int rawIndex = (int) (((float)grayValue / 256.0f) * numLayers);
        rawIndex = Math.max(0, Math.min(rawIndex, numLayers - 1));
        int invertedIndex = (numLayers - 1) - rawIndex;
        return invertedIndex;
    }

    private void recyclePencilBitmaps() {
        if (pencilBitmap != null && !pencilBitmap.isRecycled()) pencilBitmap.recycle();
        pencilBitmap = null;
        if (layerBitmaps != null) {
            for (Bitmap b : layerBitmaps) if (b != null && !b.isRecycled()) b.recycle();
            layerBitmaps = null;
        }
    }

    // --- Layer Selection Dialog ---
    private void showLayerSelectionDialog() {
        Dialog d = new Dialog(this);
        d.setContentView(R.layout.dialog_layer_selection);
        d.setTitle(R.string.layer_selection_title);
        RecyclerView rv = d.findViewById(R.id.recyclerView); // Updated ID
        if (rv == null) return;
        rv.setLayoutManager(new LinearLayoutManager(this));
        LayerAdapter a = new LayerAdapter(PENCIL_HARDNESS, layerVisibility, this);
        rv.setAdapter(a);
        d.show();
    }

    @Override
    public void onLayerVisibilityChanged(int p, boolean isVisible) {
        if (p >= 0 && p < layerVisibility.length) {
            layerVisibility[p] = isVisible;
            updateImageDisplay();
        }
    }

    // --- Save/Load Parameters ---
    private float getMatrixScale(Matrix mat) {
        float[] v = new float[9];
        mat.getValues(v);
        float sx = v[Matrix.MSCALE_X], sy = v[Matrix.MSKEW_Y];
        return (float) Math.sqrt(sx * sx + sy * sy);
    }

    private void saveParameters() {
        try {
            File f = new File(getFilesDir(), "parameters.dat");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                float[] mv = new float[9];
                matrix.getValues(mv);
                fos.write(("matrix=" + mv[0]+","+mv[1]+","+mv[2]+","+mv[3]+","+mv[4]+","+mv[5]+","+mv[6]+","+mv[7]+","+mv[8] + "\n").getBytes());
                fos.write(("isPencilMode=" + isPencilMode + "\n").getBytes());
                fos.write(("isImageVisible=" + isImageVisible + "\n").getBytes());
                fos.write(("controlsVisible=" + controlsVisibilityCheckbox.isChecked() + "\n").getBytes());
                fos.write(("transparency=" + transparencySeekBar.getProgress() + "\n").getBytes());
                StringBuilder ls = new StringBuilder("layerVisibility=");
                for (int i = 0; i < layerVisibility.length; i++) {
                    ls.append(layerVisibility[i]);
                    if (i < layerVisibility.length - 1) ls.append(",");
                }
                fos.write((ls.toString() + "\n").getBytes());
                Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            Toast.makeText(this, "Save Err", Toast.LENGTH_LONG).show();
        }
    }

    private void loadParameters() {
        try {
            File f = new File(getFilesDir(), "parameters.dat");
            if (!f.exists()) {
                Toast.makeText(this, "No params", Toast.LENGTH_SHORT).show();
                return;
            }
            try (FileInputStream fis = new FileInputStream(f); java.util.Scanner sc = new java.util.Scanner(fis)) {
                boolean loadedPencil = isPencilMode;
                while (sc.hasNextLine()) {
                    String l = sc.nextLine();
                    String[] p = l.split("=", 2);
                    if (p.length != 2) continue;
                    String k = p[0], v = p[1];
                    try {
                        switch (k) {
                            case "matrix":
                                String[] mvs = v.split(",");
                                if (mvs.length == 9) {
                                    float[] vals = new float[9];
                                    for (int i = 0; i < 9; i++) vals[i] = Float.parseFloat(mvs[i]);
                                    matrix.setValues(vals);
                                }
                                break;
                            case "isPencilMode":
                                isPencilMode = Boolean.parseBoolean(v);
                                break;
                            case "isImageVisible":
                                isImageVisible = Boolean.parseBoolean(v);
                                break;
                            case "controlsVisible":
                                controlsVisibilityCheckbox.setChecked(Boolean.parseBoolean(v));
                                break;
                            case "transparency":
                                transparencySeekBar.setProgress(Integer.parseInt(v));
                                break;
                            case "layerVisibility":
                                String[] visVals = v.split(",");
                                for (int i = 0; i < layerVisibility.length && i < visVals.length; i++)
                                    layerVisibility[i] = Boolean.parseBoolean(visVals[i]);
                                break;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Parse err", e);
                    }
                }
                pencilModeSwitch.setChecked(isPencilMode);
                hideImageCheckbox.setChecked(!isImageVisible);
                updateControlsVisibility(controlsVisibilityCheckbox.isChecked());
                applyTransformations();
                if (isPencilMode && originalBitmap != null) {
                    if (!loadedPencil) processPencilEffect();
                    else updateImageDisplay();
                } else updateImageDisplay();
                Toast.makeText(this, "Loaded", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Load Err", Toast.LENGTH_LONG).show();
        }
    }

    // --- Activity Lifecycle ---
    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
        if (isSurfaceAvailable && ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (!isCameraOpen) openCamera();
            else startCameraPreview();
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        closeCamera();
        recycleBitmaps();
        super.onDestroy();
    }

    private void recycleBitmaps() {
        if (originalBitmap != null && !originalBitmap.isRecycled()) originalBitmap.recycle();
        originalBitmap = null;
        recyclePencilBitmaps();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        imageView.postDelayed(this::resetTransformationsAndFit, 100);
    }
} // End MainActivity
