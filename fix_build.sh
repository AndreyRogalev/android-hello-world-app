#!/bin/bash

# Убедимся, что мы в корневой директории проекта
PROJECT_DIR="/root/android-hello-world-app"
cd $PROJECT_DIR || { echo "Директория проекта не найдена!"; exit 1; }

# Создание или обновление dialog_layer_selection.xml
echo "Создание/обновление dialog_layer_selection.xml..."
mkdir -p app/src/main/res/layout
cat > app/src/main/res/layout/dialog_layer_selection.xml << 'EOL'
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="16dp">

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/layerRecyclerView"
        android:layout_width="match_parent"
        android:layout_height="wrap_content" />

</LinearLayout>
EOL

# Создание или обновление strings.xml
echo "Создание/обновление strings.xml..."
mkdir -p app/src/main/res/values
cat > app/src/main/res/values/strings.xml << 'EOL'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">HelloWorld</string>
    <string name="layer_selection_title">Select Pencil Layers</string>
</resources>
EOL

# Замена MainActivity.java (без OpenCV)
echo "Замена MainActivity.java..."
cat > app/src/main/java/com/example/helloworld/MainActivity.java << 'EOL'
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
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
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
import android.view.ViewGroup;
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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

public class MainActivity extends AppCompatActivity implements LayerAdapter.OnLayerVisibilityChangedListener {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 100;
    private static final int REQUEST_STORAGE_PERMISSION = 101;
    private static final int PICK_IMAGE_REQUEST = 1;

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
    private Button captureButton;

    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private String cameraId;
    private Size previewSize;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private final Object cameraOpenCloseLock = new Object();
    private volatile boolean isSurfaceAvailable = false;
    private volatile boolean isCameraPendingOpen = false;
    private volatile boolean isCameraOpen = false;

    private Bitmap originalBitmap;
    private Bitmap pencilBitmap;
    private Bitmap[] layerBitmaps;
    private boolean[] layerVisibility;
    private Matrix matrix = new Matrix();
    private float scaleFactor = 1.0f;
    private float rotationAngle = 0.0f;
    private boolean isPencilMode = false;
    private boolean isImageVisible = true;
    private ScaleGestureDetector scaleGestureDetector;
    private float lastTouchX, lastTouchY;
    private boolean isDragging = false;

    private String[] cameraIds;
    private int currentCameraIndex = 0;

    private ImageReader imageReader;
    private static final int CAPTURE_WIDTH = 1280;
    private static final int CAPTURE_HEIGHT = 720;

