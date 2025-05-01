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
import android.graphics.SurfaceTexture; // Добавлен импорт
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
import android.os.Handler;          // Восстановлен импорт
import android.os.HandlerThread;    // Восстановлен импорт
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
// import android.view.ViewGroup; // Не используется напрямую
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
import androidx.core.content.ContextCompat; // Восстановлен импорт
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File; // Добавлен импорт
import java.io.FileInputStream; // Добавлен импорт
import java.io.FileOutputStream; // Добавлен импорт
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
    private CaptureRequest.Builder previewRequestBuilder; // Добавлено поле
    private String[] cameraIds;
    private String currentCameraId;
    private List<String> rearCameraIds = new ArrayList<>();
    private int currentRearCameraIndex = 0;
    private ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private HandlerThread backgroundThread; // Восстановлено поле
    private Handler backgroundHandler;     // Восстановлено поле
    private final Semaphore cameraOpenCloseLock = new Semaphore(1, true);
    private volatile boolean isSurfaceAvailable = false; // Добавлено поле
    private volatile boolean isCameraPendingOpen = false; // Добавлено поле
    private volatile boolean isCameraOpen = false;      // Добавлено поле
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

    // Добавлен ExecutorService для загрузки изображений
    private final ExecutorService imageLoadExecutor = Executors.newSingleThreadExecutor();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        hideSystemUI();

        initializeUI();
        setupListeners();

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
        setupCameraSelector();

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
        imagePickerLauncher = registerForActivityResult(/*...*/); // Код без изменений
        saveFileLauncher = registerForActivityResult(/*...*/); // Код без изменений
        loadFileLauncher = registerForActivityResult(/*...*/); // Код без изменений

        pickImageButton.setOnClickListener(v -> checkPermissionAndPickImage());
        transparencySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setImageAlpha(progress); // Восстановлен вызов
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        controlsVisibilityCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> updateControlsVisibility(isChecked));
        pencilModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isPencilMode = isChecked;
            layerSelectButton.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (isPencilMode && originalBitmap != null && !originalBitmap.isRecycled()) {
                imageLoadExecutor.submit(this::processPencilEffect); // Восстановлен вызов
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
        switchCameraButton.setOnClickListener(v -> switchCamera()); // Восстановлен вызов
    }

    // --- Управление UI ---
    private void hideSystemUI() { /* Код без изменений */ }
    private void updateControlsVisibility(boolean show) { /* Код без изменений */ }

    // --- Управление Разрешениями ---
    private void checkAndRequestPermissions() { /* Код без изменений */ }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) { /* Код без изменений */ }

    // --- Логика Камеры (Camera2 API) ---
    private void setupCameraManager() { /* Код без изменений (Восстановлен из предыдущей версии) */ }
    private void logCameraCharacteristics(String camId) {
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(camId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] previewSizes = map.getOutputSizes(SurfaceHolder.class);
                Log.d(TAG, "Camera " + camId + ": Preview Sizes (SurfaceHolder): " + Arrays.toString(previewSizes));
            } else {
                Log.w(TAG, "Camera " + camId + ": StreamConfigurationMap is null");
            }
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            // Исправление типа для focalLengths
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { /*...*/ return; }
        if (currentCameraId == null) { /*...*/ return; }
        if (cameraManager == null) { /*...*/ return; }
        if (isCameraOpen) { /*...*/ return; } // Восстановлена проверка

        startBackgroundThread(); // Восстановлен вызов

        cameraExecutor.submit(() -> {
            try {
                if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) { /*...*/ return; }
                try {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { /*...*/ return; }
                    isCameraOpen = true; // Восстановлено присваивание
                    // Используем backgroundHandler для колбэков камеры
                    cameraManager.openCamera(currentCameraId, cameraStateCallback, backgroundHandler); // *** ИЗМЕНЕНО ЗДЕСЬ ***
                } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
                    /*...*/
                    isCameraOpen = false; // Восстановлено присваивание
                    cameraOpenCloseLock.release();
                    /*...*/
                }
            } catch (InterruptedException e) { /*...*/ }
        });
    }

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
            isCameraOpen = false; // Восстановлено присваивание
        }
        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "Camera " + camera.getId() + " error: " + error);
            cameraOpenCloseLock.release();
            camera.close();
            cameraDevice = null;
            isCameraOpen = false; // Восстановлено присваивание
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
        // Не используем ExecutorService здесь, т.к. cameraStateCallback может быть вызван раньше,
        // и нам нужно синхронизировать доступ через семафор
        try {
            if (!cameraOpenCloseLock.tryAcquire(1000, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Timeout waiting for camera lock to close.");
                return;
            }
            try {
                closeCameraPreviewSession(); // Восстановлен вызов
                if (cameraDevice != null) {
                    cameraDevice.close();
                    cameraDevice = null;
                    Log.d(TAG,"CameraDevice closed.");
                }
                isCameraOpen = false; // Восстановлено присваивание
            } finally {
                cameraOpenCloseLock.release();
                Log.d(TAG,"Camera close lock released.");
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while waiting for camera lock to close.", e);
            Thread.currentThread().interrupt();
        } finally {
            stopBackgroundThread(); // Восстановлен вызов
        }
    }

     // Метод switchCamera (Восстановлен)
     private void switchCamera() {
         if (cameraManager == null || rearCameraIds == null || rearCameraIds.size() < 2) {
             Log.w(TAG, "Cannot switch camera: Not enough rear cameras available.");
             runOnUiThread(()->Toast.makeText(this, "Only one rear camera available", Toast.LENGTH_SHORT).show());
             return;
         }

         Log.d(TAG, "Switching camera...");
         closeCamera(); // Закрываем текущую камеру

         // Переключаемся на следующую ЗАДНЮЮ камеру циклически
         currentRearCameraIndex = (currentRearCameraIndex + 1) % rearCameraIds.size();
         currentCameraId = rearCameraIds.get(currentRearCameraIndex);
         Log.d(TAG, "Switched to camera ID: " + currentCameraId);

         // Пытаемся открыть новую камеру
         openCamera(); // Запускаем открытие, оно проверит surface и т.д.
     }

    // Метод getPreviewSizes (Восстановлен)
    private Size[] getPreviewSizes() {
        try {
            if (cameraManager == null || currentCameraId == null) return new Size[]{new Size(1280, 720)};
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(currentCameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                // Используем SurfaceHolder или SurfaceTexture, в зависимости от того, что поддерживается
                // Обычно SurfaceHolder поддерживается для превью
                 return map.getOutputSizes(SurfaceHolder.class);
                 // Если используете TextureView, то map.getOutputSizes(SurfaceTexture.class);
            } else {
                return new Size[]{new Size(1280, 720)};
            }
        } catch (CameraAccessException | IllegalArgumentException e) {
            Log.e(TAG, "Error getting preview sizes for camera " + currentCameraId, e);
            return new Size[]{new Size(1280, 720)};
        }
    }


    private void startCameraPreview() {
        if (cameraDevice == null || cameraSurfaceHolder == null || !cameraSurfaceHolder.getSurface().isValid()) { /*...*/ return; }

        startBackgroundThread(); // Убедимся, что поток запущен
        backgroundHandler.post(() -> { // Выполняем в фоновом потоке Handler'а
             try {
                 closeCameraPreviewSession(); // Восстановлен вызов

                 if (previewSize == null) {
                      Log.e(TAG, "Preview size is null, attempting to select one.");
                      // Попытка выбрать размер здесь, если он не был выбран ранее
                      previewSize = chooseOptimalPreviewSize(getPreviewSizes(), cameraSurfaceView.getWidth(), cameraSurfaceView.getHeight());
                      if (previewSize == null) {
                           Log.e(TAG, "Failed to select preview size, cannot start preview.");
                           return;
                      }
                 }

                 Surface surface = cameraSurfaceHolder.getSurface();
                 final Size finalPreviewSize = previewSize; // Для лямбды
                 runOnUiThread(() -> { /* Код установки setFixedSize без изменений */ });

                 previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                 previewRequestBuilder.addTarget(surface);
                 previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                 cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() { /* ... */ }, backgroundHandler); // Используем backgroundHandler

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
        isSurfaceAvailable = true; // Устанавливаем флаг
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
        isSurfaceAvailable = false; // Сбрасываем флаг
        closeCamera();
    }

    // --- Image Loading and Processing ---
    private void checkPermissionAndPickImage() { /* Код без изменений */ }
    private void openImagePicker() { /* Код без изменений */ }
    private void loadImage(Uri uri) { /* Код без изменений (с использованием imageLoadExecutor) */ }
    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) { /* Код без изменений */ }

    // Метод recycleBitmaps (Восстановлен)
    private void recycleBitmaps() {
        Bitmap ob = originalBitmap;
        Bitmap pb = pencilBitmap;
        Bitmap[] lb = layerBitmaps;
        originalBitmap = null;
        pencilBitmap = null;
        layerBitmaps = null;
        if (ob != null && !ob.isRecycled()) { ob.recycle(); Log.d(TAG, "Recycled originalBitmap"); }
        if (pb != null && !pb.isRecycled()) { pb.recycle(); Log.d(TAG, "Recycled pencilBitmap"); }
        if (lb != null) {
            for (int i = 0; i < lb.length; i++) {
                if (lb[i] != null && !lb[i].isRecycled()) {
                    lb[i].recycle(); Log.d(TAG, "Recycled layerBitmap[" + i + "]");
                }
            }
        }
    }

    // Метод recyclePencilBitmaps (Восстановлен)
    private void recyclePencilBitmaps() {
         Bitmap pb = pencilBitmap;
         Bitmap[] lb = layerBitmaps;
         pencilBitmap = null;
         layerBitmaps = null;
         if (pb != null && !pb.isRecycled()) { pb.recycle(); Log.d(TAG, "Recycled pencilBitmap"); }
         if (lb != null) {
             for (int i = 0; i < lb.length; i++) {
                 if (lb[i] != null && !lb[i].isRecycled()) { lb[i].recycle(); Log.d(TAG, "Recycled layerBitmap[" + i + "]"); }
             }
         }
    }

    // Метод resetTransformationsAndFit (Восстановлен)
    private void resetTransformationsAndFit() {
        matrix.reset();
        if (originalBitmap == null || originalBitmap.isRecycled() || imageView.getWidth() == 0 || imageView.getHeight() == 0) {
            scaleFactor = 1.0f; rotationAngle = 0.0f;
            if (imageView != null) runOnUiThread(() -> { imageView.setImageMatrix(matrix); imageView.invalidate(); });
            return;
        }
        final float viewWidth = imageView.getWidth(); final float viewHeight = imageView.getHeight();
        final float bmpWidth = originalBitmap.getWidth(); final float bmpHeight = originalBitmap.getHeight();
        float scale = Math.min(viewWidth / bmpWidth, viewHeight / bmpHeight);
        float dx = (viewWidth - bmpWidth * scale) / 2f;
        float dy = (viewHeight - bmpHeight * scale) / 2f;
        matrix.setScale(scale, scale); matrix.postTranslate(dx, dy);
        scaleFactor = scale; rotationAngle = 0.0f; // Сбрасываем угол
        if (imageView != null) runOnUiThread(() -> { imageView.setImageMatrix(matrix); imageView.invalidate(); });
        Log.d(TAG, "Image reset and fit. Scale: " + scaleFactor);
    }

    // Метод applyTransformations (Восстановлен)
    private void applyTransformations() {
        if (imageView != null) {
             // Применяем в UI потоке для безопасности
            runOnUiThread(() -> {
                imageView.setImageMatrix(matrix);
                imageView.invalidate();
            });
        }
    }

    // Метод setImageAlpha (Восстановлен)
    private void setImageAlpha(int progress) {
        if (imageView != null) {
            float alpha = Math.max(0.0f, Math.min(1.0f, progress / 100.0f));
            // Применяем в UI потоке
            runOnUiThread(() -> {
                imageView.setAlpha(alpha);
                imageView.invalidate();
            });
        }
    }


    private void processPencilEffect() { /* Код без изменений (с использованием imageLoadExecutor) */ }
    private int getLayerIndex(int grayValue) { /* Код без изменений */ }
    private void updateImageDisplay() { /* Код без изменений (с использованием runOnUiThread) */ }

    // --- Диалог выбора слоев ---
    private void showLayerSelectionDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_layer_selection);
        dialog.setTitle(R.string.layer_selection_title);

        // Исправляем поиск RecyclerView по ID
        RecyclerView recyclerView = dialog.findViewById(R.id.layerRecyclerView); // *** ИЗМЕНЕНО ID ***

        if (recyclerView == null) { /*...*/ return; }
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
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        hideSystemUI();
        startBackgroundThread(); // Восстановлен вызов
        if (isSurfaceAvailable && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && !isCameraOpen) {
            Log.d(TAG, "Opening camera from onResume");
            openCamera();
        }
        updateImageDisplay();
    }
    @Override
    protected void onPause() { /* Код без изменений */ }
    @Override
    protected void onDestroy() { /* Код без изменений */ }
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) { /* Код без изменений */ }
    private void restoreInstanceState(@NonNull Bundle savedInstanceState) { /* Код без изменений */ }

    // --- Обработка Касаний и Жестов ---
    private class TouchAndGestureListener implements View.OnTouchListener { /* Код без изменений */ }
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener { /* Код без изменений */ }

    // --- Вспомогательный метод для получения Display ---
    public Display getDisplay() { /* Код без изменений */ }
}
