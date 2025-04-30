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
import android.net.Uri;
import android.os.Build;
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
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private int currentRearCameraIndex = 0;
    private ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private ExecutorService imageLoadExecutor = Executors.newSingleThreadExecutor();
    private HandlerThread cameraHandlerThread;
    private Handler cameraHandler;

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
    private boolean[] layerVisibility = new boolean[20]; // Для 9H, 8H, ..., 9B
    private static final String[] PENCIL_HARDNESS = {
            "9H", "8H", "7H", "6H", "5H", "4H", "3H", "2H", "H", "F",
            "HB", "B", "2B", "3B", "4B", "5B", "6B", "7B", "8B", "9B"
    };

    // Управление видимостью изображения
    private boolean isImageVisible = true;

    // Распознавание жестов
    private ScaleGestureDetector scaleGestureDetector;
    private float lastTouchX;
    private float lastTouchY;
    private float posX, posY;

    // Константы для режимов касания
    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    private static final int ROTATE = 3;
    private int touchMode = NONE;

    // Для вращения
    private PointF startPoint = new PointF();
    private PointF midPoint = new PointF();
    private float initialAngle = 0f;

    // Activity Result API
    private ActivityResultLauncher<Intent> imagePickerLauncher;
    private ActivityResultLauncher<Intent> saveFileLauncher;
    private ActivityResultLauncher<Intent> loadFileLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Устанавливаем layout перед настройкой окна
        setContentView(R.layout.activity_main);

        // Полноэкранный режим с учётом новых API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Window window = getWindow();
            if (window != null) {
                window.setDecorFitsSystemWindows(false);
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    Log.w(TAG, "WindowInsetsController is null, falling back to older API");
                    // Fallback для старых API
                    window.setFlags(
                            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                            android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
                    );
                }
            } else {
                Log.e(TAG, "Window is null, falling back to older API");
                // Fallback
                getWindow().setFlags(
                        android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                        android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
                );
            }
        } else {
            getWindow().setFlags(
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
            );
        }
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        // Инициализация UI
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

        // Инициализация Activity Result Launcher
        imagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri selectedImageUri = result.getData().getData();
                        if (selectedImageUri != null) {
                            currentImageUri = selectedImageUri;
                            Log.d(TAG, "Image selected: " + currentImageUri);
                            loadImage(currentImageUri, false);
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

        // Настройка слушателей
        pickImageButton.setOnClickListener(v -> checkPermissionAndPickImage());
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
        controlsVisibilityCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> updateControlsVisibility(isChecked));
        pencilModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isPencilMode = isChecked;
            layerSelectButton.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            updateImageDisplay();
        });
        layerSelectButton.setOnClickListener(v -> showLayerSelectionDialog());
        hideImageCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isImageVisible = !isChecked;
            updateImageDisplay();
        });
        saveParametersButton.setOnClickListener(v -> checkPermissionAndSaveParameters());
        loadParametersButton.setOnClickListener(v -> checkPermissionAndLoadParameters());
        switchCameraButton.setOnClickListener(v -> {
            closeCamera();
            currentRearCameraIndex = (currentRearCameraIndex + 1) % rearCameraIds.size();
            currentCameraId = rearCameraIds.get(currentRearCameraIndex);
            Log.d(TAG, "Switched to camera ID: " + currentCameraId);
            openCamera();
        });

        // Инициализация layerVisibility
        for (int i = 0; i < layerVisibility.length; i++) {
            layerVisibility[i] = true;
        }

        // Настройка распознавания жестов
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());
        imageView.setOnTouchListener(new MyTouchListener());

        // Настройка SurfaceHolder для камеры
        cameraSurfaceHolder = cameraSurfaceView.getHolder();
        cameraSurfaceHolder.addCallback(this);

        // Инициализация Camera2
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        setupCameraSelector();

        // Запуск фонового потока для операций с камерой
        startCameraBackgroundThread();

        // Запрос разрешения на использование камеры
        checkCameraPermission();

        // Восстановление состояния
        boolean restoredControlsVisible = true;
        if (savedInstanceState != null) {
            Log.d(TAG, "Restoring state...");
            String savedUriString = savedInstanceState.getString(KEY_IMAGE_URI);
            if (savedUriString != null) {
                currentImageUri = Uri.parse(savedUriString);
                Log.d(TAG, "Restored Image URI: " + currentImageUri);
            }

            scaleFactor = savedInstanceState.getFloat(KEY_SCALE_FACTOR, 1.0f);
            rotationAngle = savedInstanceState.getFloat(KEY_ROTATION_ANGLE, 0.0f);

            float[] savedMatrixValues = savedInstanceState.getFloatArray(KEY_MATRIX_VALUES);
            if (savedMatrixValues != null) {
                matrix.setValues(savedMatrixValues);
                Log.d(TAG, "Restored Matrix");
                if (currentImageUri != null) {
                    loadImage(currentImageUri, true);
                }
            } else if (currentImageUri != null) {
                loadImage(currentImageUri, false);
            }

            setImageAlpha(transparencySeekBar.getProgress());

            restoredControlsVisible = savedInstanceState.getBoolean(KEY_CONTROLS_VISIBLE, true);
            controlsVisibilityCheckbox.setChecked(restoredControlsVisible);

            isPencilMode = savedInstanceState.getBoolean("isPencilMode", false);
            boolean[] savedLayerVisibility = savedInstanceState.getBooleanArray("layerVisibility");
            if (savedLayerVisibility != null && savedLayerVisibility.length == layerVisibility.length) {
                System.arraycopy(savedLayerVisibility, 0, layerVisibility, 0, layerVisibility.length);
            }
            pencilModeSwitch.setChecked(isPencilMode);
            layerSelectButton.setVisibility(isPencilMode ? View.VISIBLE : View.GONE);

            isImageVisible = savedInstanceState.getBoolean(KEY_IMAGE_VISIBLE, true);
            hideImageCheckbox.setChecked(!isImageVisible);
        } else {
            hideImageCheckbox.setChecked(false);
            isImageVisible = true;
        }

        updateControlsVisibility(restoredControlsVisible);
    }

    private void startCameraBackgroundThread() {
        cameraHandlerThread = new HandlerThread("CameraBackground");
        cameraHandlerThread.start();
        cameraHandler = new Handler(cameraHandlerThread.getLooper());
    }

    private void stopCameraBackgroundThread() {
        if (cameraHandlerThread != null) {
            cameraHandlerThread.quitSafely();
            if (cameraHandler != null) {
                cameraHandler.removeCallbacksAndMessages(null); // Удаляем все ожидающие обратные вызовы
            }
            try {
                cameraHandlerThread.join();
                cameraHandlerThread = null;
                cameraHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping camera background thread", e);
            }
        }
    }

    private void checkPermissionAndSaveParameters() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ?
                Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.WRITE_EXTERNAL_STORAGE;

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{permission}, WRITE_STORAGE_PERMISSION_CODE);
        } else {
            openSaveFilePicker();
        }
    }

    private void checkPermissionAndLoadParameters() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ?
                Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{permission}, STORAGE_PERMISSION_CODE);
        } else {
            openLoadFilePicker();
        }
    }

    private void openSaveFilePicker() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "image_parameters.json");
        try {
            saveFileLauncher.launch(intent);
        } catch (Exception e) {
            Log.e(TAG, "Не удалось открыть диалог сохранения файла", e);
            Toast.makeText(this, "Ошибка при открытии диалога сохранения", Toast.LENGTH_LONG).show();
        }
    }

    private void openLoadFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        try {
            loadFileLauncher.launch(intent);
        } catch (Exception e) {
            Log.e(TAG, "Не удалось открыть диалог загрузки файла", e);
            Toast.makeText(this, "Ошибка при открытии диалога загрузки", Toast.LENGTH_LONG).show();
        }
    }

    private void saveParametersToFile(Uri uri) {
        if (originalBitmap == null) {
            Toast.makeText(this, "Нет изображения для сохранения параметров", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            JSONObject json = new JSONObject();
            json.put("scaleFactor", scaleFactor);
            json.put("rotationAngle", rotationAngle);
            json.put("transparency", transparencySeekBar.getProgress());
            json.put("isPencilMode", isPencilMode);
            json.put("isImageVisible", isImageVisible);
            JSONArray matrixArray = new JSONArray();
            float[] matrixValues = new float[9];
            matrix.getValues(matrixValues);
            for (float value : matrixValues) {
                matrixArray.put(value);
            }
            json.put("matrix", matrixArray);
            JSONArray visibilityArray = new JSONArray();
            for (boolean visible : layerVisibility) {
                visibilityArray.put(visible);
            }
            json.put("layerVisibility", visibilityArray);

            ContentResolver resolver = getContentResolver();
            try (OutputStream outputStream = resolver.openOutputStream(uri)) {
                if (outputStream != null) {
                    outputStream.write(json.toString().getBytes());
                    Toast.makeText(this, "Параметры сохранены", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка сохранения параметров", e);
            Toast.makeText(this, "Не удалось сохранить параметры", Toast.LENGTH_LONG).show();
        }
    }

    private void loadParametersFromFile(Uri uri) {
        if (originalBitmap == null) {
            Toast.makeText(this, "Сначала загрузите изображение", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            ContentResolver resolver = getContentResolver();
            try (InputStream inputStream = resolver.openInputStream(uri)) {
                if (inputStream != null) {
                    StringBuilder jsonString = new StringBuilder();
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        jsonString.append(new String(buffer, 0, bytesRead));
                    }

                    JSONObject json = new JSONObject(jsonString.toString());
                    scaleFactor = (float) json.getDouble("scaleFactor");
                    rotationAngle = (float) json.getDouble("rotationAngle");
                    transparencySeekBar.setProgress(json.getInt("transparency"));
                    isPencilMode = json.getBoolean("isPencilMode");
                    isImageVisible = json.getBoolean("isImageVisible");

                    JSONArray matrixArray = json.getJSONArray("matrix");
                    float[] matrixValues = new float[9];
                    for (int i = 0; i < matrixArray.length(); i++) {
                        matrixValues[i] = (float) matrixArray.getDouble(i);
                    }
                    matrix.setValues(matrixValues);

                    JSONArray visibilityArray = json.getJSONArray("layerVisibility");
                    for (int i = 0; i < visibilityArray.length() && i < layerVisibility.length; i++) {
                        layerVisibility[i] = visibilityArray.getBoolean(i);
                    }

                    pencilModeSwitch.setChecked(isPencilMode);
                    hideImageCheckbox.setChecked(!isImageVisible);
                    layerSelectButton.setVisibility(isPencilMode ? View.VISIBLE : View.GONE);
                    setImageAlpha(transparencySeekBar.getProgress());
                    updateImageDisplay();
                    imageView.setImageMatrix(matrix);

                    Toast.makeText(this, "Параметры загружены", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Ошибка загрузки параметров", e);
            Toast.makeText(this, "Не удалось загрузить параметры", Toast.LENGTH_LONG).show();
        }
    }

    private void checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        } else {
            Log.d(TAG, "Camera permission already granted.");
            setupCameraSelector();
            openCamera();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Camera Permission Granted", Toast.LENGTH_SHORT).show();
                setupCameraSelector();
                openCamera();
            } else {
                Toast.makeText(this, "Camera Permission Denied", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Camera permission denied.");
            }
        } else if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Storage Permission Granted", Toast.LENGTH_SHORT).show();
                openLoadFilePicker();
            } else {
                Toast.makeText(this, "Storage Permission Denied", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Storage permission denied.");
            }
        } else if (requestCode == WRITE_STORAGE_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Write Permission Granted", Toast.LENGTH_SHORT).show();
                openSaveFilePicker();
            } else {
                Toast.makeText(this, "Write Permission Denied", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Write permission denied.");
            }
        }
    }

    private void setupCameraSelector() {
        try {
            cameraIds = cameraManager.getCameraIdList();
            rearCameraIds.clear();
            int defaultIndex = -1;

            for (int i = 0; i < cameraIds.length; i++) {
                String cameraId = cameraIds[i];
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    rearCameraIds.add(cameraId);
                    if (defaultIndex == -1) {
                        defaultIndex = i; // Первая задняя камера по умолчанию
                    }
                }
                // Логирование характеристик камеры для отладки
                logCameraCharacteristics(cameraId);
            }

            if (defaultIndex != -1) {
                currentCameraId = cameraIds[defaultIndex];
            } else if (cameraIds.length > 0) {
                currentCameraId = cameraIds[0];
            } else {
                Log.e(TAG, "No cameras available.");
                Toast.makeText(this, "No cameras available on this device", Toast.LENGTH_LONG).show();
                return;
            }

            currentRearCameraIndex = rearCameraIds.indexOf(currentCameraId);
            if (rearCameraIds.size() > 1) {
                switchCameraButton.setVisibility(View.VISIBLE);
            } else {
                switchCameraButton.setVisibility(View.GONE);
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error accessing camera characteristics", e);
            Toast.makeText(this, "Cannot access cameras: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void logCameraCharacteristics(String cameraId) {
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] previewSizes = map.getOutputSizes(SurfaceTexture.class);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            Float focalLength = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)[0];
            Log.d(TAG, "Camera " + cameraId + ": Facing: " + (facing == CameraCharacteristics.LENS_FACING_BACK ? "BACK" : "FRONT"));
            Log.d(TAG, "Camera " + cameraId + ": Focal Length: " + focalLength);
            Log.d(TAG, "Camera " + cameraId + ": Preview Sizes: " + Arrays.toString(previewSizes));
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error logging camera characteristics for camera " + cameraId, e);
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

    private void updateControlsVisibility(boolean show) {
        int visibility = show ? View.VISIBLE : View.GONE;
        String checkboxText = show ? getString(R.string.show_controls) : "";
        String imageCheckboxText = show ? getString(R.string.hide_image) : "";

        pickImageButton.setVisibility(visibility);
        transparencySeekBar.setVisibility(visibility);
        pencilModeSwitch.setVisibility(visibility);
        layerSelectButton.setVisibility(show && isPencilMode ? View.VISIBLE : View.GONE);
        saveParametersButton.setVisibility(visibility);
        loadParametersButton.setVisibility(visibility);
        switchCameraButton.setVisibility(show && rearCameraIds.size() > 1 ? View.VISIBLE : View.GONE);
        controlsVisibilityCheckbox.setText(checkboxText);
        hideImageCheckbox.setText(imageCheckboxText);
        hideImageCheckbox.setVisibility(View.VISIBLE);

        Log.d(TAG, "Controls visibility updated: " + (show ? "VISIBLE" : "GONE"));
        updateImageDisplay();
    }

    private void checkPermissionAndPickImage() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ?
                Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;

        if (ContextCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{permission}, STORAGE_PERMISSION_CODE);
        } else {
            Log.d(TAG, "Storage permission granted.");
            openImagePicker();
        }
    }

    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        try {
            imagePickerLauncher.launch(Intent.createChooser(intent, "Select Picture"));
            Log.d(TAG, "Starting image picker activity.");
        } catch (Exception ex) {
            Log.e(TAG, "No activity found to handle image picking.", ex);
            Toast.makeText(this, "Please install a File Manager: " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void loadImage(Uri uri, boolean isRestoring) {
        if (uri == null) {
            Log.e(TAG, "Cannot load image, URI is null.");
            return;
        }
        new LoadImageTask(this, isRestoring).execute(uri);
    }

    private class LoadImageTask {
        private final MainActivity activity;
        private final Uri imageUri;
        private final boolean isRestoring;

        LoadImageTask(MainActivity activity, boolean isRestoring) {
            this.activity = activity;
            this.imageUri = activity.currentImageUri;
            this.isRestoring = isRestoring;
        }

        void execute(Uri uri) {
            imageLoadExecutor.submit(() -> {
                Bitmap bitmap = loadBitmap();
                runOnUiThread(() -> postExecute(bitmap));
            });
        }

        private Bitmap loadBitmap() {
            if (activity == null || activity.isFinishing() || imageUri == null) return null;
            Log.d(TAG, "LoadImageTask: Starting background load for " + imageUri);

            ContentResolver resolver = activity.getContentResolver();
            InputStream inputStream = null;
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                inputStream = resolver.openInputStream(imageUri);
                BitmapFactory.decodeStream(inputStream, null, options);
                if (inputStream != null) inputStream.close();

                int reqWidth = 1280;
                int reqHeight = 720;
                options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
                options.inJustDecodeBounds = false;
                inputStream = resolver.openInputStream(imageUri);
                return BitmapFactory.decodeStream(inputStream, null, options);
            } catch (Exception e) {
                Log.e(TAG, "LoadImageTask: Error loading bitmap", e);
                return null;
            } finally {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (IOException e) {
                        Log.e(TAG, "LoadImageTask: Error closing input stream", e);
                    }
                }
            }
        }

        private void postExecute(Bitmap bitmap) {
            if (activity == null || activity.isFinishing()) {
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                return;
            }

            if (bitmap != null) {
                if (activity.originalBitmap != null && !activity.originalBitmap.isRecycled()) {
                    activity.originalBitmap.recycle();
                }
                activity.originalBitmap = bitmap;
                activity.pencilBitmap = null;
                activity.layerBitmaps = null;
                activity.updateImageDisplay();

                if (isRestoring) {
                    activity.imageView.post(() -> activity.imageView.setImageMatrix(activity.matrix));
                } else {
                    activity.resetTransformationsAndFit();
                }
            } else {
                Toast.makeText(activity, "Failed to load image", Toast.LENGTH_LONG).show();
                imageView.setImageBitmap(null);
                imageView.setVisibility(View.INVISIBLE);
                activity.originalBitmap = null;
                activity.pencilBitmap = null;
                activity.layerBitmaps = null;
                activity.currentImageUri = null;
                activity.matrix.reset();
                activity.scaleFactor = 1.0f;
                activity.rotationAngle = 0.0f;
            }
        }

        private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
            final int height = options.outHeight;
            final int width = options.outWidth;
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
    }

    private void resetTransformationsAndFit() {
        if (originalBitmap == null || imageView.getWidth() == 0 || imageView.getHeight() == 0) {
            matrix.reset();
            scaleFactor = 1.0f;
            rotationAngle = 0.0f;
            if (imageView != null) imageView.setImageMatrix(matrix);
            return;
        }

        matrix.reset();

        float viewWidth = imageView.getWidth();
        float viewHeight = imageView.getHeight();
        float bmpWidth = originalBitmap.getWidth();
        float bmpHeight = originalBitmap.getHeight();

        float scaleX = viewWidth / bmpWidth;
        float scaleY = viewHeight / bmpHeight;
        float initialScale = viewWidth / bmpWidth;

        float scaledBmpWidth = bmpWidth * initialScale;
        float scaledBmpHeight = bmpHeight * initialScale;
        float initialTranslateX = (viewWidth - scaledBmpWidth) / 2f;
        float initialTranslateY = (viewHeight - scaledBmpHeight) / 2f;

        matrix.postScale(initialScale, initialScale);
        matrix.postTranslate(initialTranslateX, initialTranslateY);

        imageView.post(() -> {
            imageView.setImageMatrix(matrix);
            imageView.invalidate();
            scaleFactor = 1.0f;
            rotationAngle = 0.0f;
        });
    }

    private void applyTransformations() {
        if (originalBitmap == null || imageView.getWidth() == 0 || imageView.getHeight() == 0) {
            return;
        }

        imageView.post(() -> {
            imageView.setImageMatrix(matrix);
            imageView.invalidate();
        });
    }

    private void setImageAlpha(int progress) {
        float alpha = (float) progress / 100.0f; // Convert progress (0-100) to 0.0-1.0
        if (imageView != null) {
            imageView.setAlpha(alpha);
            imageView.invalidate();
        }
    }

    private void processPencilEffect() {
        if (originalBitmap == null) {
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
            colorMatrix.setSaturation(0);
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
                int gray = Color.red(pixels[i]);
                int layerIndex = getLayerIndex(gray);
                if (layerIndex >= 0 && layerIndex < 20 && layerBitmaps[layerIndex] != null) {
                    layerBitmaps[layerIndex].setPixel(i % originalBitmap.getWidth(), i / originalBitmap.getWidth(), pixels[i]);
                }
            }
        } catch (OutOfMemoryError e) {
            Log.e(TAG, "OutOfMemoryError in processPencilEffect", e);
            Toast.makeText(this, "Not enough memory for pencil effect", Toast.LENGTH_LONG).show();
            pencilBitmap = null;
            layerBitmaps = null;
        }
    }

    private int getLayerIndex(int grayValue) {
        if (grayValue >= 243) return 0;  // 9H: 243–255
        if (grayValue >= 230) return 1;  // 8H: 230–242
        if (grayValue >= 217) return 2;  // 7H: 217–229
        if (grayValue >= 204) return 3;  // 6H: 204–216
        if (grayValue >= 191) return 4;  // 5H: 191–203
        if (grayValue >= 178) return 5;  // 4H: 178–190
        if (grayValue >= 166) return 6;  // 3H: 166–177
        if (grayValue >= 153) return 7;  // 2H: 153–165
        if (grayValue >= 140) return 8;  // H: 140–152
        if (grayValue >= 128) return 9;  // F: 128–139
        if (grayValue >= 115) return 10; // HB: 115–127
        if (grayValue >= 102) return 11; // B: 102–114
        if (grayValue >= 89) return 12;  // 2B: 89–101
        if (grayValue >= 77) return 13;  // 3B: 77–88
        if (grayValue >= 64) return 14;  // 4B: 64–76
        if (grayValue >= 51) return 15;  // 5B: 51–63
        if (grayValue >= 38) return 16;  // 6B: 38–50
        if (grayValue >= 26) return 17;  // 7B: 26–37
        if (grayValue >= 13) return 18;  // 8B: 13–25
        return 19; // 9B: 0–12
    }

    private void updateImageDisplay() {
        if (originalBitmap == null || !isImageVisible) {
            imageView.setImageBitmap(null);
            imageView.setVisibility(View.INVISIBLE);
            imageView.invalidate();
            return;
        }

        if (isPencilMode) {
            if (pencilBitmap == null || layerBitmaps == null) {
                processPencilEffect();
            }

            if (pencilBitmap == null || layerBitmaps == null) {
                imageView.setImageBitmap(null);
                imageView.setVisibility(View.INVISIBLE);
                imageView.invalidate();
                return;
            }

            Bitmap resultBitmap = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
            if (resultBitmap == null) {
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
        } else {
            imageView.setImageBitmap(originalBitmap);
            setImageAlpha(transparencySeekBar.getProgress());
            imageView.setVisibility(View.VISIBLE);
            imageView.post(() -> {
                imageView.setImageMatrix(matrix);
                imageView.invalidate();
            });
        }
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            if (originalBitmap == null) return false;

            float previousScaleFactor = scaleFactor;
            scaleFactor *= detector.getScaleFactor();
            scaleFactor = Math.max(0.1f, Math.min(scaleFactor, 5.0f));

            float scaleChange = scaleFactor / previousScaleFactor;
            matrix.postScale(scaleChange, scaleChange, detector.getFocusX(), detector.getFocusY());

            applyTransformations();
            return true;
        }
    }

    private class MyTouchListener implements View.OnTouchListener {
        private float lastEventX = 0, lastEventY = 0;
        private float initialDistance = 0f;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (originalBitmap == null) return false;
            boolean scaleHandled = scaleGestureDetector.onTouchEvent(event);

            int action = event.getActionMasked();
            int pointerCount = event.getPointerCount();

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    lastEventX = event.getX();
                    lastEventY = event.getY();
                    startPoint.set(event.getX(), event.getY());
                    touchMode = DRAG;
                    break;

                case MotionEvent.ACTION_POINTER_DOWN:
                    if (pointerCount >= 2) {
                        touchMode = ROTATE;
                        initialDistance = spacing(event);
                        initialAngle = rotation(event);
                        midPoint(midPoint, event);
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (touchMode == DRAG && pointerCount == 1 && !scaleGestureDetector.isInProgress()) {
                        float dx = event.getX() - lastEventX;
                        float dy = event.getY() - lastEventY;
                        matrix.postTranslate(dx, dy);
                        lastEventX = event.getX();
                        lastEventY = event.getY();
                        applyTransformations();
                    } else if (touchMode == ROTATE && pointerCount >= 2 && !scaleGestureDetector.isInProgress()) {
                        float currentAngle = rotation(event);
                        float deltaAngle = currentAngle - initialAngle;

                        midPoint(midPoint, event);
                        matrix.postRotate(deltaAngle, midPoint.x, midPoint.y);

                        initialAngle = currentAngle;
                        rotationAngle += deltaAngle;

                        applyTransformations();
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_CANCEL:
                    touchMode = NONE;
                    break;
            }

            return true;
        }

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
            point.set(x / 2, y / 2);
        }

        private float rotation(MotionEvent event) {
            if (event.getPointerCount() < 2) return 0f;
            double delta_x = (event.getX(0) - event.getX(1));
            double delta_y = (event.getY(0) - event.getY(1));
            double radians = Math.atan2(delta_y, delta_x);
            return (float) Math.toDegrees(radians);
        }
    }

    private void openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            checkCameraPermission();
            return;
        }

        try {
            cameraManager.openCamera(currentCameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    startCameraPreview();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    camera.close();
                    cameraDevice = null;
                    Toast.makeText(MainActivity.this, "Camera error: " + error, Toast.LENGTH_SHORT).show();
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error opening camera", e);
            Toast.makeText(this, "Cannot open camera", Toast.LENGTH_SHORT).show();
        }
    }

    private void closeCamera() {
        try {
            if (cameraCaptureSession != null) {
                cameraCaptureSession.close();
                cameraCaptureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing camera", e);
        }
    }

    private Size chooseOptimalPreviewSize(Size[] choices, int viewWidth, int viewHeight) {
        // Предпочитаем соотношение сторон, близкое к 4:3 или 16:9, в зависимости от сенсора
        double targetRatio;
        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(currentCameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size sensorSize = map.getOutputSizes(SurfaceTexture.class)[0]; // Пример: берём первый размер
            targetRatio = (double) sensorSize.getWidth() / sensorSize.getHeight();
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error accessing camera characteristics for preview size", e);
            targetRatio = (double) viewWidth / viewHeight; // Fallback
        }

        // Минимальная разница между соотношениями
        Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;

        for (Size size : choices) {
            double ratio = (double) size.getWidth() / size.getHeight();
            if (Math.abs(ratio - targetRatio) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(ratio - targetRatio);
            }
        }

        // Если ничего не нашли, берём первый доступный размер
        if (optimalSize == null && choices.length > 0) {
            optimalSize = choices[0];
        }

        Log.d(TAG, "Chosen preview size: " + optimalSize.getWidth() + "x" + optimalSize.getHeight());
        return optimalSize;
    }

    private void startCameraPreview() {
        if (cameraDevice == null || !cameraSurfaceHolder.getSurface().isValid()) {
            Log.w(TAG, "Cannot start camera preview: device or surface not ready");
            return;
        }

        try {
            // Получаем характеристики камеры
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(currentCameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] previewSizes = map.getOutputSizes(SurfaceTexture.class);

            // Выбираем оптимальный размер предпросмотра
            int viewWidth = cameraSurfaceView.getWidth();
            int viewHeight = cameraSurfaceView.getHeight();
            if (viewWidth == 0 || viewHeight == 0) {
                Log.w(TAG, "SurfaceView dimensions are 0, deferring preview start");
                return;
            }

            Size previewSize = chooseOptimalPreviewSize(previewSizes, viewWidth, viewHeight);

            // Настраиваем размер SurfaceView с учётом соотношения сторон
            float previewRatio = (float) previewSize.getWidth() / previewSize.getHeight();
            float viewRatio = (float) viewWidth / viewHeight;

            if (previewRatio > viewRatio) {
                // Если предпросмотр шире, чем SurfaceView, подгоняем по ширине
                int newHeight = (int) (viewWidth / previewRatio);
                cameraSurfaceHolder.setFixedSize(viewWidth, newHeight);
                Log.d(TAG, "Adjusted SurfaceView to " + viewWidth + "x" + newHeight);
            } else {
                // Если предпросмотр выше, подгоняем по высоте
                int newWidth = (int) (viewHeight * previewRatio);
                cameraSurfaceHolder.setFixedSize(newWidth, viewHeight);
                Log.d(TAG, "Adjusted SurfaceView to " + newWidth + "x" + viewHeight);
            }

            Surface surface = cameraSurfaceHolder.getSurface();
            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (cameraDevice == null) {
                        Log.e(TAG, "Camera device is null in onConfigured, aborting session setup");
                        session.close();
                        cameraCaptureSession = null;
                        return;
                    }
                    cameraCaptureSession = session;
                    try {
                        CaptureRequest.Builder requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        requestBuilder.addTarget(surface);
                        requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        if (cameraCaptureSession != null) {
                            cameraCaptureSession.setRepeatingRequest(requestBuilder.build(), null, cameraHandler);
                            Log.d(TAG, "Camera preview started successfully");
                        } else {
                            Log.e(TAG, "CameraCaptureSession is null, cannot set repeating request");
                        }
                    } catch (CameraAccessException | IllegalStateException e) {
                        Log.e(TAG, "Error setting up preview", e);
                        closeCamera();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Toast.makeText(MainActivity.this, "Failed to configure camera session", Toast.LENGTH_SHORT).show();
                    Log.e(TAG, "Failed to configure camera session");
                }
            }, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creating capture session", e);
            Toast.makeText(this, "Error creating camera session: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        Log.d(TAG, "Surface created.");
        openCamera();
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "Surface changed. New dimensions: " + width + "x" + height);
        startCameraPreview();
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        Log.d(TAG, "Surface destroyed.");
        closeCamera();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        startCameraBackgroundThread();
        if (cameraDevice == null && cameraSurfaceHolder != null && cameraSurfaceHolder.getSurface().isValid()) {
            openCamera();
        }
        if (currentImageUri != null && originalBitmap == null) {
            loadImage(currentImageUri, true);
        }
        updateImageDisplay();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
        closeCamera();
        stopCameraBackgroundThread();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        closeCamera();
        stopCameraBackgroundThread();
        cameraExecutor.shutdown();
        imageLoadExecutor.shutdown();
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
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        Log.d(TAG, "Saving instance state...");

        if (currentImageUri != null) {
            outState.putString(KEY_IMAGE_URI, currentImageUri.toString());
        }
        if (originalBitmap != null) {
            outState.putFloat(KEY_SCALE_FACTOR, scaleFactor);
            outState.putFloat(KEY_ROTATION_ANGLE, rotationAngle);
            float[] matrixValues = new float[9];
            matrix.getValues(matrixValues);
            outState.putFloatArray(KEY_MATRIX_VALUES, matrixValues);
        }
        if (controlsVisibilityCheckbox != null) {
            outState.putBoolean(KEY_CONTROLS_VISIBLE, controlsVisibilityCheckbox.isChecked());
        }
        outState.putBoolean("isPencilMode", isPencilMode);
        outState.putBooleanArray("layerVisibility", layerVisibility);
        outState.putBoolean(KEY_IMAGE_VISIBLE, isImageVisible);
    }
}
