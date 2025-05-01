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
import java.util.Arrays;
import java.util.concurrent.Semaphore;

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
                adjustSurfaceViewAspectRatioWithCropping(width, height);
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
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        } else {
            // Если разрешение уже есть, отмечаем, что камера ожидает открытия
             if (!isCameraOpen) isCameraPendingOpen = true;
        }
        // Запрашиваем разрешение на хранилище (для старых версий Android)
         if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE_PERMISSION);
            }
        } else {
             // На Android 10+ для доступа к галерее обычно достаточно READ_EXTERNAL_STORAGE или READ_MEDIA_IMAGES
             if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                 ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQUEST_STORAGE_PERMISSION);
             }
         }


        // --- Инициализация списка камер ---
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            if (manager != null) {
                cameraIds = manager.getCameraIdList();
                if (cameraIds.length > 0) {
                    cameraId = cameraIds[currentCameraIndex]; // Начинаем с первой камеры
                } else {
                     Log.e(TAG, "No cameras found on device.");
                     Toast.makeText(this, "No cameras found", Toast.LENGTH_LONG).show();
                }
            } else {
                 Log.e(TAG, "CameraManager is null.");
                 Toast.makeText(this, "Cannot access Camera Service", Toast.LENGTH_LONG).show();
            }

        } catch (CameraAccessException e) {
            Log.e(TAG, "Error accessing camera list", e);
            Toast.makeText(this, "Cannot access cameras", Toast.LENGTH_LONG).show();
        } catch (SecurityException se) {
             Log.e(TAG, "Camera permission not granted", se);
             // Разрешение уже должно было быть запрошено, но на всякий случай
        }


        // Инициализация массива видимости слоев
        layerVisibility = new boolean[PENCIL_HARDNESS.length]; // Используем длину массива названий
        Arrays.fill(layerVisibility, true); // По умолчанию все слои видимы
    }

    // Подгонка размера SurfaceView с обрезкой, чтобы сохранить соотношение сторон превью
    private void adjustSurfaceViewAspectRatioWithCropping(int viewWidth, int viewHeight) {
        if (previewSize == null || viewWidth == 0 || viewHeight == 0) {
            Log.w(TAG, "Cannot adjust aspect ratio: previewSize is null or view dimensions are zero.");
            return;
        }

        int previewWidth = previewSize.getWidth();
        int previewHeight = previewSize.getHeight();

        // Определяем ориентацию превью (может отличаться от ориентации экрана)
        // int displayRotation = getWindowManager().getDefaultDisplay().getRotation(); // Устарело
        int displayRotation = getDisplay().getRotation(); // Более современный способ
        int sensorOrientation = 0;
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
             if (manager != null && cameraId != null) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
             }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error getting sensor orientation", e);
        }

        boolean isLandscape = (displayRotation == Surface.ROTATION_90 || displayRotation == Surface.ROTATION_270);
        // Если сенсор повернут на 90 или 270, меняем ширину и высоту местами для расчета
        if (sensorOrientation == 90 || sensorOrientation == 270) {
             previewWidth = previewSize.getHeight();
             previewHeight = previewSize.getWidth();
        }

        float previewRatio = (float) previewWidth / previewHeight;
        float viewRatio = (float) viewWidth / viewHeight;

        Matrix transformMatrix = new Matrix();
        float scaleX = 1.0f, scaleY = 1.0f;
        float dx = 0, dy = 0;

        if (previewRatio > viewRatio) {
            // Превью шире, чем вид -> масштабируем по высоте, обрезаем по ширине
            scaleY = (float) viewHeight / previewHeight;
            scaleX = scaleY;
            dx = (viewWidth - previewWidth * scaleX) / 2f;
        } else {
            // Превью выше, чем вид -> масштабируем по ширине, обрезаем по высоте
            scaleX = (float) viewWidth / previewWidth;
            scaleY = scaleX;
            dy = (viewHeight - previewHeight * scaleY) / 2f;
        }


        // Применяем трансформацию к SurfaceView (хотя это обычно не работает так просто)
        // Более надежный способ - настроить размер контейнера SurfaceView
         ViewGroup.LayoutParams params = cameraSurfaceView.getLayoutParams();
         if (isLandscape) {
              params.width = viewWidth; //(int) (viewHeight * previewRatio);
              params.height = viewHeight;
         } else {
             params.width = viewWidth;
             params.height = viewHeight; //(int) (viewWidth / previewRatio);
         }

         Log.d(TAG, "Adjusting SurfaceView LayoutParams to: " + params.width + "x" + params.height + " (View: " + viewWidth + "x" + viewHeight + ")");
         // cameraSurfaceView.setLayoutParams(params); // Применение может вызвать проблемы, оставим стандартный match_parent

        // Мы не можем напрямую обрезать SurfaceView,
        // обрезка происходит неявно из-за разницы aspect ratio
        // SurfaceView и превью камеры.

        cameraSurfaceView.requestLayout();
        Log.d(TAG, "Adjusted SurfaceView requested layout (preview ratio: " + previewRatio + ")");
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
        if (backgroundThread == null) {
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
            Log.w(TAG, "Camera permission not granted before opening camera.");
            isCameraPendingOpen = false; // Сбрасываем флаг ожидания
            // Можно снова запросить разрешение или показать сообщение
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


        startBackgroundThread(); // Запускаем фоновый поток, если еще не запущен
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        if (manager == null) {
             Log.e(TAG, "CameraManager is null in openCamera");
             Toast.makeText(this, "Cannot access Camera Service", Toast.LENGTH_LONG).show();
             isCameraPendingOpen = false;
             return;
        }

        try {
            if (cameraId == null || cameraIds == null || cameraIds.length == 0) {
                 Log.e(TAG, "No valid camera ID found to open.");
                 Toast.makeText(this, "No camera available", Toast.LENGTH_LONG).show();
                 isCameraPendingOpen = false;
                 return;
            }

            // Получаем характеристики выбранной камеры
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Size[] previewSizes = null;
             if (characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) != null) {
                previewSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        .getOutputSizes(SurfaceHolder.class);
             } else {
                 Log.e(TAG, "StreamConfigurationMap is null for camera " + cameraId);
                 Toast.makeText(this, "Cannot get camera configurations", Toast.LENGTH_LONG).show();
                 isCameraPendingOpen = false;
                 return;
             }


            previewSize = chooseOptimalPreviewSize(previewSizes, cameraSurfaceView.getWidth(), cameraSurfaceView.getHeight());

            // Пытаемся получить блокировку семафора
            if (!cameraOpenCloseLock.tryAcquire()) {
                 Log.w(TAG, "Could not acquire camera lock, maybe another operation is in progress.");
                 // return; // Можно выйти, если не хотим ждать
                  try {
                      cameraOpenCloseLock.acquire(); // Или дождаться освобождения
                  } catch (InterruptedException e) {
                       Log.e(TAG, "Interrupted while waiting for camera lock", e);
                       Thread.currentThread().interrupt();
                       return;
                  }
            }

            Log.d(TAG, "Opening camera: " + cameraId);
            isCameraOpen = true; // Устанавливаем флаг до вызова openCamera

            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice camera) {
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
                    // Показываем сообщение об ошибке пользователю
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Camera error: " + error, Toast.LENGTH_LONG).show());
                }
            }, backgroundHandler); // Выполняем колбэки в фоновом потоке
        } catch (CameraAccessException e) {
            Log.e(TAG, "Cannot access camera to open", e);
            Toast.makeText(this, "Cannot access camera", Toast.LENGTH_LONG).show();
            isCameraOpen = false;
             if (cameraOpenCloseLock.availablePermits() == 0) cameraOpenCloseLock.release(); // Убедимся, что семафор освобожден при ошибке
        } catch (SecurityException se) {
             Log.e(TAG, "Camera permission denied during openCamera", se);
             Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show();
             isCameraOpen = false;
             if (cameraOpenCloseLock.availablePermits() == 0) cameraOpenCloseLock.release();
        }
         isCameraPendingOpen = false; // В любом случае сбрасываем флаг ожидания после попытки открытия
    }

    // Получение доступных размеров превью
    private Size[] getPreviewSizes() {
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
             if (manager == null || cameraId == null) return new Size[]{new Size(1280, 720)}; // Возвращаем дефолтное значение
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
             if (characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) != null) {
                return characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        .getOutputSizes(SurfaceHolder.class);
             } else {
                 return new Size[]{new Size(1280, 720)};
             }
        } catch (CameraAccessException | IllegalArgumentException e) { // IllegalArgumentException если cameraId невалидный
            Log.e(TAG, "Error getting preview sizes for camera " + cameraId, e);
            return new Size[]{new Size(1280, 720)}; // Возвращаем дефолтное значение при ошибке
        }
    }

    // Выбор оптимального размера превью
    private Size chooseOptimalPreviewSize(Size[] choices, int viewWidth, int viewHeight) {
        if (choices == null || choices.length == 0) {
            Log.e(TAG, "No preview sizes available, using default");
            return new Size(1280, 720); // Дефолтное значение
        }

        // Используем размеры SurfaceView как целевые
        int targetWidth = viewWidth;
        int targetHeight = viewHeight;
        if (targetWidth == 0 || targetHeight == 0) { // Если размеры еще не определены
             targetWidth = 1280;
             targetHeight = 720;
             Log.w(TAG, "View dimensions are zero, using default target size 1280x720");
        }


        Size optimalSize = null;
        int minAreaDiff = Integer.MAX_VALUE;

        // Пытаемся найти размер с точно таким же соотношением сторон, ближайший по площади
        double targetRatio = (double) targetWidth / targetHeight;
        for (Size size : choices) {
             if (size.getWidth() * size.getHeight() > 2_000_000) continue; // Пропускаем слишком большие размеры

            double ratio = (double) size.getWidth() / size.getHeight();
             if (Math.abs(ratio - targetRatio) < 0.05) { // Допускаем небольшое отклонение
                int areaDiff = Math.abs(size.getWidth() * size.getHeight() - targetWidth * targetHeight);
                 if (areaDiff < minAreaDiff) {
                    optimalSize = size;
                    minAreaDiff = areaDiff;
                 }
             }
        }

        // Если не нашли с близким соотношением сторон, ищем просто ближайший по площади
        if (optimalSize == null) {
            minAreaDiff = Integer.MAX_VALUE;
             for (Size size : choices) {
                 if (size.getWidth() * size.getHeight() > 2_000_000) continue;
                int areaDiff = Math.abs(size.getWidth() * size.getHeight() - targetWidth * targetHeight);
                 if (areaDiff < minAreaDiff) {
                    optimalSize = size;
                    minAreaDiff = areaDiff;
                 }
             }
        }


        if (optimalSize == null) optimalSize = choices[0]; // Берем первый попавшийся, если совсем ничего не подошло

        Log.d(TAG, "Chosen preview size: " + optimalSize.getWidth() + "x" + optimalSize.getHeight() + " for view size " + viewWidth + "x" + viewHeight);
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
            SurfaceHolder holder = cameraSurfaceView.getHolder();
            if (holder == null) {
                 Log.e(TAG, "SurfaceHolder is null");
                 return;
            }
            Surface surface = holder.getSurface();
            if (surface == null || !surface.isValid()) {
                Log.e(TAG, "Surface is null or invalid, aborting preview session creation");
                return;
            }

            // Устанавливаем размер буфера SurfaceView равным размеру превью (важно для соотношения сторон)
            if (previewSize != null) {
                 holder.setFixedSize(previewSize.getWidth(), previewSize.getHeight());
                 Log.d(TAG, "Set SurfaceHolder fixed size to: " + previewSize.getWidth() + "x" + previewSize.getHeight());
            } else {
                 Log.w(TAG, "previewSize is null when setting SurfaceHolder size");
                 return; // Не можем продолжить без размера превью
            }


            // Создаем запрос на превью
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface); // Добавляем нашу поверхность как цель

            // Создаем сессию
            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    // Проверяем, не закрыли ли камеру или поверхность, пока шла конфигурация
                    if (cameraDevice == null || !isSurfaceAvailable || !isCameraOpen) {
                        Log.w(TAG, "Camera device closed, surface unavailable, or camera not open during session onConfigured");
                        if (session != null) session.close(); // Закрываем сессию, если она еще существует
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
                        // Сессия могла быть закрыта в другом потоке
                        Log.e(TAG, "Session already closed during setRepeatingRequest", e);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "Failed to configure camera preview session");
                    Toast.makeText(MainActivity.this, "Failed to configure camera preview", Toast.LENGTH_LONG).show();
                }
            }, backgroundHandler); // Колбэки выполняются в фоновом потоке
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error creating camera preview session", e);
        } catch (IllegalStateException e){
             Log.e(TAG, "Illegal state during createCaptureSession (camera might be closed)", e);
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
            } finally {
                cameraCaptureSession = null;
            }
        }
    }

    // Закрытие камеры
    private void closeCamera() {
        try {
            // Пытаемся получить блокировку, чтобы избежать гонки потоков
            if (!cameraOpenCloseLock.tryAcquire(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                 Log.w(TAG, "Timeout waiting for camera lock to close camera.");
                 // return; // Можно решить не закрывать, если лок занят долго
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
             stopBackgroundThread(); // Останавливаем фоновый поток после закрытия камеры
        }
    }


    // Переключение камеры
    private void switchCamera() {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        if (manager == null || cameraIds == null || cameraIds.length < 2) {
            Log.w(TAG, "Cannot switch camera: Not enough cameras available.");
            Toast.makeText(this, "Only one camera available", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.d(TAG, "Switching camera...");
        closeCamera(); // Закрываем текущую камеру

        currentCameraIndex = (currentCameraIndex + 1) % cameraIds.length; // Переключаемся на следующую
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
        // intent.setType("image/*"); // Можно добавить, чтобы точно выбирать только изображения
        startActivityForResult(intent, PICK_IMAGE_REQUEST);
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
            }
        }
    }

     // Освобождение всех битмапов
    private void recycleBitmaps() {
        if (originalBitmap != null && !originalBitmap.isRecycled()) {
            originalBitmap.recycle();
            originalBitmap = null;
            Log.d(TAG, "Recycled originalBitmap");
        }
        if (pencilBitmap != null && !pencilBitmap.isRecycled()) {
            pencilBitmap.recycle();
            pencilBitmap = null;
             Log.d(TAG, "Recycled pencilBitmap");
        }
        if (layerBitmaps != null) {
            for (int i = 0; i < layerBitmaps.length; i++) {
                if (layerBitmaps[i] != null && !layerBitmaps[i].isRecycled()) {
                    layerBitmaps[i].recycle();
                     Log.d(TAG, "Recycled layerBitmap[" + i + "]");
                }
                 layerBitmaps[i] = null;
            }
            layerBitmaps = null; // Обнуляем сам массив
        }
    }

    // Сброс трансформаций и подгонка изображения под размер ImageView
    private void resetTransformationsAndFit() {
        matrix.reset(); // Сбрасываем матрицу

        if (originalBitmap == null || imageView.getWidth() == 0 || imageView.getHeight() == 0) {
            Log.w(TAG, "Cannot fit image: bitmap is null or ImageView not measured yet.");
             scaleFactor = 1.0f;
             rotationAngle = 0.0f;
             // Даже если нет битмапа, применяем пустую матрицу
             imageView.post(() -> { // Используем post для гарантии выполнения после измерения
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
        float initialScale = Math.min(scaleX, scaleY); // Берем минимальный масштаб, чтобы все влезло

        // Расчет смещения для центрирования
        float scaledBmpWidth = bmpWidth * initialScale;
        float scaledBmpHeight = bmpHeight * initialScale;
        float initialTranslateX = (viewWidth - scaledBmpWidth) / 2f;
        float initialTranslateY = (viewHeight - scaledBmpHeight) / 2f;

        // Применяем масштаб и смещение к матрице
        matrix.postScale(initialScale, initialScale);
        matrix.postTranslate(initialTranslateX, initialTranslateY);

        // Применяем матрицу к ImageView (лучше через post, чтобы выполнилось после layout pass)
        imageView.post(() -> {
            imageView.setImageMatrix(matrix);
            imageView.invalidate();
            Log.d(TAG, "Image fit and centered. Initial scale: " + initialScale);
        });


        // Сохраняем начальный масштаб
        scaleFactor = initialScale;
        rotationAngle = 0.0f; // Сбрасываем угол вращения
    }

    // Применение текущей матрицы трансформаций к ImageView
    private void applyTransformations() {
        imageView.setImageMatrix(matrix); // Просто применяем матрицу
        imageView.invalidate(); // Перерисовываем ImageView
        // Логирование можно убрать или сделать реже, чтобы не засорять лог
        // Log.d(TAG, "Transformations applied: scale=" + scaleFactor);
    }


    // Установка прозрачности ImageView
    private void setImageAlpha(int progress) {
        float alpha = progress / 100.0f; // Преобразуем прогресс (0-100) в альфа (0.0-1.0)
        imageView.setAlpha(alpha);
        imageView.invalidate(); // Перерисовываем
        // Log.d(TAG, "Image alpha set to: " + alpha);
    }

    // Обработка изображения для создания эффекта карандашного рисунка
    private void processPencilEffect() {
        Log.d(TAG, "Processing pencil effect...");
        if (originalBitmap == null || originalBitmap.isRecycled()) {
            Log.w(TAG, "Original bitmap is null or recycled, cannot process pencil effect");
            return;
        }

        // Освобождаем память от предыдущих обработанных битмапов
        if (pencilBitmap != null && !pencilBitmap.isRecycled()) {
            pencilBitmap.recycle();
        }
        if (layerBitmaps != null) {
            for (int i = 0; i < layerBitmaps.length; i++) {
                if (layerBitmaps[i] != null && !layerBitmaps[i].isRecycled()) {
                    layerBitmaps[i].recycle();
                }
            }
        }

        try {
            // 1. Создаем обесцвеченную версию (можно использовать для основы или как один из слоев)
            pencilBitmap = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvasGray = new Canvas(pencilBitmap);
            Paint paintGray = new Paint();
            ColorMatrix cmGray = new ColorMatrix();
            cmGray.setSaturation(0); // Убираем насыщенность
            paintGray.setColorFilter(new ColorMatrixColorFilter(cmGray));
            canvasGray.drawBitmap(originalBitmap, 0, 0, paintGray);

            // 2. Создаем массив для слоев
            layerBitmaps = new Bitmap[PENCIL_HARDNESS.length]; // Используем размер массива названий

            // 3. Получаем пиксели обесцвеченного изображения
            int width = pencilBitmap.getWidth();
            int height = pencilBitmap.getHeight();
            int[] pixels = new int[width * height];
            pencilBitmap.getPixels(pixels, 0, width, 0, 0, width, height);

             // 4. Предварительно создаем пустые битмапы для слоев
             // (Делаем это один раз, чтобы не создавать в цикле)
             for (int i = 0; i < layerBitmaps.length; i++) {
                 layerBitmaps[i] = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                 layerBitmaps[i].eraseColor(Color.TRANSPARENT); // Делаем их прозрачными
             }

            // 5. Распределяем пиксели по слоям в зависимости от яркости (серого)
            int numLayers = layerBitmaps.length;
            int step = 256 / numLayers; // Шаг яркости для каждого слоя

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int index = y * width + x;
                    int pixel = pixels[index];
                    int gray = Color.red(pixel); // Для серого R=G=B
                    int layerIndex = gray / step;
                    // Ограничиваем индекс максимальным значением
                    if (layerIndex >= numLayers) {
                        layerIndex = numLayers - 1;
                    }
                    // Устанавливаем пиксель на соответствующий слой
                     if (layerIndex >= 0 && layerBitmaps[layerIndex] != null) { // Проверка на всякий случай
                         layerBitmaps[layerIndex].setPixel(x, y, pixel);
                     }
                }
            }

            Log.d(TAG, "Pencil effect processed successfully into " + numLayers + " layers.");

        } catch (OutOfMemoryError e) {
            Log.e(TAG, "OutOfMemoryError in processPencilEffect", e);
            Toast.makeText(this, "Not enough memory to process pencil effect", Toast.LENGTH_LONG).show();
            // Освобождаем все, что могли создать
            recycleBitmaps();
        } catch (Exception e) {
             Log.e(TAG, "Unexpected error in processPencilEffect", e);
             Toast.makeText(this, "Error processing pencil effect", Toast.LENGTH_LONG).show();
             recycleBitmaps();
        }
    }


    // Определение индекса слоя по значению серого (0-255)
    private int getLayerIndex(int grayValue) {
         int numLayers = PENCIL_HARDNESS.length;
         int step = 256 / numLayers;
         int index = grayValue / step;
         return Math.min(index, numLayers - 1); // Ограничиваем сверху
    }

    // Обновление отображаемого изображения в ImageView
    private void updateImageDisplay() {
        Log.d(TAG, "Updating image display: isPencilMode=" + isPencilMode + ", isImageVisible=" + isImageVisible);

        if (!isImageVisible || originalBitmap == null || originalBitmap.isRecycled()) {
            Log.d(TAG, "Hiding ImageView or originalBitmap is unavailable.");
            imageView.setImageBitmap(null); // Убираем битмап
            imageView.setVisibility(View.INVISIBLE); // Скрываем View
            imageView.invalidate();
            return;
        }

        Bitmap bitmapToDisplay = null;

        if (isPencilMode) {
            Log.d(TAG, "Pencil mode is ON");
            // Если карандашные слои не созданы, создаем их
            if (layerBitmaps == null) {
                Log.d(TAG, "layerBitmaps is null, processing pencil effect...");
                processPencilEffect();
            }

            // Если после обработки слои все еще null (например, из-за ошибки памяти), выходим
            if (layerBitmaps == null) {
                 Log.e(TAG, "layerBitmaps still null after processing, cannot display pencil mode.");
                 imageView.setImageBitmap(null);
                 imageView.setVisibility(View.INVISIBLE);
                 imageView.invalidate();
                 return;
            }


            try {
                // Создаем итоговый битмап для отображения слоев
                bitmapToDisplay = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmapToDisplay);
                canvas.drawColor(Color.TRANSPARENT); // Начинаем с прозрачного фона

                // Рисуем видимые слои на итоговый битмап
                boolean drawnSomething = false;
                for (int i = 0; i < layerBitmaps.length; i++) {
                    if (layerVisibility[i] && layerBitmaps[i] != null && !layerBitmaps[i].isRecycled()) {
                        canvas.drawBitmap(layerBitmaps[i], 0, 0, null);
                        drawnSomething = true;
                    }
                }
                 Log.d(TAG, "Drew visible pencil layers. Drawn something: " + drawnSomething);

                 if (!drawnSomething) { // Если ни один слой не видим, делаем битмап прозрачным
                    bitmapToDisplay.recycle();
                    bitmapToDisplay = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
                    bitmapToDisplay.eraseColor(Color.TRANSPARENT);
                     Log.d(TAG, "No layers visible, displaying transparent bitmap.");
                 }


            } catch (OutOfMemoryError e) {
                 Log.e(TAG, "OutOfMemoryError creating result bitmap for pencil mode", e);
                 Toast.makeText(this, "Not enough memory to display layers", Toast.LENGTH_SHORT).show();
                 // Показываем оригинал в этом случае
                 bitmapToDisplay = originalBitmap;
            } catch (Exception e) {
                 Log.e(TAG, "Error creating result bitmap for pencil mode", e);
                 bitmapToDisplay = originalBitmap; // Показываем оригинал при ошибке
            }

        } else {
            // Если не режим карандаша, просто показываем оригинал
            Log.d(TAG, "Pencil mode is OFF, displaying original bitmap");
            bitmapToDisplay = originalBitmap;
        }

        // Устанавливаем итоговый битмап в ImageView
        imageView.setImageBitmap(bitmapToDisplay);
        // Устанавливаем текущую прозрачность
        setImageAlpha(transparencySeekBar.getProgress());
        // Делаем ImageView видимым
        imageView.setVisibility(View.VISIBLE);
        // Применяем текущие трансформации (масштаб, сдвиг)
        imageView.post(() -> { // post нужен, чтобы матрица применилась после установки битмапа
            imageView.setImageMatrix(matrix);
            imageView.invalidate();
        });
         Log.d(TAG, "Image display updated.");
    }


    // Показ диалога выбора слоев
    private void showLayerSelectionDialog() {
        Dialog dialog = new Dialog(this);
        // Используем наш кастомный макет для диалога
        dialog.setContentView(R.layout.dialog_layer_selection);
        dialog.setTitle(R.string.layer_selection_title); // Устанавливаем заголовок

        // Находим RecyclerView внутри макета диалога по ПРАВИЛЬНОМУ ID
        RecyclerView recyclerView = dialog.findViewById(R.id.recyclerView); // *** ИСПРАВЛЕНО ЗДЕСЬ ***

        if (recyclerView == null) {
             Log.e(TAG, "RecyclerView not found in dialog layout!");
             Toast.makeText(this, "Error creating layer dialog", Toast.LENGTH_SHORT).show();
             return;
        }

        recyclerView.setLayoutManager(new LinearLayoutManager(this)); // Задаем LayoutManager
        // Создаем и устанавливаем адаптер, передавая ему данные и слушатель (this)
        LayerAdapter adapter = new LayerAdapter(PENCIL_HARDNESS, layerVisibility, this);
        recyclerView.setAdapter(adapter);

        // Устанавливаем размер диалога (опционально)
        // Window window = dialog.getWindow();
        // if (window != null) {
        //    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // }

        dialog.show(); // Показываем диалог
    }

    // Метод интерфейса LayerAdapter.OnLayerVisibilityChangedListener
    // Вызывается, когда пользователь меняет состояние CheckBox в диалоге
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


    // Сохранение параметров (трансформации, режим, видимость слоев)
    private void saveParameters() {
        // TODO: Реализовать сохранение параметров в файл или SharedPreferences
        // Пример сохранения в файл (упрощенный):
        try {
            File file = new File(getFilesDir(), "parameters.dat");
            FileOutputStream fos = new FileOutputStream(file);
            // Сохраняем значения матрицы
            float[] matrixValues = new float[9];
            matrix.getValues(matrixValues);
            for (float value : matrixValues) {
                fos.write((value + ";").getBytes());
            }
            fos.write("\n".getBytes());
            // Сохраняем другие параметры
            fos.write(("pencilMode=" + isPencilMode + "\n").getBytes());
            fos.write(("imageVisible=" + isImageVisible + "\n").getBytes());
            fos.write(("transparency=" + transparencySeekBar.getProgress() + "\n").getBytes());
             // Сохраняем видимость слоев
             StringBuilder layersStr = new StringBuilder("layers=");
             for (boolean v : layerVisibility) {
                 layersStr.append(v).append(",");
             }
             fos.write(layersStr.toString().getBytes());


            fos.close();
            Toast.makeText(this, "Parameters saved (basic)", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e(TAG, "Error saving parameters", e);
            Toast.makeText(this, "Error saving parameters", Toast.LENGTH_LONG).show();
        }
    }

    // Загрузка параметров
    private void loadParameters() {
        // TODO: Реализовать загрузку параметров из файла или SharedPreferences
         try {
            File file = new File(getFilesDir(), "parameters.dat");
            if (!file.exists()) {
                Toast.makeText(this, "No saved parameters found", Toast.LENGTH_SHORT).show();
                return;
            }
            FileInputStream fis = new FileInputStream(file);
            byte[] buffer = new byte[(int) file.length()];
            int bytesRead = fis.read(buffer);
            fis.close();
             if (bytesRead <= 0) {
                 Toast.makeText(this, "Empty parameters file", Toast.LENGTH_LONG).show();
                 return;
             }

            String content = new String(buffer, 0, bytesRead);
            String[] lines = content.split("\n");
            // Пример разбора (нужно сделать более надежным)
             if (lines.length >= 5) {
                 // Загрузка матрицы
                 String[] matrixValuesStr = lines[0].split(";");
                 if (matrixValuesStr.length >= 9) {
                     float[] values = new float[9];
                     for (int i = 0; i < 9; i++) values[i] = Float.parseFloat(matrixValuesStr[i]);
                     matrix.setValues(values);
                 }
                 // Загрузка других параметров
                  for (int i = 1; i < lines.length; i++) {
                      String line = lines[i];
                      if (line.startsWith("pencilMode=")) {
                          isPencilMode = Boolean.parseBoolean(line.substring("pencilMode=".length()));
                          pencilModeSwitch.setChecked(isPencilMode);
                      } else if (line.startsWith("imageVisible=")) {
                          isImageVisible = Boolean.parseBoolean(line.substring("imageVisible=".length()));
                          hideImageCheckbox.setChecked(!isImageVisible);
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


                 applyTransformations();
                 updateImageDisplay();
                 Toast.makeText(this, "Parameters loaded", Toast.LENGTH_SHORT).show();
             } else {
                  Toast.makeText(this, "Invalid parameters file format", Toast.LENGTH_LONG).show();
             }

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
        stopBackgroundThread(); // Останавливаем фоновый поток
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy called");
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
                 previewSize = chooseOptimalPreviewSize(getPreviewSizes(), cameraSurfaceView.getWidth(), cameraSurfaceView.getHeight());
                 adjustSurfaceViewAspectRatioWithCropping(cameraSurfaceView.getWidth(), cameraSurfaceView.getHeight());
                 // Может потребоваться перезапуск сессии превью
                 closeCameraPreviewSession();
                 createCameraPreviewSession();
             }
             resetTransformationsAndFit();
             updateImageDisplay();
        });

    }
}
