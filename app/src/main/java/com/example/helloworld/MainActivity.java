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
// import android.graphics.SurfaceTexture; // Не используется напрямую
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
// import android.os.Handler; // Не используется напрямую в этой версии
// import android.os.HandlerThread; // Не используется напрямую в этой версии
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections; // Импорт для Collections.singletonList
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
    private String[] cameraIds;
    private String currentCameraId;
    private List<String> rearCameraIds = new ArrayList<>();
    private int currentRearCameraIndex = 0; // Индекс текущей задней камеры в списке rearCameraIds
    private ExecutorService cameraExecutor = Executors.newSingleThreadExecutor(); // Используем ExecutorService
    // private HandlerThread backgroundThread; // Заменяем на ExecutorService
    // private Handler backgroundHandler; // Заменяем на ExecutorService
    private final Semaphore cameraOpenCloseLock = new Semaphore(1, true); // Семафор для синхронизации открытия/закрытия
    private Size previewSize;

    // Манипуляции с изображением
    private Bitmap originalBitmap = null;
    private Matrix matrix = new Matrix();
    private float scaleFactor = 1.0f;
    private float rotationAngle = 0.0f;
    private Uri currentImageUri;

    // Карандашный режим
    private boolean isPencilMode = false;
    private Bitmap pencilBitmap; // Обесцвеченная версия
    private Bitmap[] layerBitmaps; // Массив слоев
    private boolean[] layerVisibility = new boolean[20]; // Видимость слоев
    private static final String[] PENCIL_HARDNESS = {
            "9H", "8H", "7H", "6H", "5H", "4H", "3H", "2H", "H", "F",
            "HB", "B", "2B", "3B", "4B", "5B", "6B", "7B", "8B", "9B"
    };

    // Управление видимостью изображения
    private boolean isImageVisible = true;

    // Распознавание жестов
    private ScaleGestureDetector scaleGestureDetector;
    // Константы для режимов касания
    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    private static final int ROTATE = 3; // Добавляем режим вращения
    private int touchMode = NONE;
    // Для перетаскивания
    private float lastEventX, lastEventY;
    // Для вращения и масштабирования
    private PointF startPoint = new PointF();
    private PointF midPoint = new PointF();
    private float initialAngle = 0f;
    // private float initialDistance = 0f; // Можно использовать detector.getPreviousSpan()


    // Activity Result API
    private ActivityResultLauncher<Intent> imagePickerLauncher;
    private ActivityResultLauncher<Intent> saveFileLauncher;
    private ActivityResultLauncher<Intent> loadFileLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        hideSystemUI(); // Скрываем системные бары

        // Инициализация UI
        initializeUI();

        // Настройка слушателей
        setupListeners();

        // Настройка распознавания жестов
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());
        imageView.setOnTouchListener(new TouchAndGestureListener()); // Используем один общий слушатель

        // Настройка SurfaceHolder для камеры
        cameraSurfaceHolder = cameraSurfaceView.getHolder();
        cameraSurfaceHolder.addCallback(this);

        // Инициализация Camera2
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cameraManager == null) {
            Log.e(TAG, "CameraManager is null! Cannot proceed.");
            Toast.makeText(this, "Camera service not available.", Toast.LENGTH_LONG).show();
            finish(); // Выход, если нет камеры
            return;
        }
        setupCameraSelector(); // Определяем доступные камеры

        // Запрос разрешений
        checkAndRequestPermissions(); // Запрашиваем камеру и хранилище

        // Восстановление состояния, если оно есть
        if (savedInstanceState != null) {
            restoreInstanceState(savedInstanceState);
        } else {
            // Начальные значения по умолчанию
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

        // Настройка ImageView для трансформаций
        imageView.setScaleType(ImageView.ScaleType.MATRIX);
    }

    private void setupListeners() {
        // Инициализация Activity Result Launcher'ов
        imagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri selectedImageUri = result.getData().getData();
                        if (selectedImageUri != null) {
                            currentImageUri = selectedImageUri;
                            Log.d(TAG, "Image selected: " + currentImageUri);
                            loadImage(currentImageUri); // Загружаем новое изображение
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
                            // Сначала загружаем изображение, если оно было в параметрах
                            // (Логика загрузки URI из параметров должна быть в loadParametersFromFile)
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
                 // Запускаем обработку эффекта в фоновом потоке
                 imageLoadExecutor.submit(this::processPencilEffect);
             } else {
                 // Если выключили режим, очищаем слои и показываем оригинал
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
        // Обновляем видимость всех контролов
        pickImageButton.setVisibility(visibility);
        transparencySeekBar.setVisibility(visibility);
        pencilModeSwitch.setVisibility(visibility);
        layerSelectButton.setVisibility(show && isPencilMode ? View.VISIBLE : View.GONE); // Зависит от pencilMode
        saveParametersButton.setVisibility(visibility);
        loadParametersButton.setVisibility(visibility);
        hideImageCheckbox.setVisibility(visibility); // Показываем всегда, когда и другие контролы
        switchCameraButton.setVisibility(show && rearCameraIds != null && rearCameraIds.size() > 1 ? View.VISIBLE : View.GONE); // Зависит от кол-ва камер

        // Чтобы чекбокс видимости не пропадал сам
        controlsVisibilityCheckbox.setVisibility(View.VISIBLE);

        Log.d(TAG, "Controls visibility updated: " + (show ? "VISIBLE" : "GONE"));
    }

    // --- Управление Разрешениями ---

    private void checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        // Проверяем разрешение камеры
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.CAMERA);
        }

        // Проверяем разрешение на хранилище
        String storagePermission = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            storagePermission = Manifest.permission.READ_MEDIA_IMAGES;
        } else { // Для Android 12 и ниже
            storagePermission = Manifest.permission.READ_EXTERNAL_STORAGE;
             // WRITE_EXTERNAL_STORAGE может быть нужен для сохранения параметров, если использовать внешний файл, но лучше JSON и SAF
             // if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
             //     permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
             // }
        }
        if (storagePermission != null && ContextCompat.checkSelfPermission(this, storagePermission) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(storagePermission);
        }

        if (!permissionsNeeded.isEmpty()) {
            Log.d(TAG, "Requesting permissions: " + permissionsNeeded);
            ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), CAMERA_PERMISSION_CODE); // Используем один код запроса
        } else {
            Log.d(TAG, "All necessary permissions already granted.");
            // Если разрешения уже есть, и камера еще не настроена/открыта
            if (cameraIds == null) setupCameraSelector();
            if (!isCameraOpen && cameraSurfaceHolder != null && cameraSurfaceHolder.getSurface() != null && cameraSurfaceHolder.getSurface().isValid()) {
                openCamera(); // Пытаемся открыть камеру, если все готово
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean cameraGranted = false;
        boolean storageGranted = false;

        if (requestCode == CAMERA_PERMISSION_CODE) { // Обрабатываем наш общий код запроса
            for (int i = 0; i < permissions.length; i++) {
                if (Manifest.permission.CAMERA.equals(permissions[i])) {
                    cameraGranted = (grantResults[i] == PackageManager.PERMISSION_GRANTED);
                    Log.d(TAG, "Camera permission result: " + (cameraGranted ? "GRANTED" : "DENIED"));
                } else if (Manifest.permission.READ_EXTERNAL_STORAGE.equals(permissions[i]) ||
                           Manifest.permission.READ_MEDIA_IMAGES.equals(permissions[i]) ||
                           Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permissions[i])) {
                    storageGranted = (grantResults[i] == PackageManager.PERMISSION_GRANTED);
                     Log.d(TAG, "Storage permission (" + permissions[i] + ") result: " + (storageGranted ? "GRANTED" : "DENIED"));
                }
            }

            if (cameraGranted) {
                if (cameraIds == null) setupCameraSelector();
                openCamera(); // Открываем камеру после получения разрешения
            } else {
                Toast.makeText(this, "Camera Permission is Required", Toast.LENGTH_SHORT).show();
            }
             if (!storageGranted) {
                // Можно показать отдельное сообщение, если хранилище не разрешено
                 // Toast.makeText(this, "Storage Permission Denied", Toast.LENGTH_SHORT).show();
             }

        }
        // Обработка других кодов запроса (если они были добавлены)
        /* else if (requestCode == STORAGE_PERMISSION_CODE) { ... } */
    }


    // --- Логика Камеры (Camera2 API) ---

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
                    logCameraCharacteristics(id); // Логируем характеристики
                }
            }

            if (!rearCameraIds.isEmpty()) {
                currentCameraId = firstBackCameraId; // Начинаем с первой задней
                currentRearCameraIndex = rearCameraIds.indexOf(currentCameraId);
                Log.d(TAG, "Found " + rearCameraIds.size() + " rear cameras. Defaulting to: " + currentCameraId);
            } else if (cameraIds.length > 0) {
                currentCameraId = cameraIds[0]; // Если нет задних, берем первую
                currentRearCameraIndex = -1; // Указываем, что это не задняя камера из списка
                Log.w(TAG, "No rear cameras found, defaulting to first available camera: " + currentCameraId);
            } else {
                Log.e(TAG, "No cameras available.");
                Toast.makeText(this, "No cameras found on this device", Toast.LENGTH_LONG).show();
                currentCameraId = null;
            }

            // Обновляем видимость кнопки переключения
            runOnUiThread(() -> switchCameraButton.setVisibility(rearCameraIds.size() > 1 ? View.VISIBLE : View.GONE));

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error accessing camera characteristics during setup", e);
            Toast.makeText(this, "Cannot access cameras: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // Логирование характеристик камеры (для отладки)
    private void logCameraCharacteristics(String camId) {
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(camId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                 Size[] previewSizes = map.getOutputSizes(SurfaceHolder.class); // Используем SurfaceHolder
                 Log.d(TAG, "Camera " + camId + ": Preview Sizes (SurfaceHolder): " + Arrays.toString(previewSizes));
            } else {
                 Log.w(TAG, "Camera " + camId + ": StreamConfigurationMap is null");
            }
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            Float[] focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
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

    private void openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "openCamera called without permission.");
            checkAndRequestPermissions();
            return;
        }
        if (currentCameraId == null) {
            Log.e(TAG, "Cannot open camera, no valid camera ID selected.");
            return;
        }
        if (cameraManager == null) {
            Log.e(TAG, "Cannot open camera, CameraManager is null.");
            return;
        }
        if (isCameraOpen) {
             Log.d(TAG, "Camera already open.");
             return;
        }

        Log.d(TAG, "Attempting to open camera: " + currentCameraId);
        cameraExecutor.submit(() -> { // Выполняем в фоновом потоке
            try {
                if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                    Log.e(TAG, "Time out waiting to lock camera opening.");
                    return;
                }
                try {
                    // Проверяем разрешение еще раз внутри потока
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                         Log.e(TAG, "Permission lost before opening camera.");
                         cameraOpenCloseLock.release();
                         return;
                    }
                    isCameraOpen = true; // Ставим флаг перед вызовом
                    cameraManager.openCamera(currentCameraId, cameraStateCallback, null); // null handler -> колбэки в потоке по умолчанию (фоновом)
                } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
                    Log.e(TAG, "Failed to open camera " + currentCameraId, e);
                    isCameraOpen = false;
                    cameraOpenCloseLock.release();
                    runOnUiThread(() -> Toast.makeText(this, "Failed to open camera", Toast.LENGTH_SHORT).show());
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while waiting for camera lock", e);
                Thread.currentThread().interrupt();
            }
        });
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            Log.d(TAG, "Camera " + camera.getId() + " opened.");
            cameraDevice = camera;
            cameraOpenCloseLock.release(); // Освобождаем лок после успешного открытия
            startCameraPreview(); // Начинаем показ превью
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

    private void closeCamera() {
        Log.d(TAG, "Attempting to close camera...");
        cameraExecutor.submit(() -> { // Закрываем тоже в фоновом потоке
            try {
                if (!cameraOpenCloseLock.tryAcquire(1000, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "Timeout waiting for camera lock to close.");
                    return;
                }
                try {
                    if (cameraCaptureSession != null) {
                        cameraCaptureSession.close();
                        cameraCaptureSession = null;
                        Log.d(TAG,"CameraCaptureSession closed.");
                    }
                    if (cameraDevice != null) {
                        cameraDevice.close();
                        cameraDevice = null;
                        Log.d(TAG,"CameraDevice closed.");
                    }
                    isCameraOpen = false;
                } finally {
                    cameraOpenCloseLock.release();
                    Log.d(TAG,"Camera close lock released.");
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while waiting for camera lock to close.", e);
                Thread.currentThread().interrupt();
            }
        });
    }

    private void startCameraPreview() {
        if (cameraDevice == null || cameraSurfaceHolder == null || !cameraSurfaceHolder.getSurface().isValid()) {
            Log.w(TAG, "Cannot start preview: cameraDevice=" + cameraDevice + ", surfaceValid=" + (cameraSurfaceHolder != null && cameraSurfaceHolder.getSurface().isValid()));
            return;
        }

        cameraExecutor.submit(() -> { // Запуск превью тоже в фоновом потоке
             try {
                closeCameraPreviewSession(); // Закрываем предыдущую сессию, если есть

                 // Получаем размер превью (уже должен быть выбран в openCamera или surfaceChanged)
                 if (previewSize == null) {
                     Log.e(TAG, "Preview size is null, cannot start preview.");
                     return;
                 }

                 Surface surface = cameraSurfaceHolder.getSurface();
                 // Устанавливаем размер буфера поверхности (в UI потоке, т.к. это View операция)
                 runOnUiThread(() -> {
                     if (surface.isValid()) {
                         try {
                              cameraSurfaceHolder.setFixedSize(previewSize.getWidth(), previewSize.getHeight());
                              Log.d(TAG, "Set SurfaceHolder fixed size for preview (UI Thread): " + previewSize.getWidth() + "x" + previewSize.getHeight());
                         } catch(Exception e) {
                              Log.e(TAG, "Error setting fixed size in startCameraPreview", e);
                         }
                     } else {
                          Log.w(TAG, "Surface became invalid before setting fixed size.");
                     }
                 });


                 CaptureRequest.Builder previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                 previewRequestBuilder.addTarget(surface);
                 previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                 // Можно добавить другие настройки, например, CONTROL_AE_MODE

                 cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                     @Override
                     public void onConfigured(@NonNull CameraCaptureSession session) {
                         if (cameraDevice == null || !isCameraOpen) { // Проверяем, не закрылась ли камера
                             Log.w(TAG, "Camera closed or null during preview session config.");
                             session.close();
                             return;
                         }
                         cameraCaptureSession = session;
                         try {
                             cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), null, null); // null handler -> в том же потоке, где и колбэки сессии
                             Log.d(TAG, "Camera preview repeating request started.");
                         } catch (CameraAccessException | IllegalStateException e) {
                             Log.e(TAG, "Error starting preview repeating request", e);
                         }
                     }

                     @Override
                     public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                         Log.e(TAG, "Failed to configure camera preview session.");
                         runOnUiThread(() -> Toast.makeText(MainActivity.this, "Failed to configure camera", Toast.LENGTH_SHORT).show());
                     }
                 }, null); // null handler -> колбэки сессии в фоновом потоке по умолчанию

             } catch (CameraAccessException | IllegalStateException e) {
                 Log.e(TAG, "Error starting camera preview", e);
                  runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error starting preview", Toast.LENGTH_SHORT).show());
             }
        });

    }

    // --- SurfaceHolder.Callback ---

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        Log.d(TAG, "Surface created.");
        // Камеру открываем после получения разрешения в onRequestPermissionsResult или onResume
         if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && !isCameraOpen) {
              openCamera();
         }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "Surface changed. New dimensions: " + width + "x" + height);
        // Перезапускаем превью, если камера уже открыта
        if (isCameraOpen && cameraDevice != null) {
            // Пересчитываем размер превью для нового размера поверхности
            previewSize = chooseOptimalPreviewSize(getPreviewSizes(), width, height);
            startCameraPreview();
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        Log.d(TAG, "Surface destroyed.");
        closeCamera(); // Закрываем камеру при уничтожении поверхности
    }

    // --- Image Loading and Processing ---

    private void checkPermissionAndPickImage() {
        String permission;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permission = Manifest.permission.READ_MEDIA_IMAGES;
        } else {
            permission = Manifest.permission.READ_EXTERNAL_STORAGE;
        }

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
             Log.d(TAG, "Requesting storage permission for picking image.");
            ActivityCompat.requestPermissions(this, new String[]{permission}, STORAGE_PERMISSION_CODE); // Используем STORAGE_PERMISSION_CODE
        } else {
            Log.d(TAG, "Storage permission already granted.");
            openImagePicker();
        }
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        try {
            imagePickerLauncher.launch(intent);
            Log.d(TAG, "Launching image picker.");
        } catch (Exception ex) {
            Log.e(TAG, "No activity found to handle image picking.", ex);
            Toast.makeText(this, "Cannot open image picker: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // Загрузка изображения в фоновом потоке
    private void loadImage(Uri uri) {
        if (uri == null) {
            Log.e(TAG, "Cannot load image, URI is null.");
            return;
        }
        Log.d(TAG, "Requesting image load for URI: " + uri);
        imageLoadExecutor.submit(() -> { // Используем ExecutorService
            Bitmap loadedBitmap = null;
            InputStream inputStream = null;
            try {
                ContentResolver resolver = getContentResolver();
                // Сначала получаем размеры
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                inputStream = resolver.openInputStream(uri);
                BitmapFactory.decodeStream(inputStream, null, options);
                if (inputStream != null) inputStream.close(); // Закрываем поток после чтения размеров

                // Рассчитываем inSampleSize (уменьшаем размер для экономии памяти)
                int reqWidth = 1920; // Максимальная ширина
                int reqHeight = 1080; // Максимальная высота
                options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
                options.inJustDecodeBounds = false;

                // Декодируем с уменьшением размера
                inputStream = resolver.openInputStream(uri);
                loadedBitmap = BitmapFactory.decodeStream(inputStream, null, options);
                Log.d(TAG, "Bitmap loaded in background: " + (loadedBitmap != null ? loadedBitmap.getWidth() + "x" + loadedBitmap.getHeight() : "null"));

            } catch (IOException e) {
                Log.e(TAG, "IOException loading bitmap", e);
            } catch (OutOfMemoryError oom) {
                Log.e(TAG, "OutOfMemoryError loading bitmap", oom);
                runOnUiThread(()-> Toast.makeText(this, "Image too large - Out of Memory", Toast.LENGTH_LONG).show());
            } catch (Exception e) { // Ловим другие ошибки
                 Log.e(TAG, "Exception loading bitmap", e);
            } finally {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (IOException e) { /* ignore */ }
                }
            }

            // Обновляем UI в основном потоке
            final Bitmap finalBitmap = loadedBitmap; // Копия для лямбды
            runOnUiThread(() -> {
                if (finalBitmap != null) {
                    recycleBitmaps(); // Освобождаем старые битмапы
                    originalBitmap = finalBitmap;
                    currentImageUri = uri; // Сохраняем URI загруженного изображения
                    resetTransformationsAndFit(); // Подгоняем под экран
                    if (isPencilMode) {
                         // Обработку эффекта тоже лучше в фон
                         imageLoadExecutor.submit(this::processPencilEffect);
                    } else {
                        updateImageDisplay(); // Обновляем отображение
                    }
                } else {
                    Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // Расчет коэффициента уменьшения изображения
    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            // Выбираем больший коэффициент уменьшения, чтобы изображение точно влезло
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        Log.d(TAG, "Calculated inSampleSize: " + inSampleSize);
        return inSampleSize;
    }

    // --- Логика Pencil Mode ---

    private void processPencilEffect() {
         if (originalBitmap == null || originalBitmap.isRecycled()) {
            Log.w(TAG, "processPencilEffect: Original bitmap unavailable.");
            // Может стоит выключить pencil mode?
            // isPencilMode = false;
            // runOnUiThread(()-> pencilModeSwitch.setChecked(false));
            return;
         }
         Log.d(TAG, "Starting pencil effect processing in background...");

        // Копируем оригинал для потокобезопасности
        final Bitmap sourceBitmap = originalBitmap.copy(originalBitmap.getConfig(), false);

         // Выполняем ресурсоемкую обработку в фоновом потоке
         imageLoadExecutor.submit(() -> {
            // Освобождаем старые слои перед созданием новых
            recyclePencilBitmaps();

            Bitmap grayBitmap = null;
            Bitmap[] newLayerBitmaps = new Bitmap[PENCIL_HARDNESS.length];
            boolean success = false;

            try {
                 int width = sourceBitmap.getWidth();
                 int height = sourceBitmap.getHeight();

                 // 1. Обесцвечивание
                 grayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                 Canvas canvasGray = new Canvas(grayBitmap);
                 Paint paintGray = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                 ColorMatrix cmGray = new ColorMatrix();
                 cmGray.setSaturation(0);
                 paintGray.setColorFilter(new ColorMatrixColorFilter(cmGray));
                 canvasGray.drawBitmap(sourceBitmap, 0, 0, paintGray);

                 // 2. Получение пикселей
                 int[] pixels = new int[width * height];
                 grayBitmap.getPixels(pixels, 0, width, 0, 0, width, height);
                 // grayBitmap можно освободить, если он не нужен сам по себе
                 // grayBitmap.recycle(); grayBitmap = null;

                 // 3. Создание и заполнение слоев
                 int numLayers = newLayerBitmaps.length;
                 int step = 256 / numLayers;

                 int[][] layerPixels = new int[numLayers][width * height];
                 for (int i = 0; i < numLayers; i++) Arrays.fill(layerPixels[i], Color.TRANSPARENT);

                 for (int i = 0; i < pixels.length; i++) {
                     int gray = Color.red(pixels[i]);
                     int layerIndex = getLayerIndex(gray); // Используем исправленный getLayerIndex
                     if (layerIndex >= 0) { // Индекс всегда будет >= 0
                          layerPixels[layerIndex][i] = pixels[i];
                     }
                 }
                 pixels = null; // Освобождаем

                 // 4. Создание Bitmap'ов слоев
                 for (int i = 0; i < numLayers; i++) {
                     newLayerBitmaps[i] = Bitmap.createBitmap(layerPixels[i], width, height, Bitmap.Config.ARGB_8888);
                     layerPixels[i] = null; // Освобождаем
                 }
                 layerPixels = null;
                 success = true;
                 Log.d(TAG, "Pencil effect processing successful.");

            } catch (OutOfMemoryError e) {
                Log.e(TAG, "OutOfMemoryError processing pencil effect", e);
                 runOnUiThread(() -> Toast.makeText(this, "Not enough memory for pencil effect", Toast.LENGTH_LONG).show());
                 // Очищаем то, что могли создать
                 if (grayBitmap != null && !grayBitmap.isRecycled()) grayBitmap.recycle();
                 for (int i = 0; i < newLayerBitmaps.length; i++) {
                    if (newLayerBitmaps[i] != null && !newLayerBitmaps[i].isRecycled()) newLayerBitmaps[i].recycle();
                 }
            } catch (Exception e) {
                 Log.e(TAG, "Error processing pencil effect", e);
                 runOnUiThread(() -> Toast.makeText(this, "Error processing pencil effect", Toast.LENGTH_SHORT).show());
            } finally {
                 // Освобождаем исходную копию, она больше не нужна
                 if (sourceBitmap != null && !sourceBitmap.isRecycled()) {
                     sourceBitmap.recycle();
                 }
                  // Если создали серый битмап, но не освободили раньше
                  if (grayBitmap != null && !grayBitmap.isRecycled()) {
                      grayBitmap.recycle();
                  }
            }

            // Обновляем глобальные переменные и UI в основном потоке
             final boolean finalSuccess = success;
             final Bitmap[] finalLayerBitmaps = newLayerBitmaps; // Передаем созданные слои
            runOnUiThread(() -> {
                 if (finalSuccess) {
                     layerBitmaps = finalLayerBitmaps; // Присваиваем успешно созданные слои
                     pencilBitmap = null; // Обесцвеченный оригинал больше не храним отдельно
                 } else {
                     // Если не удалось, сбрасываем режим карандаша
                     layerBitmaps = null;
                     pencilBitmap = null;
                     isPencilMode = false;
                     pencilModeSwitch.setChecked(false);
                     layerSelectButton.setVisibility(View.GONE);
                 }
                 updateImageDisplay(); // Обновляем отображение в любом случае
            });
         });
    }


    // Определение индекса слоя по значению серого (0-255)
    private int getLayerIndex(int grayValue) {
        // Более точное распределение, чтобы избежать пустых крайних слоев
        int numLayers = PENCIL_HARDNESS.length;
        // Отображаем 0..255 на 0..(numLayers - 1)
        int index = (int) (((float) grayValue / 256.0f) * numLayers);
        // Убедимся, что индекс в допустимых границах
        return Math.max(0, Math.min(index, numLayers - 1));
    }

    // Обновление отображаемого изображения в ImageView
    private void updateImageDisplay() {
        if (!isImageVisible || originalBitmap == null || originalBitmap.isRecycled()) {
            Log.d(TAG, "Hiding ImageView or originalBitmap is unavailable.");
            runOnUiThread(() -> {
                 if (imageView != null) {
                    imageView.setImageBitmap(null);
                    imageView.setVisibility(View.INVISIBLE);
                    imageView.invalidate();
                 }
            });
            return;
        }

        Bitmap bitmapToDisplay = null;
        boolean isPencilBitmapReady = isPencilMode && layerBitmaps != null;

        if (isPencilBitmapReady) {
            Log.d(TAG, "Updating display for Pencil mode");
            try {
                bitmapToDisplay = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmapToDisplay);
                canvas.drawColor(Color.TRANSPARENT);
                Paint layerPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
                boolean drawnSomething = false;

                for (int i = 0; i < layerBitmaps.length; i++) {
                    if (layerVisibility[i] && layerBitmaps[i] != null && !layerBitmaps[i].isRecycled()) {
                        canvas.drawBitmap(layerBitmaps[i], 0, 0, layerPaint);
                        drawnSomething = true;
                    }
                }
                Log.d(TAG, "Drew visible pencil layers. Drawn something: " + drawnSomething);
                if (!drawnSomething && bitmapToDisplay != null && !bitmapToDisplay.isRecycled()) {
                    bitmapToDisplay.recycle();
                    bitmapToDisplay = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
                    bitmapToDisplay.eraseColor(Color.TRANSPARENT);
                }
            } catch (OutOfMemoryError e) {
                Log.e(TAG, "OOM Error creating result bitmap for pencil mode", e);
                bitmapToDisplay = originalBitmap; // Fallback to original
                // Выключаем режим карандаша, чтобы избежать повторной ошибки
                isPencilMode = false;
                runOnUiThread(() -> {
                     pencilModeSwitch.setChecked(false);
                     layerSelectButton.setVisibility(View.GONE);
                     Toast.makeText(this, "Out of memory displaying layers", Toast.LENGTH_SHORT).show();
                });
                recyclePencilBitmaps(); // Очищаем слои
            } catch (Exception e) {
                 Log.e(TAG, "Error composing pencil layers", e);
                 bitmapToDisplay = originalBitmap; // Fallback
            }
        } else {
            Log.d(TAG, "Displaying original bitmap");
            bitmapToDisplay = originalBitmap; // Показываем оригинал
        }

        // Устанавливаем битмап и обновляем UI
        final Bitmap finalBitmap = bitmapToDisplay; // Копия для лямбды
        runOnUiThread(() -> {
            if (imageView != null) {
                 if (finalBitmap != null && !finalBitmap.isRecycled()) {
                     imageView.setImageBitmap(finalBitmap);
                     imageView.setVisibility(View.VISIBLE);
                     imageView.setImageMatrix(matrix); // Применяем текущую матрицу
                     setImageAlpha(transparencySeekBar.getProgress());
                     imageView.invalidate();
                     Log.d(TAG, "ImageView updated.");
                 } else {
                      Log.w(TAG, "Bitmap to display is null or recycled.");
                      imageView.setImageBitmap(null);
                      imageView.setVisibility(View.INVISIBLE);
                 }
            }
        });
    }

    // Освобождение только карандашных битмапов
    private void recyclePencilBitmaps() {
         Bitmap pb = pencilBitmap;
         Bitmap[] lb = layerBitmaps;
         pencilBitmap = null;
         layerBitmaps = null;

         if (pb != null && !pb.isRecycled()) {
             pb.recycle();
             Log.d(TAG, "Recycled pencilBitmap");
         }
         if (lb != null) {
             for (int i = 0; i < lb.length; i++) {
                 if (lb[i] != null && !lb[i].isRecycled()) {
                     lb[i].recycle();
                     Log.d(TAG, "Recycled layerBitmap[" + i + "]");
                 }
             }
         }
    }

    // --- Диалог выбора слоев ---

    private void showLayerSelectionDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_layer_selection);
        dialog.setTitle(R.string.layer_selection_title);

        RecyclerView recyclerView = dialog.findViewById(R.id.layerRecyclerView); // Используем ID из dialog_layer_selection.xml
        if (recyclerView == null) {
            Log.e(TAG, "RecyclerView (R.id.layerRecyclerView) not found in dialog layout!");
            Toast.makeText(this, "Error creating layer dialog", Toast.LENGTH_SHORT).show();
            return;
        }

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        // Используем исправленный LayerAdapter
        LayerAdapter adapter = new LayerAdapter(PENCIL_HARDNESS, layerVisibility, this);
        recyclerView.setAdapter(adapter);

        dialog.show();
    }

    // Колбэк из LayerAdapter
    @Override
    public void onLayerVisibilityChanged(int position, boolean isVisible) {
        if (position >= 0 && position < layerVisibility.length) {
            layerVisibility[position] = isVisible;
            Log.d(TAG, "Layer " + position + " (" + PENCIL_HARDNESS[position] + ") visibility changed to: " + isVisible);
            updateImageDisplay(); // Обновляем отображение
        } else {
            Log.w(TAG, "Invalid position received from LayerAdapter: " + position);
        }
    }

    // --- Сохранение/Загрузка параметров ---

    private void checkPermissionAndSaveParameters() {
         // Используем SAF, разрешения не нужны
         openSaveFilePicker();
    }

    private void checkPermissionAndLoadParameters() {
         // Используем SAF, разрешения не нужны
         openLoadFilePicker();
    }

    private void openSaveFilePicker() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json"); // Указываем тип файла
        intent.putExtra(Intent.EXTRA_TITLE, "camera_overlay_params.json"); // Имя файла по умолчанию
        try {
            saveFileLauncher.launch(intent);
        } catch (Exception e) {
            Log.e(TAG, "Could not launch save file picker", e);
            Toast.makeText(this, "Error opening save dialog", Toast.SHORT).show();
        }
    }

    private void openLoadFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json"); // Ищем только JSON файлы
        try {
            loadFileLauncher.launch(intent);
        } catch (Exception e) {
            Log.e(TAG, "Could not launch load file picker", e);
            Toast.makeText(this, "Error opening load dialog", Toast.SHORT).show();
        }
    }

    private void saveParametersToFile(Uri uri) {
        // Параметры сохраняются только если есть изображение, т.к. матрица зависит от него
        if (originalBitmap == null || originalBitmap.isRecycled()) {
            Toast.makeText(this, "Load an image first to save parameters", Toast.LENGTH_SHORT).show();
            return;
        }
         if (uri == null) {
             Log.e(TAG, "Save URI is null");
             return;
         }

        try {
            JSONObject json = new JSONObject();
            // Сохраняем URI текущего изображения (если есть)
            if (currentImageUri != null) {
                 json.put("imageUri", currentImageUri.toString());
            }
            // Сохраняем параметры трансформации
            JSONArray matrixArray = new JSONArray();
            float[] matrixValues = new float[9];
            matrix.getValues(matrixValues);
            for (float value : matrixValues) matrixArray.put(value);
            json.put("matrix", matrixArray);
            json.put("scaleFactor", scaleFactor); // Сохраняем текущий масштаб
            json.put("rotationAngle", rotationAngle); // Сохраняем угол

             // Сохраняем параметры UI
            json.put("transparency", transparencySeekBar.getProgress());
            json.put("isPencilMode", isPencilMode);
            json.put("isImageVisible", isImageVisible);
            json.put("controlsVisible", controlsVisibilityCheckbox.isChecked());

            // Сохраняем видимость слоев
            JSONArray visibilityArray = new JSONArray();
            for (boolean visible : layerVisibility) visibilityArray.put(visible);
            json.put("layerVisibility", visibilityArray);

            // Записываем JSON в файл через ContentResolver
            ContentResolver resolver = getContentResolver();
            try (OutputStream outputStream = resolver.openOutputStream(uri)) {
                if (outputStream != null) {
                    outputStream.write(json.toString(2).getBytes()); // toString(2) для красивого вывода
                    Toast.makeText(this, "Parameters saved", Toast.LENGTH_SHORT).show();
                     Log.d(TAG, "Parameters saved to " + uri);
                } else {
                    throw new IOException("OutputStream is null");
                }
            }
        } catch (Exception e) { // Ловим JSONException и IOException
            Log.e(TAG, "Error saving parameters to file", e);
            Toast.makeText(this, "Failed to save parameters", Toast.LENGTH_LONG).show();
        }
    }

    private void loadParametersFromFile(Uri uri) {
         if (uri == null) {
             Log.e(TAG, "Load URI is null");
             return;
         }
         Log.d(TAG, "Loading parameters from URI: " + uri);
        try {
            ContentResolver resolver = getContentResolver();
            try (InputStream inputStream = resolver.openInputStream(uri)) {
                if (inputStream != null) {
                    // Читаем содержимое файла
                    StringBuilder jsonString = new StringBuilder();
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        jsonString.append(new String(buffer, 0, bytesRead));
                    }

                    // Парсим JSON
                    JSONObject json = new JSONObject(jsonString.toString());

                    // Сначала проверяем, есть ли ссылка на изображение
                    Uri loadedImageUri = null;
                    if (json.has("imageUri")) {
                         loadedImageUri = Uri.parse(json.getString("imageUri"));
                    }

                     // Загружаем изображение, если URI изменился или изображения нет
                     if (loadedImageUri != null && !loadedImageUri.equals(currentImageUri)) {
                         Log.d(TAG, "Loading image specified in parameters: " + loadedImageUri);
                         currentImageUri = loadedImageUri; // Устанавливаем новый URI
                         // Запускаем загрузку изображения. Параметры применятся ПОСЛЕ загрузки.
                         loadImage(currentImageUri);
                         // Сохраняем остальные параметры для применения после загрузки
                         // (Это сложная логика, пока просто загрузим параметры, а потом изображение)
                         // Лучше сначала загрузить изображение, а потом параметры.
                         // Переделываем: Сначала просто загружаем параметры UI
                     }
                     // Если изображение то же или его не было в файле, просто грузим параметры
                     applyLoadedParameters(json);


                } else {
                    throw new IOException("InputStream is null");
                }
            }
        } catch (Exception e) { // Ловим JSONException и IOException
            Log.e(TAG, "Error loading parameters from file", e);
            Toast.makeText(this, "Failed to load parameters", Toast.LENGTH_LONG).show();
        }
    }

     // Применение загруженных параметров (вызывается после парсинга JSON)
     private void applyLoadedParameters(JSONObject json) throws Exception { // Может бросить JSONException, NumberFormatException
         Log.d(TAG, "Applying loaded parameters...");
         // Загружаем матрицу и связанные параметры
         if (json.has("matrix")) {
             JSONArray matrixArray = json.getJSONArray("matrix");
             if (matrixArray.length() >= 9) {
                 float[] matrixValues = new float[9];
                 for (int i = 0; i < 9; i++) matrixValues[i] = (float) matrixArray.getDouble(i);
                 matrix.setValues(matrixValues);
             }
         }
          if (json.has("scaleFactor")) scaleFactor = (float) json.getDouble("scaleFactor");
          if (json.has("rotationAngle")) rotationAngle = (float) json.getDouble("rotationAngle");

         // Загружаем параметры UI
         if (json.has("transparency")) transparencySeekBar.setProgress(json.getInt("transparency"));
         if (json.has("isPencilMode")) isPencilMode = json.getBoolean("isPencilMode");
         if (json.has("isImageVisible")) isImageVisible = json.getBoolean("isImageVisible");
         boolean controlsVisible = true; // По умолчанию показываем
         if (json.has("controlsVisible")) controlsVisible = json.getBoolean("controlsVisible");

         // Загружаем видимость слоев
         if (json.has("layerVisibility")) {
             JSONArray visibilityArray = json.getJSONArray("layerVisibility");
             for (int i = 0; i < visibilityArray.length() && i < layerVisibility.length; i++) {
                 layerVisibility[i] = visibilityArray.getBoolean(i);
             }
         }

         // Обновляем состояние UI в основном потоке
          final boolean finalControlsVisible = controlsVisible; // Для лямбды
         runOnUiThread(() -> {
             pencilModeSwitch.setChecked(isPencilMode);
             hideImageCheckbox.setChecked(!isImageVisible);
             controlsVisibilityCheckbox.setChecked(finalControlsVisible); // Обновляем чекбокс видимости контролов
             updateControlsVisibility(finalControlsVisible); // Применяем видимость контролов
             // Transparency SeekBar уже обновлен
             applyTransformations(); // Применяем загруженную матрицу
             // Если режим карандаша включен, запускаем его обработку (в фоне)
              if (isPencilMode && originalBitmap != null && !originalBitmap.isRecycled()) {
                  imageLoadExecutor.submit(this::processPencilEffect);
              } else {
                  updateImageDisplay(); // Обновляем отображение
              }
             Toast.makeText(this, "Parameters applied", Toast.LENGTH_SHORT).show();
             Log.d(TAG, "Parameters applied successfully.");
         });
     }


    // --- Жизненный цикл Activity ---

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        hideSystemUI(); // Скрываем UI при возвращении
        // Запускаем фоновый поток и пытаемся открыть камеру, если нужно
        startBackgroundThread();
        if (cameraSurfaceHolder != null && cameraSurfaceHolder.getSurface() != null && cameraSurfaceHolder.getSurface().isValid()
                && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && !isCameraOpen) {
             Log.d(TAG, "Opening camera from onResume");
            openCamera();
        }
         // Перерисовываем изображение на случай, если оно было загружено, пока Activity была на паузе
         updateImageDisplay();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
        closeCamera(); // Закрываем камеру
        // Не нужно останавливать ExecutorService здесь, он нужен для фоновых задач
        // stopBackgroundThread(); // Теперь не используется
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        closeCamera(); // Убедимся, что камера закрыта
        cameraExecutor.shutdown(); // Останавливаем потоки
        imageLoadExecutor.shutdown();
        recycleBitmaps(); // Освобождаем все битмапы
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        Log.d(TAG, "Saving instance state...");

        if (currentImageUri != null) {
            outState.putString(KEY_IMAGE_URI, currentImageUri.toString());
        }
        // Сохраняем матрицу только если есть изображение
        if (originalBitmap != null) {
             float[] matrixValues = new float[9];
             matrix.getValues(matrixValues);
             outState.putFloatArray(KEY_MATRIX_VALUES, matrixValues);
             outState.putFloat(KEY_SCALE_FACTOR, scaleFactor);
             outState.putFloat(KEY_ROTATION_ANGLE, rotationAngle);
        }

        if (controlsVisibilityCheckbox != null) {
            outState.putBoolean(KEY_CONTROLS_VISIBLE, controlsVisibilityCheckbox.isChecked());
        }
        outState.putBoolean(KEY_PENCIL_MODE, isPencilMode);
        outState.putBooleanArray(KEY_LAYER_VISIBILITY, layerVisibility);
        outState.putBoolean(KEY_IMAGE_VISIBLE, isImageVisible);
         if (currentCameraId != null) {
             outState.putString(KEY_CURRENT_CAMERA_ID, currentCameraId);
         }
    }

     // Восстановление состояния (вызывается после onCreate)
     private void restoreInstanceState(@NonNull Bundle savedInstanceState) {
         Log.d(TAG, "Restoring instance state...");
         String savedUriString = savedInstanceState.getString(KEY_IMAGE_URI);
         if (savedUriString != null) {
             currentImageUri = Uri.parse(savedUriString);
             Log.d(TAG, "Restored Image URI: " + currentImageUri);
         }

         float[] savedMatrixValues = savedInstanceState.getFloatArray(KEY_MATRIX_VALUES);
         if (savedMatrixValues != null) {
             matrix.setValues(savedMatrixValues);
             scaleFactor = savedInstanceState.getFloat(KEY_SCALE_FACTOR, 1.0f);
             rotationAngle = savedInstanceState.getFloat(KEY_ROTATION_ANGLE, 0.0f);
             Log.d(TAG, "Restored Matrix, Scale, Rotation");
             // Загружаем изображение, если URI восстановлен
             if (currentImageUri != null) {
                  loadImage(currentImageUri); // Загрузка запустит updateImageDisplay
             }
         } else if (currentImageUri != null) {
             // Если матрицы нет, но есть URI, загружаем и сбрасываем трансформации
             loadImage(currentImageUri);
         }

         isPencilMode = savedInstanceState.getBoolean(KEY_PENCIL_MODE, false);
         boolean[] savedLayerVisibility = savedInstanceState.getBooleanArray(KEY_LAYER_VISIBILITY);
         if (savedLayerVisibility != null && savedLayerVisibility.length == layerVisibility.length) {
             System.arraycopy(savedLayerVisibility, 0, layerVisibility, 0, layerVisibility.length);
         }
         isImageVisible = savedInstanceState.getBoolean(KEY_IMAGE_VISIBLE, true);
         boolean restoredControlsVisible = savedInstanceState.getBoolean(KEY_CONTROLS_VISIBLE, true);

         // Обновляем UI элементы
         pencilModeSwitch.setChecked(isPencilMode);
         hideImageCheckbox.setChecked(!isImageVisible);
         controlsVisibilityCheckbox.setChecked(restoredControlsVisible);
         updateControlsVisibility(restoredControlsVisible); // Применяем видимость
         setImageAlpha(transparencySeekBar.getProgress()); // Прозрачность не сохраняли, берем текущую

         // Восстанавливаем ID камеры, если нужно
         currentCameraId = savedInstanceState.getString(KEY_CURRENT_CAMERA_ID, currentCameraId);
     }


    // --- Обработка Касаний и Жестов ---

    private class TouchAndGestureListener implements View.OnTouchListener {
        PointF startDragPoint = new PointF();
        Matrix savedMatrix = new Matrix();

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (originalBitmap == null) return false; // Не обрабатываем, если нет изображения

            // Передаем событие в ScaleGestureDetector первым
            boolean scaleHandled = scaleGestureDetector.onTouchEvent(event);

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
                        savedMatrix.set(matrix); // Сохраняем перед началом масштабирования/вращения
                        // initialDistance = spacing(event); // Расстояние для масштаба (ScaleGestureDetector сам справится)
                        initialAngle = rotation(event); // Начальный угол для вращения
                        midPoint(midPoint, event); // Центр между пальцами
                        touchMode = ZOOM; // Используем ZOOM как общий режим для 2+ пальцев
                        Log.d(TAG, "Touch Mode: ZOOM/ROTATE");
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (touchMode == DRAG && pointerCount == 1 && !scaleGestureDetector.isInProgress()) {
                        // Перетаскивание
                        matrix.set(savedMatrix); // Восстанавливаем матрицу на начало жеста
                        float dx = event.getX() - startDragPoint.x;
                        float dy = event.getY() - startDragPoint.y;
                        matrix.postTranslate(dx, dy); // Применяем смещение
                    } else if (touchMode == ZOOM && pointerCount >= 2) {
                         // Масштабирование обрабатывается в ScaleListener
                         // Вращение:
                         float currentAngle = rotation(event);
                         float deltaAngle = currentAngle - initialAngle;
                         // Обновляем центр между пальцами
                         midPoint(midPoint, event);
                         // Восстанавливаем матрицу (важно, т.к. ScaleListener ее тоже меняет)
                         matrix.set(savedMatrix);
                         // Применяем текущий накопленный масштаб из ScaleGestureDetector
                         matrix.postScale(scaleFactor/getMatrixScale(savedMatrix), scaleFactor/getMatrixScale(savedMatrix), midPoint.x, midPoint.y);
                         // Применяем вращение
                         matrix.postRotate(deltaAngle, midPoint.x, midPoint.y);
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_CANCEL:
                    Log.d(TAG, "Touch Mode: NONE");
                    touchMode = NONE;
                    // Можно сохранить текущий угол вращения, если нужно
                    // rotationAngle = getMatrixRotation(matrix); // (Требует реализации getMatrixRotation)
                    break;
            }

            // Применяем итоговую матрицу к ImageView
            // Делаем это здесь, чтобы не вызывать слишком часто в ACTION_MOVE
             if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
                 applyTransformations();
             }

            return true; // Возвращаем true, чтобы получать все события касания
        }

        // Вспомогательные методы для жестов
        private float spacing(MotionEvent event) {
            if (event.getPointerCount() < 2) return 0f;
            float x = event.getX(0) - event.getX(1);
            float y = event.getY(0) - event.getY(1);
            return (float) Math.sqrt(x * x + y * y);
        }

        private void midPoint(PointF point, MotionEvent event) {
            if (event.getPointerCount() < 2) {
                point.set(event.getX(), event.getY());
                return;
            }
            float x = event.getX(0) + event.getX(1);
            float y = event.getY(0) + event.getY(1);
            point.set(x / 2f, y / 2f);
        }

        private float rotation(MotionEvent event) {
            if (event.getPointerCount() < 2) return 0f;
            double delta_x = (event.getX(0) - event.getX(1));
            double delta_y = (event.getY(0) - event.getY(1));
            double radians = Math.atan2(delta_y, delta_x);
            return (float) Math.toDegrees(radians);
        }
         // Вспомогательный метод для получения текущего масштаба матрицы
         private float getMatrixScale(Matrix mat) {
             float[] values = new float[9];
             mat.getValues(values);
             // Используем среднее от масштаба по X и Y
             float scaleX = values[Matrix.MSCALE_X];
             float scaleY = values[Matrix.MSCALE_Y];
             return (float) Math.sqrt(scaleX * scaleX + values[Matrix.MSKEW_Y] * values[Matrix.MSKEW_Y]); // Более точный способ
         }
    }

    // Слушатель для ScaleGestureDetector
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
             if (originalBitmap == null) return false;
             // Сохраняем матрицу в начале масштабирования
             // (Это может конфликтовать с ACTION_POINTER_DOWN, нужно проверить)
             // savedMatrix.set(matrix); // Возможно, не нужно, т.к. savedMatrix уже есть в TouchListener
             touchMode = ZOOM; // Устанавливаем режим
             return true; // Начинаем обработку
        }

        @Override
        public boolean onScale(@NonNull ScaleGestureDetector detector) {
             if (originalBitmap == null || touchMode != ZOOM) return false;

             float scaleChange = detector.getScaleFactor(); // Относительное изменение масштаба
             float focusX = detector.getFocusX();
             float focusY = detector.getFocusY();

             // Применяем масштабирование к текущей матрице
             matrix.postScale(scaleChange, scaleChange, focusX, focusY);
             // Обновляем общий scaleFactor (может быть полезно для ограничений)
             scaleFactor = getMatrixScale(matrix);

             // Ограничиваем масштаб (опционально)
             // float currentScale = getMatrixScale(matrix);
             // if (scaleChange > 1 && currentScale > 10.0f) return false; // Пример ограничения макс. масштаба
             // if (scaleChange < 1 && currentScale < 0.1f) return false; // Пример ограничения мин. масштаба

             applyTransformations(); // Применяем изменения к ImageView
             return true; // Продолжаем обработку
        }

         @Override
         public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
             // touchMode = NONE; // Возвращаем режим после завершения масштаба (делается в ACTION_UP/POINTER_UP)
             super.onScaleEnd(detector);
         }

         // Вспомогательный метод для получения текущего масштаба матрицы
         private float getMatrixScale(Matrix mat) {
             float[] values = new float[9];
             mat.getValues(values);
             // Используем масштаб по X (или среднее, или гипотенузу)
             return values[Matrix.MSCALE_X];
         }
    }

    // --- Вспомогательный метод для получения Display ---
    // Делаем его public, чтобы не конфликтовать с родительским
    public Display getDisplay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            if (displayManager != null) {
                return displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            } else {
                Log.e(TAG, "DisplayManager service not found!");
                // Fallback
                //noinspection deprecation
                return getWindowManager().getDefaultDisplay();
            }
        } else {
            // Устаревший метод
            //noinspection deprecation
            return getWindowManager().getDefaultDisplay();
        }
    }
}
