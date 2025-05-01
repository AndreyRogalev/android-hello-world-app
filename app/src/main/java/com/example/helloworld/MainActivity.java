package com.example.helloworld;

import android.Manifest;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
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
import android.net.Uri;
import android.os.Build; // Импортируем Build
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.Display; // Импортируем Display
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
// import android.view.WindowManager; // Можно убрать, если используем getDisplay()
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
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit; // Импортируем TimeUnit

// Добавляем реализацию интерфейса из нашего адаптера
public class MainActivity extends AppCompatActivity implements LayerAdapter.OnLayerVisibilityChangedListener {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 100;
    private static final int REQUEST_STORAGE_PERMISSION = 101;
    private static final int PICK_IMAGE_REQUEST = 1;

    // UI элементы
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

    // Камера
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private String cameraId;
    private Size previewSize;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private final Semaphore cameraOpenCloseLock = new Semaphore(1);
    private volatile boolean isSurfaceAvailable = false;
    private volatile boolean isCameraPendingOpen = false;
    private volatile boolean isCameraOpen = false;
    private String[] cameraIds;
    private int currentCameraIndex = 0;


    // Изображение и его обработка
    private Bitmap originalBitmap;
    private Bitmap pencilBitmap;
    private Bitmap[] layerBitmaps; // Массив для слоев карандашного эффекта
    private boolean[] layerVisibility; // Видимость слоев
    private Matrix matrix = new Matrix(); // Матрица для трансформаций ImageView
    private float scaleFactor = 1.0f;
    private float rotationAngle = 0.0f; // (Пока не используется, но может пригодиться)
    private boolean isPencilMode = false;
    private boolean isImageVisible = true;
    private ScaleGestureDetector scaleGestureDetector; // Для масштабирования жестами
    private float lastTouchX, lastTouchY; // Для перетаскивания
    private boolean isDragging = false;