    private static final String[] PENCIL_HARDNESS = {
            "9H", "8H", "7H", "6H", "5H", "4H", "3H", "2H", "H", "F",
            "HB", "B", "2B", "3B", "4B", "5B", "6B", "7B", "8B", "9B"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

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
        captureButton = findViewById(R.id.captureButton);

        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scaleFactor *= detector.getScaleFactor();
                scaleFactor = Math.max(0.1f, Math.min(scaleFactor, 5.0f));
                matrix.postScale(detector.getScaleFactor(), detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                applyTransformations();
                return true;
            }
        });

        imageView.setOnTouchListener((v, event) -> {
            scaleGestureDetector.onTouchEvent(event);
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    isDragging = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (isDragging) {
                        float dx = event.getX() - lastTouchX;
                        float dy = event.getY() - lastTouchY;
                        matrix.postTranslate(dx, dy);
                        applyTransformations();
                        lastTouchX = event.getX();
                        lastTouchY = event.getY();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    isDragging = false;
                    break;
            }
            return true;
        });

        cameraSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                Log.d(TAG, "Surface created");
                isSurfaceAvailable = true;
                if (isCameraPendingOpen && !isCameraOpen) {
                    openCamera();
                    isCameraPendingOpen = false;
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                Log.d(TAG, "Surface changed: " + width + "x" + height);
                adjustSurfaceViewAspectRatioWithCropping(width, height);
                if (cameraDevice != null && isSurfaceAvailable) {
                    closeCameraPreviewSession();
                    previewSize = chooseOptimalPreviewSize(getPreviewSizes(), width, height);
                    createCameraPreviewSession();
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                Log.d(TAG, "Surface destroyed");
                isSurfaceAvailable = false;
                closeCamera();
            }
        });

        transparencySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setImageAlpha(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        pickImageButton.setOnClickListener(v -> pickImage());

        pencilModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isPencilMode = isChecked;
            layerSelectButton.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (isPencilMode) {
                processPencilEffect();
            }
            updateImageDisplay();
        });

        layerSelectButton.setOnClickListener(v -> showLayerSelectionDialog());

        controlsVisibilityCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            int visibility = isChecked ? View.VISIBLE : View.GONE;
            transparencySeekBar.setVisibility(visibility);
            pickImageButton.setVisibility(visibility);
            pencilModeSwitch.setVisibility(visibility);
            layerSelectButton.setVisibility(isPencilMode && isChecked ? View.VISIBLE : View.GONE);
            hideImageCheckbox.setVisibility(visibility);
            saveParametersButton.setVisibility(visibility);
            loadParametersButton.setVisibility(visibility);
            switchCameraButton.setVisibility(visibility);
            captureButton.setVisibility(visibility);
        });

        hideImageCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isImageVisible = !isChecked;
            updateImageDisplay();
        });

        saveParametersButton.setOnClickListener(v -> saveParameters());

        loadParametersButton.setOnClickListener(v -> loadParameters());

        switchCameraButton.setOnClickListener(v -> switchCamera());

        captureButton.setOnClickListener(v -> captureImage());

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        } else {
            isCameraPendingOpen = true;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE_PERMISSION);
        }

        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            cameraIds = manager.getCameraIdList();
            if (cameraIds.length > 0) {
                cameraId = cameraIds[0];
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error accessing camera list", e);
            Toast.makeText(this, "Cannot access cameras", Toast.LENGTH_LONG).show();
        }

        layerVisibility = new boolean[20];
        Arrays.fill(layerVisibility, true);
    }

    private void adjustSurfaceViewAspectRatioWithCropping(int width, int height) {
        if (previewSize == null) {
            Log.e(TAG, "Preview size is null, cannot adjust aspect ratio");
            return;
        }

        float previewRatio = (float) previewSize.getWidth() / previewSize.getHeight();
        float viewRatio = (float) width / height;

        cameraSurfaceView.setScaleX(1.0f);
        cameraSurfaceView.setScaleY(1.0f);

        float scaleX, scaleY;
        if (previewRatio > viewRatio) {
            scaleY = 1.0f;
            scaleX = previewRatio / viewRatio;
        } else {
            scaleX = 1.0f;
            scaleY = viewRatio / previewRatio;
        }

        cameraSurfaceView.setScaleX(scaleX);
        cameraSurfaceView.setScaleY(scaleY);

        cameraSurfaceView.setPivotX(width / 2f);
        cameraSurfaceView.setPivotY(height / 2f);

        ViewGroup.LayoutParams params = cameraSurfaceView.getLayoutParams();
        params.width = width;
        params.height = height;
        cameraSurfaceView.setLayoutParams(params);

        cameraSurfaceView.requestLayout();
        Log.d(TAG, "Adjusted SurfaceView: width=" + width + ", height=" + height +
                ", previewRatio=" + previewRatio + ", scaleX=" + scaleX + ", scaleY=" + scaleY);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (isSurfaceAvailable && !isCameraOpen) {
                    openCamera();
                } else {
                    isCameraPendingOpen = true;
                }
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show();
                finish();
            }
        } else if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted
            } else {
                Toast.makeText(this, "Storage permission is required", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = new HandlerThread("CameraBackground");
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());
        }
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping background thread", e);
            }
        }
    }

    private void openCamera() {
        if (!isSurfaceAvailable || isCameraOpen) {
            Log.d(TAG, "Surface not available or camera already open, setting pending open");
            isCameraPendingOpen = true;
            return;
        }

        startBackgroundThread();
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            if (cameraId == null) {
                cameraId = manager.getCameraIdList()[0];
            }
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Size[] previewSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(SurfaceHolder.class);
            previewSize = chooseOptimalPreviewSize(previewSizes, cameraSurfaceView.getWidth(), cameraSurfaceView.getHeight());

            imageReader = ImageReader.newInstance(CAPTURE_WIDTH, CAPTURE_HEIGHT, android.graphics.ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = reader.acquireLatestImage();
                if (image != null) {
                    Bitmap bitmap = imageToBitmap(image);
                    image.close();
                    if (bitmap != null) {
                        processCapturedImage(bitmap);
                    }
                }
            }, backgroundHandler);

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            synchronized (cameraOpenCloseLock) {
                isCameraOpen = true;
                manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(@NonNull CameraDevice camera) {
                        cameraDevice = camera;
                        if (isSurfaceAvailable) {
                            createCameraPreviewSession();
                        } else {
                            Log.d(TAG, "Surface not available after camera opened, closing camera");
                            closeCamera();
                        }
                        synchronized (cameraOpenCloseLock) {
                            cameraOpenCloseLock.notify();
                        }
                    }

                    @Override
                    public void onDisconnected(@NonNull CameraDevice camera) {
                        synchronized (cameraOpenCloseLock) {
                            cameraOpenCloseLock.notify();
                        }
                        camera.close();
                        cameraDevice = null;
                        isCameraOpen = false;
                    }

                    @Override
                    public void onError(@NonNull CameraDevice camera, int error) {
                        synchronized (cameraOpenCloseLock) {
                            cameraOpenCloseLock.notify();
                        }
                        camera.close();
                        cameraDevice = null;
                        isCameraOpen = false;
                        Toast.makeText(MainActivity.this, "Camera error: " + error, Toast.LENGTH_LONG).show();
                    }
                }, backgroundHandler);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Cannot access camera", e);
            Toast.makeText(this, "Cannot access camera", Toast.LENGTH_LONG).show();
            isCameraOpen = false;
        }
    }

    private Size[] getPreviewSizes() {
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            return characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(SurfaceHolder.class);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error getting preview sizes", e);
            return new Size[]{new Size(1280, 720)};
        }
    }

    private Size chooseOptimalPreviewSize(Size[] choices, int viewWidth, int viewHeight) {
        if (choices == null || choices.length == 0) {
            Log.e(TAG, "No preview sizes available, using default");
            return new Size(1280, 720);
        }

        double targetRatio = (viewWidth > 0 && viewHeight > 0) ? (double) viewWidth / viewHeight : 4.0 / 3.0;

        Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;
        int maxArea = 0;

        for (Size size : choices) {
            double ratio = (double) size.getWidth() / size.getHeight();
            int area = size.getWidth() * size.getHeight();
            double ratioDiff = Math.abs(ratio - targetRatio);
            if (ratioDiff < minDiff || (ratioDiff == minDiff && area > maxArea)) {
                optimalSize = size;
                minDiff = ratioDiff;
                maxArea = area;
            }
        }

        if (optimalSize == null) {
            optimalSize = choices[0];
        }

        Log.d(TAG, "Chosen preview size: " + optimalSize.getWidth() + "x" + optimalSize.getHeight() +
                ", targetRatio=" + targetRatio);
        return optimalSize;
    }

    private void createCameraPreviewSession() {
        if (!isSurfaceAvailable || cameraDevice == null || !isCameraOpen) {
            Log.d(TAG, "Cannot create preview session: Surface not available, cameraDevice is null, or camera is closed");
            return;
        }

        try {
            SurfaceHolder holder = cameraSurfaceView.getHolder();
            Surface surface = holder.getSurface();
            if (!surface.isValid()) {
                Log.d(TAG, "Surface is not valid, aborting preview session creation");
                return;
            }

            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            List<Surface> surfaces = new ArrayList<>();
            surfaces.add(surface);
            surfaces.add(imageReader.getSurface());

            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (cameraDevice == null || !isSurfaceAvailable || !isCameraOpen) {
                        Log.d(TAG, "Camera device closed, surface not available, or camera not open during session configuration");
                        session.close();
                        return;
                    }
                    cameraCaptureSession = session;
                    try {
                        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                        Log.d(TAG, "Camera preview session started");
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Error setting up camera preview", e);
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "Session already closed during setRepeatingRequest", e);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Toast.makeText(MainActivity.this, "Failed to configure camera preview", Toast.LENGTH_LONG).show();
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creating camera preview session", e);
        }
    }

    private void closeCameraPreviewSession() {
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }
    }

    private void closeCamera() {
        synchronized (cameraOpenCloseLock) {
            closeCameraPreviewSession();
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            isCameraOpen = false;
        }
        stopBackgroundThread();
    }

    private void switchCamera() {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            closeCamera();
            currentCameraIndex = (currentCameraIndex + 1) % cameraIds.length;
            cameraId = cameraIds[currentCameraIndex];
            if (isSurfaceAvailable && !isCameraOpen) {
                openCamera();
            } else {
                isCameraPendingOpen = true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error switching camera", e);
            Toast.makeText(this, "Error switching camera", Toast.LENGTH_LONG).show();
        }
    }

    private void captureImage() {
        if (cameraDevice == null || cameraCaptureSession == null) {
            Log.e(TAG, "Cannot capture image: camera not initialized");
            return;
        }

        try {
            CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            cameraCaptureSession.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    Log.d(TAG, "Image captured successfully");
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error capturing image", e);
            Toast.makeText(this, "Error capturing image", Toast.LENGTH_LONG).show();
        }
    }

    private Bitmap imageToBitmap(Image image) {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }

    private void processCapturedImage(Bitmap bitmap) {
        // Упрощённая обработка без OpenCV: просто сохраняем изображение
        runOnUiThread(() -> {
            if (originalBitmap != null && !originalBitmap.isRecycled()) {
                originalBitmap.recycle();
            }
            originalBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
            resetTransformationsAndFit();
            updateImageDisplay();
        });
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, PICK_IMAGE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri imageUri = data.getData();
            try {
                if (originalBitmap != null && !originalBitmap.isRecycled()) {
                    originalBitmap.recycle();
                }
                originalBitmap = MediaStore.Images.Media.getBitmap(getContentResolver(), imageUri);
                resetTransformationsAndFit();
                layerVisibility = new boolean[20];
                Arrays.fill(layerVisibility, true);
                if (isPencilMode) {
                    processPencilEffect();
                }
                updateImageDisplay();
            } catch (IOException e) {
                Log.e(TAG, "Error loading image", e);
                Toast.makeText(this, "Error loading image", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void resetTransformationsAndFit() {
        if (originalBitmap == null || imageView.getWidth() == 0 || imageView.getHeight() == 0) {
            matrix.reset();
            scaleFactor = 1.0f;
            rotationAngle = 0.0f;
            imageView.setImageMatrix(matrix);
            return;
        }

        matrix.reset();

        float viewWidth = imageView.getWidth();
        float viewHeight = imageView.getHeight();
        float bmpWidth = originalBitmap.getWidth();
        float bmpHeight = originalBitmap.getHeight();

        float scaleX = viewWidth / bmpWidth;
        float scaleY = viewHeight / bmpHeight;
        float initialScale = Math.min(scaleX, scaleY);

        float scaledBmpWidth = bmpWidth * initialScale;
        float scaledBmpHeight = bmpHeight * initialScale;
        float initialTranslateX = (viewWidth - scaledBmpWidth) / 2f;
        float initialTranslateY = (viewHeight - scaledBmpHeight) / 2f;

        matrix.postScale(initialScale, initialScale);
        matrix.postTranslate(initialTranslateX, initialTranslateY);

        imageView.post(() -> {
            imageView.setImageMatrix(matrix);
            imageView.invalidate();
            scaleFactor = initialScale;
            rotationAngle = 0.0f;
        });
    }

    private void applyTransformations() {
        imageView.setImageMatrix(matrix);
        imageView.invalidate();
        Log.d(TAG, "Transformations applied: scale=" + scaleFactor);
    }

    private void setImageAlpha(int progress) {
        float alpha = progress / 100.0f;
        imageView.setAlpha(alpha);
        imageView.invalidate();
        Log.d(TAG, "Image alpha set to: " + alpha);
    }

    private void processPencilEffect() {
        Log.d(TAG, "Processing pencil effect");
        if (originalBitmap == null) {
            Log.d(TAG, "Original bitmap is null, cannot process pencil effect");
            return;
        }

        if (pencilBitmap != null && !pencilBitmap.isRecycled()) {
            pencilBitmap.recycle();
            pencilBitmap = null;
        }
        if (layerBitmaps != null) {
            for (Bitmap layer : layerBitmaps) {
                if (layer != null && !layer.isRecycled()) {
                    layer.recycle();
                }
            }
            layerBitmaps = null;
        }

        try {
            pencilBitmap = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
            if (pencilBitmap == null) {
                Log.e(TAG, "Failed to create pencilBitmap");
                return;
            }
            Canvas canvas = new Canvas(pencilBitmap);
            Paint paint = new Paint();
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0); // Преобразование в чёрно-белое
            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
            paint.setColorFilter(filter);
            canvas.drawBitmap(originalBitmap, 0, 0, paint);

            layerBitmaps = new Bitmap[20];
            for (int i = 0; i < 20; i++) {
                layerBitmaps[i] = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
                if (layerBitmaps[i] == null) {
                    Log.e(TAG, "Failed to create layerBitmap[" + i + "]");
                    return;
                }
                layerBitmaps[i].eraseColor(Color.TRANSPARENT);
            }

            int[] pixels = new int[originalBitmap.getWidth() * originalBitmap.getHeight()];
            pencilBitmap.getPixels(pixels, 0, originalBitmap.getWidth(), 0, 0, originalBitmap.getWidth(), originalBitmap.getHeight());

            for (int i = 0; i < pixels.length; i++) {
                int gray = Color.red(pixels[i]); // Простое преобразование в градации серого
                int layerIndex = getLayerIndex(gray);
                if (layerIndex >= 0 && layerIndex < 20 && layerBitmaps[layerIndex] != null) {
                    layerBitmaps[layerIndex].setPixel(i % originalBitmap.getWidth(), i / originalBitmap.getWidth(), pixels[i]);
                }
            }
            Log.d(TAG, "Pencil effect processed successfully");
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "OutOfMemoryError in processPencilEffect", e);
            Toast.makeText(this, "Not enough memory for pencil effect", Toast.LENGTH_LONG).show();
            pencilBitmap = null;
            layerBitmaps = null;
        }
    }

    private int getLayerIndex(int grayValue) {
        return grayValue / (256 / 20);
    }

    private void updateImageDisplay() {
        Log.d(TAG, "updateImageDisplay: isPencilMode=" + isPencilMode + ", isImageVisible=" + isImageVisible);
        if (originalBitmap == null || !isImageVisible) {
            Log.d(TAG, "updateImageDisplay: originalBitmap is null or image is not visible");
            imageView.setImageBitmap(null);
            imageView.setVisibility(View.INVISIBLE);
            imageView.invalidate();
            return;
        }

        if (isPencilMode) {
            Log.d(TAG, "updateImageDisplay: Processing pencil mode");
            if (pencilBitmap == null || layerBitmaps == null) {
                processPencilEffect();
            }

            if (pencilBitmap == null || layerBitmaps == null) {
                Log.d(TAG, "updateImageDisplay: pencilBitmap or layerBitmaps is null");
                imageView.setImageBitmap(null);
                imageView.setVisibility(View.INVISIBLE);
                imageView.invalidate();
                return;
            }

            Bitmap resultBitmap = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
            if (resultBitmap == null) {
                Log.d(TAG, "updateImageDisplay: Failed to create resultBitmap");
                imageView.setImageBitmap(null);
                imageView.setVisibility(View.INVISIBLE);
                imageView.invalidate();
                return;
            }
            Canvas canvas = new Canvas(resultBitmap);
            canvas.drawColor(Color.TRANSPARENT);

            for (int i = 0; i < layerBitmaps.length; i++) {
                if (layerVisibility[i] && layerBitmaps[i] != null && !layerBitmaps[i].isRecycled()) {
                    canvas.drawBitmap(layerBitmaps[i], 0, 0, null);
                }
            }

            imageView.setImageBitmap(resultBitmap);
            setImageAlpha(transparencySeekBar.getProgress());
            imageView.setVisibility(View.VISIBLE);
            imageView.post(() -> {
                imageView.setImageMatrix(matrix);
                imageView.invalidate();
            });
            Log.d(TAG, "updateImageDisplay: Pencil mode applied");
        } else {
            Log.d(TAG, "updateImageDisplay: Displaying original bitmap");
            imageView.setImageBitmap(originalBitmap);
            setImageAlpha(transparencySeekBar.getProgress());
            imageView.setVisibility(View.VISIBLE);
            imageView.post(() -> {
                imageView.setImageMatrix(matrix);
                imageView.invalidate();
            });
            Log.d(TAG, "updateImageDisplay: Original bitmap displayed");
        }
    }

    private void showLayerSelectionDialog() {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_layer_selection);
        dialog.setTitle(R.string.layer_selection_title);

        RecyclerView recyclerView = dialog.findViewById(R.id.layerRecyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        LayerAdapter adapter = new LayerAdapter(PENCIL_HARDNESS, layerVisibility, this);
        recyclerView.setAdapter(adapter);

        dialog.show();
    }

    @Override
    public void onLayerVisibilityChanged(int position, boolean isVisible) {
        layerVisibility[position] = isVisible;
        updateImageDisplay();
    }

    private void saveParameters() {
        try {
            File file = new File(getFilesDir(), "parameters.dat");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(Float.toString(scaleFactor).getBytes());
            fos.write("\n".getBytes());
            fos.write(Float.toString(rotationAngle).getBytes());
            fos.write("\n".getBytes());
            float[] matrixValues = new float[9];
            matrix.getValues(matrixValues);
            for (float value : matrixValues) {
                fos.write(Float.toString(value).getBytes());
                fos.write(" ".getBytes());
            }
            fos.write("\n".getBytes());
            fos.write(String.valueOf(isPencilMode).getBytes());
            fos.write("\n".getBytes());
            for (boolean visible : layerVisibility) {
                fos.write(String.valueOf(visible).getBytes());
                fos.write(" ".getBytes());
            }
            fos.close();
            Toast.makeText(this, "Parameters saved", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e(TAG, "Error saving parameters", e);
            Toast.makeText(this, "Error saving parameters", Toast.LENGTH_LONG).show();
        }
    }

    private void loadParameters() {
        try {
            File file = new File(getFilesDir(), "parameters.dat");
            if (!file.exists()) {
                Toast.makeText(this, "No saved parameters found", Toast.LENGTH_SHORT).show();
                return;
            }
            FileInputStream fis = new FileInputStream(file);
            byte[] buffer = new byte[(int) file.length()];
            fis.read(buffer);
            fis.close();
            String[] lines = new String(buffer).split("\n");
            if (lines.length < 4) {
                Toast.makeText(this, "Invalid parameters file", Toast.LENGTH_LONG).show();
                return;
            }
            scaleFactor = Float.parseFloat(lines[0]);
            rotationAngle = Float.parseFloat(lines[1]);
            String[] matrixValues = lines[2].split(" ");
            float[] values = new float[9];
            for (int i = 0; i < 9; i++) {
                values[i] = Float.parseFloat(matrixValues[i]);
            }
            matrix.setValues(values);
            isPencilMode = Boolean.parseBoolean(lines[3]);
            pencilModeSwitch.setChecked(isPencilMode);
            String[] visibilityValues = lines[4].split(" ");
            for (int i = 0; i < layerVisibility.length; i++) {
                layerVisibility[i] = Boolean.parseBoolean(visibilityValues[i]);
            }
            applyTransformations();
            updateImageDisplay();
            Toast.makeText(this, "Parameters loaded", Toast.LENGTH_SHORT).show();
        } catch (IOException | NumberFormatException e) {
            Log.e(TAG, "Error loading parameters", e);
            Toast.makeText(this, "Error loading parameters", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isSurfaceAvailable && !isCameraOpen) {
            openCamera();
        } else {
            isCameraPendingOpen = true;
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (originalBitmap != null && !originalBitmap.isRecycled()) {
            originalBitmap.recycle();
            originalBitmap = null;
        }
        if (pencilBitmap != null && !pencilBitmap.isRecycled()) {
            pencilBitmap.recycle();
            pencilBitmap = null;
        }
        if (layerBitmaps != null) {
            for (Bitmap layer : layerBitmaps) {
                if (layer != null && !layer.isRecycled()) {
                    layer.recycle();
                }
            }
            layerBitmaps = null;
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        resetTransformationsAndFit();
        updateImageDisplay();
    }
}
EOL

# Очистка проекта
echo "Очистка проекта..."
./gradlew clean

# Пересборка проекта
echo "Пересборка проекта..."
./gradlew assembleDebug

echo "Исправление завершено!"
