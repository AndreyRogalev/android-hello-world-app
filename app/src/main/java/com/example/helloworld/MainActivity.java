package com.example.helloworld;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
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
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
// import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback, LayerAdapter.OnLayerVisibilityChangedListener {

    private static final String TAG = "MainActivity";
    private static final int CAMERA_PERMISSION_CODE = 100;
    private static final int STORAGE_PERMISSION_CODE = 101;
    private static final int WRITE_STORAGE_PERMISSION_CODE = 102;

    // Ключи для сохранения состояния
    private static final String KEY_IMAGE_URI = "imageUri";
    private static final String KEY_SCALE_FACTOR = "scaleFactor";
    private static final String KEY_ROTATION_ANGLE = "rotationAngle";
    private static final String KEY_MATRIX_VALUES = "matrixValues";
    private static final String KEY_CONTROLS_VISIBLE = "controlsVisible";
    private static final String KEY_IMAGE_VISIBLE = "imageVisible";
    private static final String KEY_PENCIL_MODE = "isPencilMode";
    private static final String KEY_LAYER_VISIBILITY = "layerVisibility";
    private static final String KEY_CURRENT_CAMERA_ID = "currentCameraId";


    // UI элементы
    private ImageView imageView;
    private SeekBar transparencySeekBar;
    private Button pickImageButton;
    private SurfaceView cameraSurfaceView;
    private SurfaceHolder cameraSurfaceHolder;
    private CheckBox controlsVisibilityCheckbox;
    private Switch pencilModeSwitch;
    private Button layerSelectButton;
    private CheckBox hideImageCheckbox;
    private Button saveParametersButton;
    private Button loadParametersButton;
    private Button switchCameraButton;

    // Camera2 API
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private String[] cameraIds;
    private String currentCameraId;
    private List<String> rearCameraIds = new ArrayList<>();
    private int currentRearCameraIndex = 0;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private final Semaphore cameraOpenCloseLock = new Semaphore(1, true);
    private volatile boolean isSurfaceAvailable = false;
    private volatile boolean isCameraOpen = false;
    private Size previewSize;

    // Манипуляции с изображением
    private Bitmap originalBitmap = null;
    private Matrix matrix = new Matrix();
    private float scaleFactor = 1.0f;
    private float rotationAngle = 0.0f;
    private Uri currentImageUri;

    // Карандашный режим
    private boolean isPencilMode = false;
    private Bitmap pencilBitmap;
    private Bitmap[] layerBitmaps;
    private boolean[] layerVisibility = new boolean[20];
    private static final String[] PENCIL_HARDNESS = {
            "9H", "8H", "7H", "6H", "5H", "4H", "3H", "2H", "H", "F",
            "HB", "B", "2B", "3B", "4B", "5B", "6B", "7B", "8B", "9B"
    };

    // Управление видимостью изображения
    private boolean isImageVisible = true;

    // Распознавание жестов
    private ScaleGestureDetector scaleGestureDetector;
    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    private static final int ROTATE = 3;
    private int touchMode = NONE;
    private float lastEventX, lastEventY;
    private PointF startPoint = new PointF();
    private PointF midPoint = new PointF();
    private float initialAngle = 0f;

    // Activity Result API
    private ActivityResultLauncher<Intent> imagePickerLauncher;
    private ActivityResultLauncher<Intent> saveFileLauncher;
    private ActivityResultLauncher<Intent> loadFileLauncher;

    private final ExecutorService imageLoadExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        hideSystemUI();

        initializeUI();
        setupListeners(); // Инициализация лаунчеров теперь здесь

        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());
        imageView.setOnTouchListener(new TouchAndGestureListener());

        cameraSurfaceHolder = cameraSurfaceView.getHolder();
        cameraSurfaceHolder.addCallback(this);

        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            Log.e(TAG, "CameraManager is null! Cannot proceed.");
            Toast.makeText(this, "Camera service not available.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setupCameraSelector(); // Вызов восстановлен

        checkAndRequestPermissions();

        if (savedInstanceState != null) {
            restoreInstanceState(savedInstanceState);
        } else {
            Arrays.fill(layerVisibility, true);
            updateControlsVisibility(controlsVisibilityCheckbox.isChecked());
            updateImageDisplay();
        }
    }

    // --- Инициализация UI и Слушателей ---
    private void initializeUI() {
        imageView = findViewById(R.id.imageView);
        transparencySeekBar = findViewById(R.id.transparencySeekBar);
        pickImageButton = findViewById(R.id.pickImageButton);
        cameraSurfaceView = findViewById(R.id.cameraSurfaceView);
        controlsVisibilityCheckbox = findViewById(R.id.controlsVisibilityCheckbox);
        pencilModeSwitch = findViewById(R.id.pencilModeSwitch);
        layerSelectButton = findViewById(R.id.layerSelectButton);
        hideImageCheckbox = findViewById(R.id.hideImageCheckbox);
        saveParametersButton = findViewById(R.id.saveParametersButton);
        loadParametersButton = findViewById(R.id.loadParametersButton);
        switchCameraButton = findViewById(R.id.switchCameraButton);
        imageView.setScaleType(ImageView.ScaleType.MATRIX);
    }

    private void setupListeners() {
        // Инициализация Activity Result Launcher'ов (ВОССТАНОВЛЕНА)
        imagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri selectedImageUri = result.getData().getData();
                        if (selectedImageUri != null) {
                            currentImageUri = selectedImageUri;
                            Log.d(TAG, "Image selected: " + currentImageUri);
                            loadImage(currentImageUri);
                        }
                    } else {
                        Log.w(TAG, "Image selection cancelled or failed. ResultCode: " + result.getResultCode());
                    }
                }
        );

        saveFileLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            saveParametersToFile(uri);
                        }
                    }
                }
        );

        loadFileLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            loadParametersFromFile(uri);
                        }
                    }
                }
        );

        // Слушатели кнопок и контролов
        pickImageButton.setOnClickListener(v -> checkPermissionAndPickImage());
        transparencySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setImageAlpha(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        controlsVisibilityCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> updateControlsVisibility(isChecked));
        pencilModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isPencilMode = isChecked;
            layerSelectButton.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (isPencilMode && originalBitmap != null && !originalBitmap.isRecycled()) {
                imageLoadExecutor.submit(this::processPencilEffect);
            } else {
                recyclePencilBitmaps();
                updateImageDisplay();
            }
        });
        layerSelectButton.setOnClickListener(v -> showLayerSelectionDialog());
        hideImageCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isImageVisible = !isChecked;
            updateImageDisplay();
        });
        saveParametersButton.setOnClickListener(v -> checkPermissionAndSaveParameters());
        loadParametersButton.setOnClickListener(v -> checkPermissionAndLoadParameters());
        switchCameraButton.setOnClickListener(v -> switchCamera());
    }

    // --- Управление UI ---
    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                // Fallback
                //noinspection deprecation
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN);
            }
        } else {
            //noinspection deprecation
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
    }

    private void updateControlsVisibility(boolean show) {
        int visibility = show ? View.VISIBLE : View.GONE;
        pickImageButton.setVisibility(visibility);
        transparencySeekBar.setVisibility(visibility);
        pencilModeSwitch.setVisibility(visibility);
        layerSelectButton.setVisibility(show && isPencilMode ? View.VISIBLE : View.GONE);
        saveParametersButton.setVisibility(visibility);
        loadParametersButton.setVisibility(visibility);
        hideImageCheckbox.setVisibility(visibility);
        switchCameraButton.setVisibility(show && rearCameraIds != null && rearCameraIds.size() > 1 ? View.VISIBLE : View.GONE);
        controlsVisibilityCheckbox.setVisibility(View.VISIBLE);
        Log.d(TAG, "Controls visibility updated: " + (show ? "VISIBLE" : "GONE"));
    }

    // --- Управление Разрешениями ---
    private void checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CAMERA);
        }
        String storagePermission = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            storagePermission = Manifest.permission.READ_MEDIA_IMAGES;
        } else {
            storagePermission = Manifest.permission.READ_EXTERNAL_STORAGE;
        }
        if (storagePermission != null && ContextCompat.checkSelfPermission(this, storagePermission) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(storagePermission);
        }

        if (!permissionsNeeded.isEmpty()) {
            Log.d(TAG, "Requesting permissions: " + permissionsNeeded);
            ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), CAMERA_PERMISSION_CODE);
        } else {
            Log.d(TAG, "All necessary permissions already granted.");
            if (cameraIds == null) setupCameraSelector();
            if (!isCameraOpen && cameraSurfaceHolder != null && cameraSurfaceHolder.getSurface() != null && cameraSurfaceHolder.getSurface().isValid()) {
                openCamera();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean storageGranted = false;
        String storagePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(this, storagePermission) == PackageManager.PERMISSION_GRANTED) {
            storageGranted = true;
        }

        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (cameraGranted) {
                Log.d(TAG, "Camera permission granted via request result.");
                if (cameraIds == null) setupCameraSelector();
                openCamera();
            } else {
                Toast.makeText(this, "Camera Permission is Required", Toast.LENGTH_SHORT).show();
            }
            if (!storageGranted) {
                Log.d(TAG, "Storage permission still denied after request.");
            }
        }
        // Можно добавить обработку других кодов запросов, если они есть
    }

    // --- Логика Камеры (Camera2 API) ---

    // Метод setupCameraSelector (Восстановлен)
    private void setupCameraSelector() {
        if (cameraManager == null) return;
        try {
            cameraIds = cameraManager.getCameraIdList();
            rearCameraIds.clear();
            String firstBackCameraId = null;

            for (String id : cameraIds) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    rearCameraIds.add(id);
                    if (firstBackCameraId == null) firstBackCameraId = id;
                    logCameraCharacteristics(id);
                }
            }

            if (!rearCameraIds.isEmpty()) {
                currentCameraId = firstBackCameraId;
                currentRearCameraIndex = rearCameraIds.indexOf(currentCameraId);
                Log.d(TAG, "Found " + rearCameraIds.size() + " rear cameras. Defaulting to: " + currentCameraId);
            } else if (cameraIds.length > 0) {
                currentCameraId = cameraIds[0];
                currentRearCameraIndex = -1;
                Log.w(TAG, "No rear cameras found, defaulting to first available camera: " + currentCameraId);
            } else {
                Log.e(TAG, "No cameras available.");
                Toast.makeText(this, "No cameras found on this device", Toast.LENGTH_LONG).show();
                currentCameraId = null;
            }

            // Обновляем видимость кнопки переключения
            runOnUiThread(() -> switchCameraButton.setVisibility(rearCameraIds != null && rearCameraIds.size() > 1 ? View.VISIBLE : View.GONE));

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error accessing camera characteristics during setup", e);
            Toast.makeText(this, "Cannot access cameras: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }


    private void logCameraCharacteristics(String camId) {
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(camId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] outputSizes = map.getOutputSizes(SurfaceHolder.class);
                Log.d(TAG, "Camera " + camId + ": Preview Sizes (SurfaceHolder): " + Arrays.toString(outputSizes));
            } else {
                Log.w(TAG, "Camera " + camId + ": StreamConfigurationMap is null");
            }
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
            Log.d(TAG, "Camera " + camId + ": Facing: " + (facing == null ? "Unknown" : (facing == CameraCharacteristics.LENS_FACING_BACK ? "BACK" : "FRONT")));
            if (focalLengths != null && focalLengths.length > 0) {
                Log.d(TAG, "Camera " + camId + ": Focal Lengths: " + Arrays.toString(focalLengths));
            } else {
                Log.d(TAG, "Camera " + camId + ": Focal Lengths: Not available");
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error logging camera characteristics for camera " + camId, e);
        }
    }

    // Метод startBackgroundThread (Восстановлен)
    private void startBackgroundThread() {
        if (backgroundThread == null || !backgroundThread.isAlive()) {
            stopBackgroundThread();
            backgroundThread = new HandlerThread("CameraBackground");
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());
            Log.d(TAG, "Background thread started");
        }
    }

    // Метод stopBackgroundThread (Восстановлен)
    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join(500);
                if (backgroundThread.isAlive()) {
                    Log.w(TAG, "Background thread did not stop in time.");
                }
                backgroundThread = null;
                backgroundHandler = null;
                Log.d(TAG, "Background thread stopped");
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping background thread", e);
                Thread.currentThread().interrupt();
            }
        }
    }

    private void openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { Log.w(TAG, "openCamera called without permission."); checkAndRequestPermissions(); return; }
        if (currentCameraId == null) { Log.e(TAG, "Cannot open camera, no valid camera ID selected."); return; }
        if (cameraManager == null) { Log.e(TAG, "Cannot open camera, CameraManager is null."); return; }
        if (isCameraOpen) { Log.d(TAG, "Camera already open."); return; }

        startBackgroundThread();

        cameraExecutor.submit(() -> {
            try {
                if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) { Log.e(TAG, "Time out waiting to lock camera opening."); return; }
                try {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { Log.e(TAG, "Permission lost before opening camera."); cameraOpenCloseLock.release(); return; }
                    isCameraOpen = true;
                    cameraManager.openCamera(currentCameraId, cameraStateCallback, backgroundHandler);
                } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
                    Log.e(TAG, "Failed to open camera " + currentCameraId, e);
                    isCameraOpen = false;
                    cameraOpenCloseLock.release();
                    runOnUiThread(() -> Toast.makeText(this, "Failed to open camera", Toast.LENGTH_SHORT).show());
                }
            } catch (InterruptedException e) { Log.e(TAG, "Interrupted while waiting for camera lock", e); Thread.currentThread().interrupt(); }
        });
    }

    // Восстановлена полная реализация CameraDevice.StateCallback
    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera " + camera.getId() + " opened.");
            cameraDevice = camera;
            cameraOpenCloseLock.release();
            startCameraPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.w(TAG, "Camera " + camera.getId() + " disconnected.");
            cameraOpenCloseLock.release();
            camera.close();
            cameraDevice = null;
            isCameraOpen = false;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "Camera " + camera.getId() + " error: " + error);
            cameraOpenCloseLock.release();
            camera.close();
            cameraDevice = null;
            isCameraOpen = false;
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Camera error: " + error, Toast.LENGTH_SHORT).show());
        }
    };

    // Метод closeCameraPreviewSession (Восстановлен)
    private void closeCameraPreviewSession() {
        if (cameraCaptureSession != null) {
            try {
                cameraCaptureSession.close();
                Log.d(TAG, "Camera preview session closed.");
            } catch (IllegalStateException e) {
                Log.e(TAG, "IllegalStateException closing preview session (already closed?)", e);
            } catch (Exception e) {
                Log.e(TAG, "Exception closing preview session", e);
            } finally {
                cameraCaptureSession = null;
            }
        }
    }

    private void closeCamera() {
        Log.d(TAG, "Attempting to close camera...");
        try {
            if (!cameraOpenCloseLock.tryAcquire(1000, TimeUnit.MILLISECONDS)) { Log.w(TAG, "Timeout waiting for camera lock to close."); return; }
            try {
                closeCameraPreviewSession();
                if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; Log.d(TAG,"CameraDevice closed."); }
                isCameraOpen = false;
            } finally {
                cameraOpenCloseLock.release(); Log.d(TAG,"Camera close lock released.");
            }
        } catch (InterruptedException e) { Log.e(TAG, "Interrupted while waiting for camera lock to close.", e); Thread.currentThread().interrupt(); }
        finally { stopBackgroundThread(); }
    }

    // Метод switchCamera (Восстановлен)
    private void switchCamera() {
        if (cameraManager == null || rearCameraIds == null || rearCameraIds.size() < 2) { /*...*/ return; }
        Log.d(TAG, "Switching camera...");
        closeCamera();
        currentRearCameraIndex = (currentRearCameraIndex + 1) % rearCameraIds.size();
        currentCameraId = rearCameraIds.get(currentRearCameraIndex);
        Log.d(TAG, "Switched to camera ID: " + currentCameraId);
        openCamera();
    }

    // Метод getPreviewSizes (Восстановлен)
    private Size[] getPreviewSizes() {
        try {
            if (cameraManager == null || currentCameraId == null) return new Size[]{new Size(1280, 720)};
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(currentCameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) { return map.getOutputSizes(SurfaceHolder.class); }
            else { return new Size[]{new Size(1280, 720)}; }
        } catch (CameraAccessException | IllegalArgumentException e) {
            Log.e(TAG, "Error getting preview sizes for camera " + currentCameraId, e);
            return new Size[]{new Size(1280, 720)};
        }
    }

    // Метод chooseOptimalPreviewSize (Восстановлен)
    private Size chooseOptimalPreviewSize(Size[] choices, int viewWidth, int viewHeight) {
         if (choices == null || choices.length == 0) { Log.e(TAG, "No preview sizes available, using default"); return new Size(1280, 720); }
         int targetWidth = viewWidth > 0 ? viewWidth : 1280; int targetHeight = viewHeight > 0 ? viewHeight : 720;
         List<Size> sortedChoices = new ArrayList<>(Arrays.asList(choices));
         Collections.sort(sortedChoices, (a, b) -> Long.compare((long)b.getWidth() * b.getHeight(), (long)a.getWidth() * a.getHeight()));
         Size optimalSize = null; double targetRatio = (double) targetWidth / targetHeight;
         for (Size size : sortedChoices) {
             if (size.getWidth() * size.getHeight() > 4000 * 3000) continue;
             double ratio = (double) size.getWidth() / size.getHeight();
             if (Math.abs(ratio - targetRatio) < 0.05) { optimalSize = size; break; }
         }
         if (optimalSize == null) {
             for (Size size : sortedChoices) { if (size.getWidth() * size.getHeight() <= 4000*3000) { optimalSize = size; break; } }
         }
         if (optimalSize == null) optimalSize = choices[0];
         Log.d(TAG, "Chosen preview size: " + optimalSize.getWidth() + "x" + optimalSize.getHeight() + " for view size " + targetWidth + "x" + targetHeight);
         return optimalSize;
     }


    private void startCameraPreview() {
        if (cameraDevice == null || cameraSurfaceHolder == null || !cameraSurfaceHolder.getSurface().isValid() || !isCameraOpen) { /*...*/ return; }
        if (backgroundHandler == null) { startBackgroundThread(); if(backgroundHandler == null) return; }

        backgroundHandler.post(() -> {
             try {
                 closeCameraPreviewSession();
                 if (previewSize == null) { previewSize = chooseOptimalPreviewSize(getPreviewSizes(), cameraSurfaceView.getWidth(), cameraSurfaceView.getHeight()); if (previewSize == null) { Log.e(TAG, "Failed to select preview size"); return; } }

                 Surface surface = cameraSurfaceHolder.getSurface();
                 final Size finalPreviewSize = previewSize;
                 runOnUiThread(() -> { /* Код установки setFixedSize без изменений */ });

                 previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                 previewRequestBuilder.addTarget(surface);
                 previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                 // Восстановлена полная реализация StateCallback
                 cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                     @Override
                     public void onConfigured(@NonNull CameraCaptureSession session) {
                         if (cameraDevice == null || !isCameraOpen) { Log.w(TAG, "Camera closed or null during preview session config."); session.close(); return; }
                         cameraCaptureSession = session;
                         try {
                             cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                             Log.d(TAG, "Camera preview repeating request started.");
                         } catch (CameraAccessException | IllegalStateException e) { Log.e(TAG, "Error starting preview repeating request", e); }
                     }
                     @Override
                     public void onConfigureFailed(@NonNull CameraCaptureSession session) { // ВОССТАНОВЛЕН МЕТОД
                         Log.e(TAG, "Failed to configure camera preview session.");
                         runOnUiThread(() -> Toast.makeText(MainActivity.this, "Failed to configure camera", Toast.LENGTH_SHORT).show());
                     }
                 }, backgroundHandler);

             } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
                 Log.e(TAG, "Error starting camera preview", e);
                  runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error starting preview", Toast.LENGTH_SHORT).show());
             }
        });
    }

    // --- SurfaceHolder.Callback ---
    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        Log.d(TAG, "Surface created.");
        isSurfaceAvailable = true;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && !isCameraOpen) {
            openCamera();
        }
    }
    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "Surface changed. New dimensions: " + width + "x" + height);
        if (isCameraOpen && cameraDevice != null) {
             previewSize = chooseOptimalPreviewSize(getPreviewSizes(), width, height);
             startCameraPreview();
        }
    }
    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        Log.d(TAG, "Surface destroyed.");
        isSurfaceAvailable = false;
        closeCamera();
    }

    // --- Image Loading and Processing ---
    private void checkPermissionAndPickImage() {
         String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
         if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) { Log.d(TAG, "Requesting storage permission for picking image."); ActivityCompat.requestPermissions(this, new String[]{permission}, STORAGE_PERMISSION_CODE); }
         else { Log.d(TAG, "Storage permission already granted."); openImagePicker(); }
     }
    private void openImagePicker() {
         Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
         intent.setType("image/*");
         try { imagePickerLauncher.launch(intent); Log.d(TAG, "Launching image picker."); }
         catch (Exception ex) { Log.e(TAG, "No activity found to handle image picking.", ex); Toast.makeText(this, "Cannot open image picker: " + ex.getMessage(), Toast.LENGTH_LONG).show(); }
    }
    private void loadImage(Uri uri) {
         if (uri == null) { Log.e(TAG, "Cannot load image, URI is null."); return; }
         Log.d(TAG, "Requesting image load for URI: " + uri);
         imageLoadExecutor.submit(() -> { /* Код без изменений */ });
     }
    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) { /* Код без изменений */ }

    // Метод recycleBitmaps (Восстановлен)
    private void recycleBitmaps() {
         Bitmap ob = originalBitmap; Bitmap pb = pencilBitmap; Bitmap[] lb = layerBitmaps;
         originalBitmap = null; pencilBitmap = null; layerBitmaps = null;
         if (ob != null && !ob.isRecycled()) { ob.recycle(); Log.d(TAG, "Recycled originalBitmap"); }
         if (pb != null && !pb.isRecycled()) { pb.recycle(); Log.d(TAG, "Recycled pencilBitmap"); }
         if (lb != null) { for (int i = 0; i < lb.length; i++) { if (lb[i] != null && !lb[i].isRecycled()) { lb[i].recycle(); Log.d(TAG, "Recycled layerBitmap[" + i + "]"); } } }
     }
    // Метод recyclePencilBitmaps (Восстановлен)
    private void recyclePencilBitmaps() {
          Bitmap pb = pencilBitmap; Bitmap[] lb = layerBitmaps;
          pencilBitmap = null; layerBitmaps = null;
          if (pb != null && !pb.isRecycled()) { pb.recycle(); Log.d(TAG, "Recycled pencilBitmap"); }
          if (lb != null) { for (int i = 0; i < lb.length; i++) { if (lb[i] != null && !lb[i].isRecycled()) { lb[i].recycle(); Log.d(TAG, "Recycled layerBitmap[" + i + "]"); } } }
     }
    // Метод resetTransformationsAndFit (Восстановлен)
    private void resetTransformationsAndFit() {
         matrix.reset();
         if (originalBitmap == null || originalBitmap.isRecycled() || imageView.getWidth() == 0 || imageView.getHeight() == 0) { scaleFactor = 1.0f; rotationAngle = 0.0f; if (imageView != null) runOnUiThread(() -> { imageView.setImageMatrix(matrix); imageView.invalidate(); }); return; }
         final float viewWidth = imageView.getWidth(); final float viewHeight = imageView.getHeight();
         final float bmpWidth = originalBitmap.getWidth(); final float bmpHeight = originalBitmap.getHeight();
         float scale = Math.min(viewWidth / bmpWidth, viewHeight / bmpHeight);
         float dx = (viewWidth - bmpWidth * scale) / 2f; float dy = (viewHeight - bmpHeight * scale) / 2f;
         matrix.setScale(scale, scale); matrix.postTranslate(dx, dy);
         scaleFactor = scale; rotationAngle = 0.0f;
         if (imageView != null) runOnUiThread(() -> { imageView.setImageMatrix(matrix); imageView.invalidate(); });
         Log.d(TAG, "Image reset and fit. Scale: " + scaleFactor);
     }
    // Метод applyTransformations (Восстановлен)
    private void applyTransformations() {
         if (imageView != null) { runOnUiThread(() -> { imageView.setImageMatrix(matrix); imageView.invalidate(); }); }
     }
    // Метод setImageAlpha (Восстановлен)
    private void setImageAlpha(int progress) {
         if (imageView != null) { float alpha = Math.max(0.0f, Math.min(1.0f, progress / 100.0f)); runOnUiThread(() -> { imageView.setAlpha(alpha); imageView.invalidate(); }); }
     }

    private void processPencilEffect() { /* Код без изменений */ }
    private int getLayerIndex(int grayValue) { /* Код без изменений */ }
    private void updateImageDisplay() { /* Код без изменений */ }

    // --- Диалог выбора слоев ---
    private void showLayerSelectionDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_layer_selection);
        dialog.setTitle(R.string.layer_selection_title);

        // Используем ID из вашего файла dialog_layer_selection.xml
        RecyclerView recyclerView = dialog.findViewById(R.id.layerRecyclerView); // *** ИСПРАВЛЕНО ID ***

        if (recyclerView == null) { Log.e(TAG, "RecyclerView (R.id.layerRecyclerView) not found!"); Toast.makeText(this, "Error dialog", Toast.LENGTH_SHORT).show(); return; }
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        LayerAdapter adapter = new LayerAdapter(PENCIL_HARDNESS, layerVisibility, this);
        recyclerView.setAdapter(adapter);
        dialog.show();
    }
    @Override
    public void onLayerVisibilityChanged(int position, boolean isVisible) { /* Код без изменений */ }

    // --- Сохранение/Загрузка параметров ---
    private void checkPermissionAndSaveParameters() { /* Код без изменений */ }
    private void checkPermissionAndLoadParameters() { /* Код без изменений */ }
    private void openSaveFilePicker() { /* Код без изменений */ }
    private void openLoadFilePicker() { /* Код без изменений */ }
    private void saveParametersToFile(Uri uri) { /* Код без изменений */ }
    private void loadParametersFromFile(Uri uri) { /* Код без изменений */ }
    private void applyLoadedParameters(JSONObject json) throws Exception { /* Код без изменений */ }

    // --- Жизненный цикл Activity ---
    @Override
    protected void onResume() { /* Код без изменений */ }
    @Override
    protected void onPause() { /* Код без изменений */ }
    @Override
    protected void onDestroy() { /* Код без изменений */ }
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) { /* Код без изменений */ }
    private void restoreInstanceState(@NonNull Bundle savedInstanceState) { /* Код без изменений */ }

    // --- Обработка Касаний и Жестов ---
    // Класс TouchAndGestureListener (Восстановлен полностью)
    private class TouchAndGestureListener implements View.OnTouchListener {
        PointF startDragPoint = new PointF();
        Matrix savedMatrix = new Matrix(); // Сохраняем матрицу в начале жеста

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (originalBitmap == null || originalBitmap.isRecycled()) return false;

            // Передаем событие детекторам жестов
            scaleGestureDetector.onTouchEvent(event);
            // Если будете добавлять детектор вращения, передавать и ему

            int action = event.getActionMasked();
            int pointerCount = event.getPointerCount();

            switch (action) {
                case MotionEvent.ACTION_DOWN: // Первый палец
                    savedMatrix.set(matrix); // Сохраняем текущее состояние матрицы
                    startDragPoint.set(event.getX(), event.getY());
                    touchMode = DRAG;
                    Log.d(TAG, "Touch Mode: DRAG");
                    break;

                case MotionEvent.ACTION_POINTER_DOWN: // Второй (или более) палец
                    if (pointerCount >= 2) {
                        savedMatrix.set(matrix); // Сохраняем матрицу перед началом мультитача
                        initialAngle = rotation(event);
                        midPoint(midPoint, event);
                        touchMode = ZOOM; // Используем ZOOM для 2+ пальцев (масштаб+вращение)
                        Log.d(TAG, "Touch Mode: ZOOM/ROTATE");
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (touchMode == DRAG && pointerCount == 1 && !scaleGestureDetector.isInProgress()) {
                        // Перетаскивание
                        matrix.set(savedMatrix);
                        float dx = event.getX() - startDragPoint.x;
                        float dy = event.getY() - startDragPoint.y;
                        matrix.postTranslate(dx, dy);
                        applyTransformations(); // Применяем сразу для плавности
                    } else if (touchMode == ZOOM && pointerCount >= 2) {
                        // Вращение:
                        float currentAngle = rotation(event);
                        float deltaAngle = currentAngle - initialAngle;
                        midPoint(midPoint, event); // Обновляем центр

                        // Масштабирование (scaleFactor обновляется в ScaleListener)
                        float initialScaleFactor = getMatrixScale(savedMatrix);
                        float scaleChange = (initialScaleFactor > 0.001f) ? scaleFactor / initialScaleFactor : 1f;

                        // Применяем трансформации относительно сохраненной матрицы и центра жеста
                        matrix.set(savedMatrix);
                        matrix.postScale(scaleChange, scaleChange, midPoint.x, midPoint.y);
                        matrix.postRotate(deltaAngle, midPoint.x, midPoint.y);
                        applyTransformations(); // Применяем сразу
                    }
                    break;

                case MotionEvent.ACTION_POINTER_UP:
                    if (pointerCount == 2) { // Если остался один палец после мультитача
                        int remainingPointerIndex = (event.getActionIndex() == 0) ? 1 : 0;
                        startDragPoint.set(event.getX(remainingPointerIndex), event.getY(remainingPointerIndex));
                        savedMatrix.set(matrix);
                        touchMode = DRAG;
                        Log.d(TAG, "Touch Mode changed to DRAG after POINTER_UP");
                    } else if (pointerCount < 2) {
                        touchMode = NONE;
                        Log.d(TAG, "Touch Mode: NONE (Pointer Up)");
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    touchMode = NONE;
                    Log.d(TAG, "Touch Mode: NONE (Up/Cancel)");
                    break;
            }
            return true;
        }

        // Вспомогательные методы для жестов
        private void midPoint(PointF point, MotionEvent event) {
            if (event.getPointerCount() < 2) { point.set(event.getX(), event.getY()); return; }
            float x = event.getX(0) + event.getX(1);
            float y = event.getY(0) + event.getY(1);
            point.set(x / 2f, y / 2f);
        }
        private float rotation(MotionEvent event) {
            if (event.getPointerCount() < 2) return 0f;
            double delta_x = (event.getX(0) - event.getX(1));
            double delta_y = (event.getY(0) - event.getY(1));
            return (float) Math.toDegrees(Math.atan2(delta_y, delta_x));
        }
        private float getMatrixScale(Matrix mat) {
            float[] values = new float[9];
            mat.getValues(values);
            float scaleX = values[Matrix.MSCALE_X];
            float skewY = values[Matrix.MSKEW_Y];
            return (float) Math.sqrt(scaleX * scaleX + skewY * skewY);
        }
    }

    // Класс ScaleListener (Восстановлен полностью)
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
            if (originalBitmap == null || originalBitmap.isRecycled()) return false;
            touchMode = ZOOM;
            return true;
        }
        @Override
        public boolean onScale(@NonNull ScaleGestureDetector detector) {
            if (originalBitmap == null || touchMode != ZOOM) return false;
            scaleFactor *= detector.getScaleFactor();
            scaleFactor = Math.max(0.1f, Math.min(scaleFactor, 10.0f));
            return true;
        }
    }

    // --- Вспомогательный метод для получения Display ---
    public Display getDisplay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            if (displayManager != null) {
                return displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            } else {
                Log.e(TAG, "DisplayManager service not found!");
                //noinspection deprecation
                return getWindowManager().getDefaultDisplay();
            }
        } else {
            //noinspection deprecation
            return getWindowManager().getDefaultDisplay();
        }
    }
}