    // Константы для слоев
    private static final String[] PENCIL_HARDNESS = {
            "9H", "8H", "7H", "6H", "5H", "4H", "3H", "2H", "H", "F",
            "HB", "B", "2B", "3B", "4B", "5B", "6B", "7B", "8B", "9B"
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // --- Инициализация UI элементов ---
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

        // --- Настройка обработчиков жестов для ImageView ---
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scaleFactor *= detector.getScaleFactor();
                // Ограничиваем масштаб
                scaleFactor = Math.max(0.1f, Math.min(scaleFactor, 10.0f)); // Увеличил максимальный масштаб
                matrix.postScale(detector.getScaleFactor(), detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                applyTransformations();
                return true;
            }
        });

        imageView.setOnTouchListener((v, event) -> {
            scaleGestureDetector.onTouchEvent(event); // Передаем событие в ScaleGestureDetector
            final int action = event.getActionMasked();

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                    isDragging = true;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (isDragging && event.getPointerCount() == 1) { // Перетаскиваем только одним пальцем
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
                case MotionEvent.ACTION_POINTER_UP:
                    // Если отпустили второй палец (после масштабирования), пересчитываем lastTouch для корректного перетаскивания
                    if (event.getPointerCount() == 2) {
                        int newPointerIndex = (event.getActionIndex() == 0) ? 1 : 0;
                        lastTouchX = event.getX(newPointerIndex);
                        lastTouchY = event.getY(newPointerIndex);
                    }
                    break;

            }
            return true; // Возвращаем true, чтобы обрабатывать и перетаскивание, и масштабирование
        });

        // --- Настройка SurfaceView для камеры ---
        cameraSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                Log.d(TAG, "Surface created");
                isSurfaceAvailable = true;
                // Если камера ожидала открытия и поверхность готова, открываем
                if (isCameraPendingOpen && !isCameraOpen) {
                    openCamera();
                    isCameraPendingOpen = false; // Сбрасываем флаг ожидания
                }
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                Log.d(TAG, "Surface changed: " + width + "x" + height);
                // Подгоняем соотношение сторон SurfaceView под размер превью
                // adjustSurfaceViewAspectRatioWithCropping(width, height); // Вызов этой функции здесь может быть избыточен или вызывать проблемы, т.к. размер превью еще может быть не определен
                // Если камера открыта и поверхность изменилась, перезапускаем превью
                if (cameraDevice != null && isSurfaceAvailable && isCameraOpen) {
                    closeCameraPreviewSession(); // Закрываем старую сессию
                    // Пересчитываем оптимальный размер превью на случай изменения размера SurfaceView
                    previewSize = chooseOptimalPreviewSize(getPreviewSizes(), width, height);
                    createCameraPreviewSession(); // Создаем новую сессию
                }
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                Log.d(TAG, "Surface destroyed");
                isSurfaceAvailable = false;
                closeCamera(); // Закрываем камеру при уничтожении поверхности
            }
        });

        // --- Настройка слушателей для кнопок и контролов ---
        transparencySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setImageAlpha(progress); // Обновляем прозрачность ImageView
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        pickImageButton.setOnClickListener(v -> pickImage());

        pencilModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isPencilMode = isChecked;
            layerSelectButton.setVisibility(isChecked ? View.VISIBLE : View.GONE); // Показываем кнопку слоев только в режиме карандаша
            if (isPencilMode && originalBitmap != null) { // Обрабатываем эффект только если он включен и есть изображение
                processPencilEffect();
            }
            updateImageDisplay(); // Обновляем отображение
        });

        layerSelectButton.setOnClickListener(v -> showLayerSelectionDialog());

        controlsVisibilityCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            int visibility = isChecked ? View.VISIBLE : View.GONE;
            transparencySeekBar.setVisibility(visibility);
            pickImageButton.setVisibility(visibility);
            pencilModeSwitch.setVisibility(visibility);
            // Кнопка слоев зависит и от режима карандаша, и от этого чекбокса
            layerSelectButton.setVisibility(isPencilMode && isChecked ? View.VISIBLE : View.GONE);
            hideImageCheckbox.setVisibility(visibility);
            saveParametersButton.setVisibility(visibility);
            loadParametersButton.setVisibility(visibility);
            switchCameraButton.setVisibility(visibility);
        });

        hideImageCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isImageVisible = !isChecked; // Инвертируем состояние видимости
            updateImageDisplay();
        });

        saveParametersButton.setOnClickListener(v -> saveParameters());
        loadParametersButton.setOnClickListener(v -> loadParameters());
        switchCameraButton.setOnClickListener(v -> switchCamera());

        // --- Запрос разрешений ---
        checkAndRequestPermissions(); // Выносим запрос разрешений в отдельный метод

        // --- Инициализация списка камер ---
        setupCameraManager(); // Выносим инициализацию камеры в отдельный метод

        // Инициализация массива видимости слоев
        layerVisibility = new boolean[PENCIL_HARDNESS.length]; // Используем длину массива названий
        Arrays.fill(layerVisibility, true); // По умолчанию все слои видимы
    }

    // Метод для запроса разрешений
    private void checkAndRequestPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        } else {
            // Если разрешение уже есть, отмечаем, что камера ожидает открытия
             if (!isCameraOpen) isCameraPendingOpen = true;
        }

        // Запрашиваем разрешение на хранилище в зависимости от версии Android
        String storagePermission;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13+
            storagePermission = Manifest.permission.READ_MEDIA_IMAGES;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Android 10-12
             storagePermission = Manifest.permission.READ_EXTERNAL_STORAGE; // WRITE не нужен для доступа к галерее
        } else { // Android 9 (Pie) и ниже
            storagePermission = Manifest.permission.READ_EXTERNAL_STORAGE;
             // Для сохранения параметров может потребоваться WRITE_EXTERNAL_STORAGE, но лучше использовать внутреннее хранилище
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                 ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE_PERMISSION);
                 return; // Выходим, чтобы не запрашивать дважды
            }
        }

        if (ActivityCompat.checkSelfPermission(this, storagePermission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{storagePermission}, REQUEST_STORAGE_PERMISSION);
        }
    }

    // Инициализация CameraManager и списка камер
    private void setupCameraManager() {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        if (manager == null) {
            Log.e(TAG, "CameraManager is null.");
            Toast.makeText(this, "Cannot access Camera Service", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            cameraIds = manager.getCameraIdList();
            if (cameraIds.length > 0) {
                // Пытаемся найти заднюю камеру по умолчанию
                for (String id : cameraIds) {
                    CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        cameraId = id;
                        currentCameraIndex = Arrays.asList(cameraIds).indexOf(id);
                        Log.d(TAG, "Defaulting to back camera: " + cameraId);
                        return; // Нашли заднюю камеру, выходим
                    }
                }
                // Если задней нет, берем первую попавшуюся
                cameraId = cameraIds[0];
                currentCameraIndex = 0;
                Log.d(TAG, "No back camera found, defaulting to first camera: " + cameraId);
            } else {
                Log.e(TAG, "No cameras found on device.");
                Toast.makeText(this, "No cameras found", Toast.LENGTH_LONG).show();
                cameraId = null; // Указываем, что камеры нет
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error accessing camera list", e);
            Toast.makeText(this, "Cannot access cameras", Toast.LENGTH_LONG).show();
        } catch (SecurityException se) {
            Log.e(TAG, "Camera permission not granted during setup", se);
             // Разрешение уже должно было быть запрошено, но на всякий случай
        }
    }


    // Подгонка размера SurfaceView с обрезкой, чтобы сохранить соотношение сторон превью
    private void adjustSurfaceViewAspectRatioWithCropping(int viewWidth, int viewHeight) {
        // Этот метод сложен и часто не нужен, если SurfaceView занимает весь экран или его размер фиксирован.
        // Проще доверить отрисовку превью камере, она сама масштабирует.
        // Если вам нужна точная обрезка, реализация должна учитывать ориентацию сенсора и экрана.
        // Пока оставим его пустым или закомментируем, чтобы избежать потенциальных проблем с layout.
        Log.d(TAG, "AdjustSurfaceViewAspectRatioWithCropping called but currently disabled.");
    }


    // Обработка результата запроса разрешений
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Camera permission granted");
                // Если поверхность готова и камера еще не открыта, открываем
                if (isSurfaceAvailable && !isCameraOpen) {
                    openCamera();
                } else {
                    // Иначе отмечаем, что нужно открыть позже
                    isCameraPendingOpen = true;
                    Log.d(TAG, "Surface not ready or camera already open, setting pending open flag");
                }
            } else {
                Toast.makeText(this, "Camera permission is required to use the camera", Toast.LENGTH_LONG).show();
                // Возможно, стоит закрыть приложение или отключить функционал камеры
                // finish();
            }
        } else if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Storage permission granted");
                // Разрешение получено, можно работать с хранилищем
            } else {
                Toast.makeText(this, "Storage permission is required to pick images", Toast.LENGTH_LONG).show();
            }
        }
    }

    // Запуск фонового потока для операций с камерой
    private void startBackgroundThread() {
        // Только если поток еще не запущен
        if (backgroundThread == null || !backgroundThread.isAlive()) {
             stopBackgroundThread(); // На всякий случай останавливаем старый, если он завис
            backgroundThread = new HandlerThread("CameraBackground");
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());
            Log.d(TAG, "Background thread started");
        }
    }

    // Остановка фонового потока
    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join(500); // Ждем не более 500 мс
                if (backgroundThread.isAlive()) {
                    Log.w(TAG, "Background thread did not stop in time.");
                }
                backgroundThread = null;
                backgroundHandler = null;
                Log.d(TAG, "Background thread stopped");
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping background thread", e);
                Thread.currentThread().interrupt(); // Восстанавливаем флаг прерывания
            }
        }
    }

    // Открытие камеры
    private void openCamera() {
        // Проверяем разрешение еще раз перед открытием
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission check failed in openCamera.");
            isCameraPendingOpen = false;
            checkAndRequestPermissions(); // Запрашиваем снова
            return;
        }
        if (!isSurfaceAvailable) {
            Log.d(TAG, "Surface not available, setting pending open");
            isCameraPendingOpen = true;
            return;
        }
        if (isCameraOpen) {
            Log.d(TAG, "Camera already open, skipping open request");
            isCameraPendingOpen = false;
            return;
        }

        startBackgroundThread(); // Убеждаемся, что поток запущен
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        if (manager == null || cameraId == null) {
            Log.e(TAG, "CameraManager or CameraId is null in openCamera. Cannot open.");
             runOnUiThread(()-> Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show());
            isCameraPendingOpen = false;
            return;
        }

        try {
            // Получаем характеристики выбранной камеры
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Size[] outputSizes = null;
            if (characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) != null) {
                outputSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        .getOutputSizes(SurfaceHolder.class);
            } else {
                 Log.e(TAG, "StreamConfigurationMap is null for camera " + cameraId);
                 runOnUiThread(()->Toast.makeText(this, "Cannot get camera configurations", Toast.LENGTH_LONG).show());
                 isCameraPendingOpen = false;
                 return;
            }
            // Выбираем оптимальный размер превью до открытия камеры
            previewSize = chooseOptimalPreviewSize(outputSizes, cameraSurfaceView.getWidth(), cameraSurfaceView.getHeight());

            // Пытаемся получить блокировку семафора
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) { // Увеличили таймаут
                Log.e(TAG, "Time out waiting to lock camera opening.");
                isCameraPendingOpen = false; // Сбрасываем флаг, т.к. открыть не удалось
                return;
            }
            try {
                 Log.d(TAG, "Opening camera: " + cameraId);
                 isCameraOpen = true; // Устанавливаем флаг до вызова openCamera

                 manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                     @Override
                     public void onOpened(@NonNull CameraDevice camera) {
                         // Этот колбэк выполняется в backgroundHandler
                         Log.d(TAG, "Camera opened: " + camera.getId());
                         cameraDevice = camera;
                          // Если поверхность все еще доступна, создаем сессию превью
                         if (isSurfaceAvailable) {
                             createCameraPreviewSession();
                         } else {
                              Log.w(TAG, "Surface became unavailable after camera opened, closing camera");
                              closeCamera(); // Закрываем, так как показывать негде
                         }
                         cameraOpenCloseLock.release(); // Освобождаем семафор
                     }

                     @Override
                     public void onDisconnected(@NonNull CameraDevice camera) {
                         Log.w(TAG, "Camera disconnected: " + camera.getId());
                         cameraOpenCloseLock.release();
                         camera.close(); // Закрываем устройство
                         cameraDevice = null;
                         isCameraOpen = false;
                     }

                     @Override
                     public void onError(@NonNull CameraDevice camera, int error) {
                         Log.e(TAG, "Camera error: " + camera.getId() + ", error code: " + error);
                         cameraOpenCloseLock.release();
                         camera.close();
                         cameraDevice = null;
                         isCameraOpen = false;
                         // Показываем сообщение об ошибке пользователю В ОСНОВНОМ ПОТОКЕ
                         runOnUiThread(() -> Toast.makeText(MainActivity.this, "Camera error: " + error, Toast.LENGTH_LONG).show());
                     }
                 }, backgroundHandler); // Выполняем колбэки в фоновом потоке
            } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
                 // Обрабатываем возможные исключения при openCamera
                 Log.e(TAG, "Failed to open camera: " + cameraId, e);
                 runOnUiThread(() -> Toast.makeText(this, "Failed to open camera", Toast.LENGTH_LONG).show());
                 isCameraOpen = false;
                 cameraOpenCloseLock.release(); // Освобождаем лок при ошибке
            }
        } catch (CameraAccessException | IllegalArgumentException e) {
             Log.e(TAG, "Cannot access camera characteristics or invalid camera ID", e);
             runOnUiThread(() -> Toast.makeText(this, "Cannot get camera info", Toast.LENGTH_LONG).show());
             isCameraOpen = false; // Устанавливаем, что камера не открыта
        } catch (InterruptedException e) {
             Log.e(TAG, "Interrupted while waiting for camera lock", e);
             Thread.currentThread().interrupt();
             isCameraOpen = false;
        } finally {
             isCameraPendingOpen = false; // Сбрасываем флаг ожидания после попытки открытия
        }
    }


    // Получение доступных размеров превью
    private Size[] getPreviewSizes() {
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            if (manager == null || cameraId == null) return new Size[]{new Size(1280, 720)};
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            if (characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) != null) {
                return characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        .getOutputSizes(SurfaceHolder.class);
            } else {
                return new Size[]{new Size(1280, 720)};
            }
        } catch (CameraAccessException | IllegalArgumentException e) {
            Log.e(TAG, "Error getting preview sizes for camera " + cameraId, e);
            return new Size[]{new Size(1280, 720)};
        }
    }

    // Выбор оптимального размера превью
    private Size chooseOptimalPreviewSize(Size[] choices, int viewWidth, int viewHeight) {
        if (choices == null || choices.length == 0) {
            Log.e(TAG, "No preview sizes available, using default");
            return new Size(1280, 720);
        }
        int targetWidth = viewWidth > 0 ? viewWidth : 1280;
        int targetHeight = viewHeight > 0 ? viewHeight : 720;

        Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;

        // Предпочитаем размеры с соотношением сторон, близким к экрану
        double targetRatio = (double) targetWidth / targetHeight;
        for (Size size : choices) {
            if (size.getWidth() * size.getHeight() > 4000*3000) continue; // Игнорируем слишком большие разрешения
            double ratio = (double) size.getWidth() / size.getHeight();
            if (Math.abs(ratio - targetRatio) < 0.1) { // Допуск 0.1
                double diff = Math.abs(size.getHeight() - targetHeight);
                if (diff < minDiff) {
                    optimalSize = size;
                    minDiff = diff;
                }
            }
        }

        // Если не нашли с подходящим соотношением, ищем просто ближайший по высоте
        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (Size size : choices) {
                 if (size.getWidth() * size.getHeight() > 4000*3000) continue;
                double diff = Math.abs(size.getHeight() - targetHeight);
                if (diff < minDiff) {
                    optimalSize = size;
                    minDiff = diff;
                }
            }
        }

        if (optimalSize == null) optimalSize = choices[0]; // Последний шанс

        Log.d(TAG, "Chosen preview size: " + optimalSize.getWidth() + "x" + optimalSize.getHeight() + " for view size " + targetWidth + "x" + targetHeight);
        return optimalSize;
    }


    // Создание сессии превью камеры
    private void createCameraPreviewSession() {
        if (!isSurfaceAvailable || cameraDevice == null || !isCameraOpen) {
            Log.w(TAG, "Cannot create preview session: Surface not available=" + !isSurfaceAvailable
                    + ", cameraDevice is null=" + (cameraDevice == null)
                    + ", or camera is not open=" + !isCameraOpen);
            return;
        }

        try {
            final SurfaceHolder holder = cameraSurfaceView.getHolder();
            if (holder == null) {
                Log.e(TAG, "SurfaceHolder is null in createCameraPreviewSession");
                return;
            }
            Surface surface = holder.getSurface();
            if (surface == null || !surface.isValid()) {
                Log.e(TAG, "Surface is null or invalid, aborting preview session creation");
                return;
            }

            // Устанавливаем размер буфера SurfaceView В ОСНОВНОМ ПОТОКЕ
            if (previewSize != null) {
                final Size finalPreviewSize = previewSize; // Копия для лямбды
                runOnUiThread(() -> {
                    if (holder.getSurface().isValid()) { // Проверяем валидность еще раз
                        try {
                             holder.setFixedSize(finalPreviewSize.getWidth(), finalPreviewSize.getHeight());
                             Log.d(TAG, "Set SurfaceHolder fixed size (UI Thread) to: " + finalPreviewSize.getWidth() + "x" + finalPreviewSize.getHeight());
                        } catch (Exception e) { // Ловим возможные исключения
                             Log.e(TAG, "Error setting fixed size on UI thread", e);
                        }
                    } else {
                         Log.w(TAG, "Surface became invalid before setting fixed size on UI thread.");
                    }
                });
            } else {
                Log.e(TAG, "previewSize is null, cannot set SurfaceHolder size");
                return; // Не можем продолжить без размера превью
            }

            // Создаем запрос на превью
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface); // Добавляем нашу поверхность как цель

            // Создаем сессию
            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    // Этот колбэк выполняется в backgroundHandler
                    if (cameraDevice == null || !isSurfaceAvailable || !isCameraOpen) {
                        Log.w(TAG, "State changed during session onConfigured, closing session");
                        if (session != null) session.close();
                        return;
                    }
                    cameraCaptureSession = session; // Сохраняем сессию
                    try {
                        // Настраиваем параметры запроса (например, автофокус)
                        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        // Запускаем повторяющийся запрос для отображения превью
                        cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                        Log.d(TAG, "Camera preview session configured and started");
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Error setting up camera preview request", e);
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "Session state error during setRepeatingRequest (e.g., already closed)", e);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                     Log.e(TAG, "Failed to configure camera preview session");
                    // Показываем сообщение об ошибке пользователю В ОСНОВНОМ ПОТОКЕ
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Failed to configure camera preview", Toast.LENGTH_LONG).show());
                }
            }, backgroundHandler); // Колбэки выполняются в фоновом потоке
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error accessing camera for createCameraPreviewSession", e);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Illegal state during createCaptureSession (camera might be closed or session exists)", e);
        } catch (IllegalArgumentException iae) {
            Log.e(TAG, "Illegal argument during createCaptureSession (surface invalid?)", iae);
        }
    }


    // Закрытие сессии превью
    private void closeCameraPreviewSession() {
        if (cameraCaptureSession != null) {
            try {
                cameraCaptureSession.close();
                Log.d(TAG, "Camera preview session closed.");
            } catch (IllegalStateException e) {
                Log.e(TAG, "IllegalStateException closing preview session (already closed?)", e);
            } catch (Exception e) { // Ловим и другие возможные ошибки
                 Log.e(TAG, "Exception closing preview session", e);
            } finally {
                 cameraCaptureSession = null;
            }
        }
    }

    // Закрытие камеры
    private void closeCamera() {
        try {
            // Пытаемся получить блокировку с таймаутом
            if (!cameraOpenCloseLock.tryAcquire(500, TimeUnit.MILLISECONDS)) {
                 Log.w(TAG, "Timeout waiting for camera lock to close camera.");
                 // Возможно, не стоит останавливать поток, если лок не получен? Зависит от логики.
                 // stopBackgroundThread(); // Возможно, стоит остановить поток здесь
                 return;
            }
            try {
                Log.d(TAG, "Closing camera...");
                closeCameraPreviewSession(); // Сначала закрываем сессию
                if (cameraDevice != null) {
                    cameraDevice.close(); // Затем закрываем само устройство
                    cameraDevice = null;
                }
                isCameraOpen = false; // Сбрасываем флаг
                Log.d(TAG, "Camera closed.");
            } finally {
                cameraOpenCloseLock.release(); // Освобождаем семафор в любом случае
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while trying to lock camera for closing.", e);
            Thread.currentThread().interrupt();
        } finally {
             // Останавливаем фоновый поток здесь, после попытки закрытия
            stopBackgroundThread();
        }
    }


    // Переключение камеры
    private void switchCamera() {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        if (manager == null || cameraIds == null || cameraIds.length < 2) {
            Log.w(TAG, "Cannot switch camera: Not enough cameras available.");
            runOnUiThread(()->Toast.makeText(this, "Only one camera available", Toast.LENGTH_SHORT).show());
            return;
        }

        Log.d(TAG, "Switching camera...");
        closeCamera(); // Закрываем текущую камеру

        // Переключаемся на следующую камеру циклически
        currentCameraIndex = (currentCameraIndex + 1) % cameraIds.length;
        cameraId = cameraIds[currentCameraIndex];
        Log.d(TAG, "Switched to camera ID: " + cameraId);

        // Пытаемся открыть новую камеру
        if (isSurfaceAvailable) {
            openCamera();
        } else {
            isCameraPendingOpen = true; // Откроем, когда поверхность будет готова
            Log.d(TAG, "Surface not available after switch, setting pending open flag");
        }
    }


    // Запуск выбора изображения из галереи
    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*"); // Уточняем, что нужны только изображения
        try {
            startActivityForResult(intent, PICK_IMAGE_REQUEST);
        } catch (android.content.ActivityNotFoundException ex) {
             Log.e(TAG, "No activity found to handle pick image intent", ex);
             Toast.makeText(this, "Cannot open image picker", Toast.LENGTH_SHORT).show();
        }
    }

    // Обработка результата выбора изображения
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri imageUri = data.getData();
            Log.d(TAG, "Image picked: " + imageUri.toString());
            try {
                // Освобождаем старые битмапы перед загрузкой нового
                recycleBitmaps();

                // Загружаем новый битмап
                originalBitmap = MediaStore.Images.Media.getBitmap(this.getContentResolver(), imageUri);
                if (originalBitmap == null) {
                     Log.e(TAG, "Failed to decode bitmap from URI");
                     Toast.makeText(this, "Failed to load image", Toast.LENGTH_LONG).show();
                     return;
                }
                Log.d(TAG, "Original bitmap loaded: " + originalBitmap.getWidth() + "x" + originalBitmap.getHeight());

                // Сбрасываем трансформации и подгоняем изображение под ImageView
                resetTransformationsAndFit();

                // Сбрасываем видимость слоев
                layerVisibility = new boolean[PENCIL_HARDNESS.length];
                Arrays.fill(layerVisibility, true);

                // Если режим карандаша активен, сразу обрабатываем
                if (isPencilMode) {
                    processPencilEffect();
                }
                // Обновляем отображение
                updateImageDisplay();

            } catch (IOException e) {
                Log.e(TAG, "Error loading image from URI", e);
                Toast.makeText(this, "Error loading image", Toast.LENGTH_LONG).show();
            } catch (OutOfMemoryError oom) {
                 Log.e(TAG, "OutOfMemoryError loading image", oom);
                 Toast.makeText(this, "Image is too large, not enough memory", Toast.LENGTH_LONG).show();
                 recycleBitmaps(); // Пытаемся освободить память
            } catch (SecurityException se) {
                 Log.e(TAG, "SecurityException loading image (permission issue?)", se);
                 Toast.makeText(this, "Permission denied to load image", Toast.LENGTH_LONG).show();
            }
        }
    }

     // Освобождение всех битмапов
    private void recycleBitmaps() {
        // Используем локальные копии, чтобы избежать гонки потоков при проверке и обнулении
        Bitmap ob = originalBitmap;
        Bitmap pb = pencilBitmap;
        Bitmap[] lb = layerBitmaps;

        originalBitmap = null;
        pencilBitmap = null;
        layerBitmaps = null;

        if (ob != null && !ob.isRecycled()) {
            ob.recycle();
            Log.d(TAG, "Recycled originalBitmap");
        }
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
                 // lb[i] = null; // Массив все равно будет обнулен выше
            }
             // layerBitmaps = null; // Уже обнулен выше
        }
         // Запускаем сборщик мусора (не гарантирует немедленное освобождение, но может помочь)
        // System.gc();
    }

    // Сброс трансформаций и подгонка изображения под размер ImageView
    private void resetTransformationsAndFit() {
        matrix.reset(); // Сбрасываем матрицу

        if (originalBitmap == null || originalBitmap.isRecycled() || imageView.getWidth() == 0 || imageView.getHeight() == 0) {
             Log.w(TAG, "Cannot fit image: bitmap unavailable or ImageView not measured yet.");
             scaleFactor = 1.0f;
             rotationAngle = 0.0f;
             imageView.post(() -> {
                 imageView.setImageMatrix(matrix);
                 imageView.invalidate();
             });
            return;
        }

        // Размеры View и Bitmap
        final float viewWidth = imageView.getWidth();
        final float viewHeight = imageView.getHeight();
        final float bmpWidth = originalBitmap.getWidth();
        final float bmpHeight = originalBitmap.getHeight();

        // Расчет масштаба для вписывания изображения (fit center)
        float scaleX = viewWidth / bmpWidth;
        float scaleY = viewHeight / bmpHeight;
        float initialScale = Math.min(scaleX, scaleY);

        // Расчет смещения для центрирования
        float scaledBmpWidth = bmpWidth * initialScale;
        float scaledBmpHeight = bmpHeight * initialScale;
        float initialTranslateX = (viewWidth - scaledBmpWidth) / 2f;
        float initialTranslateY = (viewHeight - scaledBmpHeight) / 2f;

        // Применяем масштаб и смещение к матрице
        matrix.postScale(initialScale, initialScale);
        matrix.postTranslate(initialTranslateX, initialTranslateY);

        // Применяем матрицу к ImageView
        imageView.post(() -> {
            imageView.setImageMatrix(matrix);
            imageView.invalidate();
            Log.d(TAG, "Image fit and centered. Initial scale: " + initialScale);
        });

        // Сохраняем начальный масштаб
        scaleFactor = initialScale;
        rotationAngle = 0.0f;
    }

    // Применение текущей матрицы трансформаций к ImageView
    private void applyTransformations() {
         if (imageView != null) { // Проверяем, не null ли imageView
            imageView.setImageMatrix(matrix);
            imageView.invalidate();
         }
    }


    // Установка прозрачности ImageView
    private void setImageAlpha(int progress) {
         if (imageView != null) {
            float alpha = Math.max(0.0f, Math.min(1.0f, progress / 100.0f)); // Ограничиваем 0.0-1.0
            imageView.setAlpha(alpha);
            imageView.invalidate();
         }
    }

    // Обработка изображения для создания эффекта карандашного рисунка
    private void processPencilEffect() {
        Log.d(TAG, "Processing pencil effect...");
        if (originalBitmap == null || originalBitmap.isRecycled()) {
            Log.w(TAG, "Original bitmap is null or recycled, cannot process pencil effect");
            return;
        }

        // Освобождаем память от предыдущих обработанных битмапов
        recycleBitmaps(); // Используем отдельный метод для очистки

        try {
             int width = originalBitmap.getWidth();
             int height = originalBitmap.getHeight();

            // 1. Создаем обесцвеченную версию
            pencilBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvasGray = new Canvas(pencilBitmap);
            Paint paintGray = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG); // Добавляем флаги для качества
            ColorMatrix cmGray = new ColorMatrix();
            cmGray.setSaturation(0);
            paintGray.setColorFilter(new ColorMatrixColorFilter(cmGray));
            canvasGray.drawBitmap(originalBitmap, 0, 0, paintGray);

            // 2. Создаем массив для слоев
            layerBitmaps = new Bitmap[PENCIL_HARDNESS.length];

            // 3. Получаем пиксели обесцвеченного изображения
            int[] pixels = new int[width * height];
            pencilBitmap.getPixels(pixels, 0, width, 0, 0, width, height);
            // Обесцвеченный битмап больше не нужен, если не планируем его показывать отдельно
            // pencilBitmap.recycle();
            // pencilBitmap = null;

             // 4. Создаем пустые битмапы для слоев и распределяем пиксели
            int numLayers = layerBitmaps.length;
            int step = 256 / numLayers;

            // Создаем массивы пикселей для каждого слоя
            int[][] layerPixels = new int[numLayers][width * height];
             for (int i = 0; i < numLayers; i++) {
                 // Инициализируем прозрачным цветом (ARGB = 0)
                 Arrays.fill(layerPixels[i], Color.TRANSPARENT);
             }

             // Распределяем пиксели
            for (int i = 0; i < pixels.length; i++) {
                 int gray = Color.red(pixels[i]);
                 int layerIndex = gray / step;
                 if (layerIndex >= numLayers) layerIndex = numLayers - 1;
                  if (layerIndex >= 0) {
                      layerPixels[layerIndex][i] = pixels[i]; // Копируем пиксель в нужный слой
                  }
            }
             pixels = null; // Освобождаем память от исходного массива пикселей

            // Создаем битмапы из массивов пикселей
             for (int i = 0; i < numLayers; i++) {
                 layerBitmaps[i] = Bitmap.createBitmap(layerPixels[i], width, height, Bitmap.Config.ARGB_8888);
                 layerPixels[i] = null; // Освобождаем память промежуточного массива
             }
             layerPixels = null; // Освобождаем сам массив массивов

            Log.d(TAG, "Pencil effect processed successfully into " + numLayers + " layers.");

        } catch (OutOfMemoryError e) {
            Log.e(TAG, "OutOfMemoryError in processPencilEffect", e);
            runOnUiThread(()-> Toast.makeText(this, "Not enough memory to process pencil effect", Toast.LENGTH_LONG).show());
            recycleBitmaps();
        } catch (Exception e) { // Ловим другие возможные ошибки
             Log.e(TAG, "Unexpected error in processPencilEffect", e);
             runOnUiThread(()-> Toast.makeText(this, "Error processing pencil effect", Toast.LENGTH_LONG).show());
             recycleBitmaps();
        }
    }

    // Определение индекса слоя по значению серого (0-255)
    // Этот метод больше не нужен, если логика встроена в processPencilEffect
    /*
    private int getLayerIndex(int grayValue) {
         int numLayers = PENCIL_HARDNESS.length;
         int step = 256 / numLayers;
         int index = grayValue / step;
         return Math.min(index, numLayers - 1); // Ограничиваем сверху
    }
    */

    // Обновление отображаемого изображения в ImageView
    private void updateImageDisplay() {
        Log.d(TAG, "Updating image display: isPencilMode=" + isPencilMode + ", isImageVisible=" + isImageVisible);

        if (!isImageVisible || originalBitmap == null || originalBitmap.isRecycled()) {
            Log.d(TAG, "Hiding ImageView or originalBitmap is unavailable.");
            runOnUiThread(() -> { // Операции с UI в основном потоке
                 imageView.setImageBitmap(null);
                 imageView.setVisibility(View.INVISIBLE);
                 imageView.invalidate();
            });
            return;
        }

        Bitmap bitmapToDisplay = null;

        if (isPencilMode) {
            Log.d(TAG, "Pencil mode is ON");
            if (layerBitmaps == null) {
                Log.d(TAG, "layerBitmaps is null, processing pencil effect...");
                processPencilEffect(); // Эта функция теперь может вызвать OutOfMemoryError
            }

            if (layerBitmaps == null) {
                 Log.e(TAG, "layerBitmaps still null after processing attempt.");
                 runOnUiThread(() -> {
                     imageView.setImageBitmap(null);
                     imageView.setVisibility(View.INVISIBLE);
                     imageView.invalidate();
                 });
                 return;
            }

            try {
                // Создаем итоговый битмап
                bitmapToDisplay = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmapToDisplay);
                canvas.drawColor(Color.TRANSPARENT); // Начинаем с прозрачного фона
                Paint layerPaint = new Paint(Paint.FILTER_BITMAP_FLAG); // Добавляем флаг для качества

                // Рисуем видимые слои
                boolean drawnSomething = false;
                for (int i = 0; i < layerBitmaps.length; i++) {
                    if (layerVisibility[i] && layerBitmaps[i] != null && !layerBitmaps[i].isRecycled()) {
                        canvas.drawBitmap(layerBitmaps[i], 0, 0, layerPaint);
                        drawnSomething = true;
                    }
                }
                 Log.d(TAG, "Drew visible pencil layers. Drawn something: " + drawnSomething);

                 if (!drawnSomething && bitmapToDisplay != null) { // Если ничего не нарисовали, делаем битмап прозрачным
                     bitmapToDisplay.recycle(); // Освобождаем предыдущий
                    bitmapToDisplay = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
                    bitmapToDisplay.eraseColor(Color.TRANSPARENT);
                    Log.d(TAG, "No layers visible, created transparent bitmap.");
                 }

            } catch (OutOfMemoryError e) {
                 Log.e(TAG, "OutOfMemoryError creating result bitmap for pencil mode", e);
                 runOnUiThread(()-> Toast.makeText(this, "Not enough memory to display layers", Toast.LENGTH_SHORT).show());
                 bitmapToDisplay = originalBitmap; // Показываем оригинал
                 // Важно: нужно освободить память от слоев, если они были созданы до ошибки
                 recycleBitmaps(); // Перенесли сюда, чтобы очистить и слои
                 isPencilMode = false; // Выключаем режим карандаша, чтобы не пытаться снова
                 runOnUiThread(()-> pencilModeSwitch.setChecked(false));

            } catch (Exception e) {
                 Log.e(TAG, "Error creating result bitmap for pencil mode", e);
                 bitmapToDisplay = originalBitmap;
            }

        } else {
            // Если не режим карандаша, просто показываем оригинал
            Log.d(TAG, "Pencil mode is OFF, displaying original bitmap");
            bitmapToDisplay = originalBitmap;
        }

        // Устанавливаем итоговый битмап и применяем трансформации в основном потоке
         final Bitmap finalBitmapToDisplay = bitmapToDisplay; // Копия для лямбды
        runOnUiThread(() -> {
            if (imageView != null) {
                 imageView.setImageBitmap(finalBitmapToDisplay);
                 setImageAlpha(transparencySeekBar.getProgress()); // Альфа тоже в UI потоке
                 imageView.setVisibility(View.VISIBLE);
                 imageView.setImageMatrix(matrix); // Применяем матрицу
                 imageView.invalidate();
                 Log.d(TAG, "Image display updated on UI thread.");
            }
        });
    }


    // Показ диалога выбора слоев
    private void showLayerSelectionDialog() {
        final Dialog dialog = new Dialog(this); // Используем final
        // Используем наш кастомный макет для диалога
        dialog.setContentView(R.layout.dialog_layer_selection);
        dialog.setTitle(R.string.layer_selection_title); // Устанавливаем заголовок

        // Находим RecyclerView внутри макета диалога по ПРАВИЛЬНОМУ ID
        RecyclerView recyclerView = dialog.findViewById(R.id.recyclerView); // *** ID ИСПРАВЛЕН ***

        if (recyclerView == null) {
             Log.e(TAG, "RecyclerView (R.id.recyclerView) not found in dialog layout!");
             Toast.makeText(this, "Error creating layer dialog", Toast.LENGTH_SHORT).show();
             return;
        }

        recyclerView.setLayoutManager(new LinearLayoutManager(this)); // Задаем LayoutManager
        // Создаем и устанавливаем адаптер, передавая ему данные и слушатель (this)
        LayerAdapter adapter = new LayerAdapter(PENCIL_HARDNESS, layerVisibility, this);
        recyclerView.setAdapter(adapter);

        // Устанавливаем размер диалога (опционально, можно настроить в XML)
        /*
        Window window = dialog.getWindow();
        if (window != null) {
           window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        */

        dialog.show(); // Показываем диалог
    }

    // Метод интерфейса LayerAdapter.OnLayerVisibilityChangedListener
    @Override
    public void onLayerVisibilityChanged(int position, boolean isVisible) {
         if (position >= 0 && position < layerVisibility.length) {
            layerVisibility[position] = isVisible;
            Log.d(TAG, "Layer " + position + " (" + PENCIL_HARDNESS[position] + ") visibility changed to: " + isVisible);
            updateImageDisplay(); // Обновляем отображение в ImageView
         } else {
             Log.w(TAG, "Invalid position received from LayerAdapter: " + position);
         }
    }


    // Сохранение параметров
    private void saveParameters() {
        // Используем внутреннее хранилище, не требующее разрешений
        File file = new File(getFilesDir(), "parameters.dat");
        try (FileOutputStream fos = new FileOutputStream(file)) { // try-with-resources для автозакрытия
            // Сохраняем значения матрицы
            float[] matrixValues = new float[9];
            matrix.getValues(matrixValues);
            StringBuilder matrixStr = new StringBuilder("matrix=");
            for (int i=0; i < matrixValues.length; i++) {
                matrixStr.append(matrixValues[i]);
                if (i < matrixValues.length - 1) matrixStr.append(",");
            }
            fos.write((matrixStr.toString() + "\n").getBytes());

            // Сохраняем другие параметры
            fos.write(("pencilMode=" + isPencilMode + "\n").getBytes());
            fos.write(("imageVisible=" + isImageVisible + "\n").getBytes());
            fos.write(("transparency=" + transparencySeekBar.getProgress() + "\n").getBytes());

            // Сохраняем видимость слоев
            StringBuilder layersStr = new StringBuilder("layers=");
            for (int i=0; i < layerVisibility.length; i++) {
                layersStr.append(layerVisibility[i]);
                 if (i < layerVisibility.length - 1) layersStr.append(",");
            }
            fos.write(layersStr.toString().getBytes()); // последняя строка без \n

            Toast.makeText(this, "Parameters saved", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Parameters saved to " + file.getAbsolutePath());

        } catch (IOException e) {
            Log.e(TAG, "Error saving parameters", e);
            Toast.makeText(this, "Error saving parameters", Toast.LENGTH_LONG).show();
        }
    }

    // Загрузка параметров
    private void loadParameters() {
        File file = new File(getFilesDir(), "parameters.dat");
        if (!file.exists()) {
            Toast.makeText(this, "No saved parameters found", Toast.LENGTH_SHORT).show();
            return;
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int bytesRead = fis.read(buffer);
            if (bytesRead <= 0) {
                 Toast.makeText(this, "Empty parameters file", Toast.LENGTH_LONG).show();
                 return;
             }
            String content = new String(buffer, 0, bytesRead);
            String[] lines = content.split("\n");

            float[] loadedMatrixValues = new float[9];
            Arrays.fill(loadedMatrixValues, 0f); // Инициализация
            loadedMatrixValues[0] = loadedMatrixValues[4] = loadedMatrixValues[8] = 1f; // Единичная матрица по умолчанию

            for (String line : lines) {
                 if (line.startsWith("matrix=")) {
                      String[] valuesStr = line.substring("matrix=".length()).split(",");
                      if (valuesStr.length >= 9) {
                          for (int i = 0; i < 9; i++) {
                              loadedMatrixValues[i] = Float.parseFloat(valuesStr[i]);
                          }
                      }
                 } else if (line.startsWith("pencilMode=")) {
                     isPencilMode = Boolean.parseBoolean(line.substring("pencilMode=".length()));
                 } else if (line.startsWith("imageVisible=")) {
                     isImageVisible = Boolean.parseBoolean(line.substring("imageVisible=".length()));
                 } else if (line.startsWith("transparency=")) {
                     transparencySeekBar.setProgress(Integer.parseInt(line.substring("transparency=".length())));
                 } else if (line.startsWith("layers=")) {
                      String[] visibilityValues = line.substring("layers=".length()).split(",");
                      for (int j = 0; j < layerVisibility.length && j < visibilityValues.length; j++) {
                          if (!visibilityValues[j].isEmpty()) {
                              layerVisibility[j] = Boolean.parseBoolean(visibilityValues[j]);
                          }
                      }
                 }
            }

            // Применяем загруженные значения
             runOnUiThread(() -> { // Обновление UI в основном потоке
                matrix.setValues(loadedMatrixValues);
                pencilModeSwitch.setChecked(isPencilMode);
                hideImageCheckbox.setChecked(!isImageVisible);
                // Transparency SeekBar уже обновлен
                applyTransformations();
                updateImageDisplay(); // Обновляем отображение с новыми параметрами
                Toast.makeText(this, "Parameters loaded", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Parameters loaded successfully.");
             });


        } catch (IOException | NumberFormatException | ArrayIndexOutOfBoundsException e) {
            Log.e(TAG, "Error loading parameters", e);
            Toast.makeText(this, "Error loading parameters", Toast.LENGTH_LONG).show();
        }
    }

    // --- Жизненный цикл Activity ---

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume called");
        startBackgroundThread(); // Запускаем фоновый поток
        // Если поверхность готова и камера не открыта, пытаемся открыть
        if (isSurfaceAvailable && !isCameraOpen) {
            Log.d(TAG, "Opening camera from onResume");
            openCamera();
        } else if (!isSurfaceAvailable) {
             Log.d(TAG, "Surface not available in onResume, setting pending open flag");
            isCameraPendingOpen = true; // Отметим, что нужно открыть позже
        }
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause called");
        closeCamera(); // Закрываем камеру при уходе с экрана
        // stopBackgroundThread(); // stopBackgroundThread теперь вызывается внутри closeCamera
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy called");
        // Не нужно вызывать closeCamera() здесь, т.к. он вызовется в onPause()
        // stopBackgroundThread(); // Тоже вызовется в onPause/closeCamera
        recycleBitmaps(); // Освобождаем все битмапы при уничтожении Activity
        super.onDestroy();
    }

    // Обработка изменения конфигурации (например, поворот экрана)
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.d(TAG, "onConfigurationChanged");
        // Пересчитываем размер превью и подгоняем изображение
        // Делаем это в post, чтобы размеры View успели обновиться
        cameraSurfaceView.post(() -> {
             if (isSurfaceAvailable && cameraDevice != null && isCameraOpen) {
                 // Закрываем старую сессию перед пересчетом размера
                 closeCameraPreviewSession();
                 previewSize = chooseOptimalPreviewSize(getPreviewSizes(), cameraSurfaceView.getWidth(), cameraSurfaceView.getHeight());
                 // adjustSurfaceViewAspectRatioWithCropping(cameraSurfaceView.getWidth(), cameraSurfaceView.getHeight()); // Вероятно, не нужен
                 // Создаем новую сессию с новым размером
                 createCameraPreviewSession();
             }
             // Сбрасываем трансформации ImageView, чтобы он центрировался в новом layout
             resetTransformationsAndFit();
             // Обновляем отображение (применит матрицу из resetTransformationsAndFit)
             updateImageDisplay();
        });
    }

     // Вспомогательный метод для получения текущего Display
     private Display getDisplay() {
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
             return getDisplayManager().getDisplay(Display.DEFAULT_DISPLAY);
         } else {
             // Устаревший метод для API < 30
             //noinspection deprecation
             return getWindowManager().getDefaultDisplay();
         }
     }
}
