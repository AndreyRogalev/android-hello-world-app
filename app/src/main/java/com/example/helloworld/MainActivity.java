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
import android.graphics.PointF; // Добавлен импорт PointF
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity implements LayerAdapter.OnLayerVisibilityChangedListener {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 100;
    private static final int REQUEST_STORAGE_PERMISSION = 101;
    private static final int PICK_IMAGE_REQUEST = 1;

    // UI Elements
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

    // Camera
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
    private String[] cameraIds;
    private int currentCameraIndex = 0;

    // Image Capture (Если кнопка не удаляется)
    private static final int CAPTURE_WIDTH = 1280;
    private static final int CAPTURE_HEIGHT = 720;

    // Image Manipulation
    private Bitmap originalBitmap;
    private Bitmap pencilBitmap;
    private Bitmap[] layerBitmaps;
    private boolean[] layerVisibility;
    private Matrix matrix = new Matrix();
    private Matrix savedMatrix = new Matrix(); // Матрица для сохранения состояния
    private float scaleFactor = 1.0f; // Текущий масштаб (управляется ScaleGestureDetector)
    // private float rotationAngle = 0.0f; // Эта переменная не используется для вращения жестом
    private boolean isPencilMode = false;
    private boolean isImageVisible = true;

    // Gesture Detection
    private ScaleGestureDetector scaleGestureDetector;
    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2; // Используем ZOOM для масштаба И вращения
    private int touchMode = NONE;
    private final PointF startPoint = new PointF(); // Начальная точка касания (для DRAG)
    private final PointF midPoint = new PointF(); // Средняя точка между пальцами (для ZOOM/ROTATE)
    private float initialAngle = 0f; // Начальный угол между пальцами

    // Pencil Mode Layers
    private static final String[] PENCIL_HARDNESS = {
            "9H", "8H", "7H", "6H", "5H", "4H", "3H", "2H", "H", "F",
            "HB", "B", "2B", "3B", "4B", "5B", "6B", "7B", "8B", "9B"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize UI
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

        // --- Initialize Gesture Detectors ---
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());
        imageView.setOnTouchListener(new TouchAndRotateListener()); // Используем новый Listener
        imageView.setScaleType(ImageView.ScaleType.MATRIX); // Важно для matrix

        // --- Setup Camera Surface ---
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
                // Мы больше не будем делать adjust тут, т.к. размер превью фиксирован
                if (cameraDevice != null && isSurfaceAvailable) {
                    // Пересоздаем сессию, если размер изменился значительно (опционально)
                    // closeCameraPreviewSession();
                    // previewSize = chooseOptimalPreviewSize(getPreviewSizes(), width, height);
                    // createCameraPreviewSession();
                    // Или просто игнорируем, если размер превью не должен меняться
                     startCameraPreview(); // Попробуем перезапустить превью
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                Log.d(TAG, "Surface destroyed");
                isSurfaceAvailable = false;
                closeCamera();
            }
        });

        // --- Setup UI Listeners ---
        transparencySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                setImageAlpha(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        pickImageButton.setOnClickListener(v -> pickImage());

        pencilModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isPencilMode = isChecked;
            layerSelectButton.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (isPencilMode) {
                processPencilEffect(); // Может занять время, лучше в фоне
            }
            updateImageDisplay();
        });

        layerSelectButton.setOnClickListener(v -> showLayerSelectionDialog());

        controlsVisibilityCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updateControlsVisibility(isChecked);
        });

        hideImageCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isImageVisible = !isChecked;
            updateImageDisplay();
        });

        saveParametersButton.setOnClickListener(v -> saveParameters());
        loadParametersButton.setOnClickListener(v -> loadParameters());
        switchCameraButton.setOnClickListener(v -> switchCamera());

        // --- Permissions & Camera Setup ---
        checkPermissionsAndSetupCamera();

        // Initialize Layer Visibility
        layerVisibility = new boolean[20];
        Arrays.fill(layerVisibility, true);
        updateControlsVisibility(controlsVisibilityCheckbox.isChecked()); // Начальная установка видимости
    }

    // --- Gesture Handling ---
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            if (touchMode == ZOOM) { // Применяем масштабирование только в режиме ZOOM
                float scale = detector.getScaleFactor();
                scaleFactor *= scale; // Обновляем общий масштаб (может быть не нужен)
                matrix.set(savedMatrix); // Восстанавливаем матрицу до начала жеста
                matrix.postScale(scale, scale, midPoint.x, midPoint.y); // Масштабируем от средней точки
                applyTransformations();
                 // Важно: Не сохраняем матрицу здесь, т.к. onScale вызывается много раз
                 // Сохранение происходит в ACTION_POINTER_DOWN / ACTION_DOWN
            }
            return true;
        }
         @Override
         public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
             // Не меняем touchMode здесь, он управляется в TouchAndRotateListener
             return originalBitmap != null && !originalBitmap.isRecycled();
         }
         @Override
         public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
             super.onScaleEnd(detector);
             // Ничего особенного не делаем при завершении масштабирования
         }
    }

    private class TouchAndRotateListener implements View.OnTouchListener {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (originalBitmap == null || originalBitmap.isRecycled()) return false;

            scaleGestureDetector.onTouchEvent(event); // Передаем событие ScaleGestureDetector'у

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: // Первое касание
                    savedMatrix.set(matrix);
                    startPoint.set(event.getX(), event.getY());
                    touchMode = DRAG;
                    Log.d(TAG, "Touch Mode: DRAG");
                    break;

                case MotionEvent.ACTION_POINTER_DOWN: // Второй палец (или больше)
                    if (event.getPointerCount() >= 2) {
                        savedMatrix.set(matrix); // Сохраняем матрицу в начале жеста масштабирования/вращения
                        midPoint(midPoint, event);
                        initialAngle = rotation(event);
                        touchMode = ZOOM;
                        Log.d(TAG, "Touch Mode: ZOOM/ROTATE");
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (touchMode == DRAG && event.getPointerCount() == 1 && !scaleGestureDetector.isInProgress()) {
                        // Перетаскивание одним пальцем
                        matrix.set(savedMatrix);
                        float dx = event.getX() - startPoint.x;
                        float dy = event.getY() - startPoint.y;
                        matrix.postTranslate(dx, dy);
                        applyTransformations();
                    } else if (touchMode == ZOOM && event.getPointerCount() >= 2) {
                        // Вращение двумя пальцами (масштабирование обрабатывается ScaleListener)
                        // Мы должны применить И вращение И масштаб от ScaleListener
                        float currentAngle = rotation(event);
                        float deltaAngle = currentAngle - initialAngle;

                        // Применяем вращение к ТЕКУЩЕЙ матрице (которая уже может быть смасштабирована ScaleListener)
                        // Важно: postRotate добавляет вращение к текущему состоянию
                         matrix.postRotate(deltaAngle, midPoint.x, midPoint.y);
                        // Обновляем начальный угол для следующего шага MOVE
                        initialAngle = currentAngle;
                         // Обновляем savedMatrix чтобы следующее вращение было относительно текущего состояния
                        // savedMatrix.set(matrix); // Это неправильно, сохраняем только в DOWN/POINTER_DOWN
                        applyTransformations(); // Применяем обновленную матрицу
                    }
                    break;

                case MotionEvent.ACTION_POINTER_UP:
                    if (event.getPointerCount() <= 2) { // Переход от 2+ пальцев к 1
                         touchMode = NONE; // Сначала сбрасываем режим
                         // Определяем оставшийся палец
                         int remainingPointerIndex = (event.getActionIndex() == 0) ? 1 : 0;
                         if(event.getPointerCount() > remainingPointerIndex) { // Убедимся, что индекс валиден
                             savedMatrix.set(matrix); // Сохраняем текущее состояние
                             startPoint.set(event.getX(remainingPointerIndex), event.getY(remainingPointerIndex));
                             touchMode = DRAG; // Переходим в режим перетаскивания
                             Log.d(TAG, "Touch Mode changed to DRAG after POINTER_UP");
                         } else {
                              Log.d(TAG, "Touch Mode: NONE (Pointer Up, <2 pointers left or invalid index)");
                         }
                    } else {
                        // Если было 3+ пальцев и один убрали, остаемся в режиме ZOOM
                        // Пересчитываем среднюю точку и угол (опционально, но может быть полезно)
                        midPoint(midPoint, event);
                        initialAngle = rotation(event);
                        savedMatrix.set(matrix); // Обновляем savedMatrix
                    }
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    touchMode = NONE;
                    Log.d(TAG, "Touch Mode: NONE (Up/Cancel)");
                    break;
            }
            return true; // Событие обработано
        }

        // Вспомогательный метод для вычисления средней точки
        private void midPoint(PointF point, MotionEvent event) {
            if (event.getPointerCount() < 2) return; // Не имеет смысла для одного пальца
            float x = event.getX(0) + event.getX(1);
            float y = event.getY(0) + event.getY(1);
            point.set(x / 2f, y / 2f);
        }

        // Вспомогательный метод для вычисления угла между двумя пальцами
        private float rotation(MotionEvent event) {
            if (event.getPointerCount() < 2) return 0f; // Не имеет смысла для одного пальца
            double delta_x = (event.getX(0) - event.getX(1));
            double delta_y = (event.getY(0) - event.getY(1));
            double radians = Math.atan2(delta_y, delta_x);
            return (float) Math.toDegrees(radians);
        }
    }

    // --- UI Updates ---
     private void updateControlsVisibility(boolean show) {
        int visibility = show ? View.VISIBLE : View.GONE;
        transparencySeekBar.setVisibility(visibility);
        pickImageButton.setVisibility(visibility);
        pencilModeSwitch.setVisibility(visibility);
        layerSelectButton.setVisibility(show && isPencilMode ? View.VISIBLE : View.GONE);
        hideImageCheckbox.setVisibility(visibility);
        saveParametersButton.setVisibility(visibility);
        loadParametersButton.setVisibility(visibility);
        switchCameraButton.setVisibility(visibility);
        // controlsVisibilityCheckbox остается видимым всегда
     }

    // --- Permission Handling ---
    private void checkPermissionsAndSetupCamera() {
         String[] permissionsToRequest = {
                 Manifest.permission.CAMERA,
                 Manifest.permission.READ_EXTERNAL_STORAGE,
                 Manifest.permission.WRITE_EXTERNAL_STORAGE // Все еще может быть нужен для сохранения параметров локально
         };
         List<String> permissionsNeeded = new ArrayList<>();
         for (String p : permissionsToRequest) {
             if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                 permissionsNeeded.add(p);
             }
         }

         if (!permissionsNeeded.isEmpty()) {
             ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), REQUEST_CAMERA_PERMISSION);
         } else {
             setupCamera(); // Все разрешения есть, настраиваем камеру
         }
     }

     @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean allGranted = true;
        if (requestCode == REQUEST_CAMERA_PERMISSION || requestCode == REQUEST_STORAGE_PERMISSION) {
             if (grantResults.length > 0) {
                 for (int result : grantResults) {
                     if (result != PackageManager.PERMISSION_GRANTED) {
                         allGranted = false;
                         break;
                     }
                 }
             } else {
                 allGranted = false; // Нет результатов - нет разрешений
             }

             if (allGranted) {
                 Log.d(TAG, "All required permissions granted.");
                 setupCamera(); // Разрешения получены, настраиваем камеру
                 if (isSurfaceAvailable && !isCameraOpen) {
                    openCamera(); // Если поверхность готова, открываем камеру
                 }
             } else {
                 Toast.makeText(this, "Required permissions are necessary to run the app", Toast.LENGTH_LONG).show();
                 // Возможно, стоит закрыть приложение или показать объяснение
                 // finish();
             }
        }
    }

     // --- Camera Setup & Control ---

     private void setupCamera() {
         CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
         try {
             cameraIds = manager.getCameraIdList();
             if (cameraIds.length > 0) {
                 // Предпочитаем заднюю камеру по умолчанию
                 String foundRearCamera = null;
                 for (String id : cameraIds) {
                      CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                      Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                      if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                          foundRearCamera = id;
                          break; // Нашли первую заднюю
                      }
                 }
                 cameraId = (foundRearCamera != null) ? foundRearCamera : cameraIds[0]; // Если задней нет, берем первую доступную
                 currentCameraIndex = Arrays.asList(cameraIds).indexOf(cameraId);
                 Log.d(TAG, "Default camera selected: ID=" + cameraId);
             } else {
                 Log.e(TAG, "No cameras found on device.");
                 Toast.makeText(this, "No cameras available", Toast.LENGTH_LONG).show();
                 cameraId = null;
             }
         } catch (CameraAccessException e) {
             Log.e(TAG, "Error accessing camera list", e);
             Toast.makeText(this, "Cannot access cameras", Toast.LENGTH_LONG).show();
         }
     }


    private void startBackgroundThread() {
        if (backgroundThread == null || !backgroundThread.isAlive()) {
            stopBackgroundThread(); // Останавливаем предыдущий, если есть
            backgroundThread = new HandlerThread("CameraBackground");
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());
             Log.d(TAG, "Background thread started.");
        }
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join(500); // Ждем недолго
                if (backgroundThread.isAlive()) {
                    Log.w(TAG, "Background thread did not finish stopping in time.");
                    // Можно попробовать interrupt, но это рискованно для Camera API
                }
                backgroundThread = null;
                backgroundHandler = null;
                Log.d(TAG, "Background thread stopped.");
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping background thread", e);
                Thread.currentThread().interrupt(); // Восстанавливаем флаг прерывания
            }
        }
    }

    private void openCamera() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
             checkPermissionsAndSetupCamera(); // Запрашиваем разрешения, если их нет
             return;
         }
        if (cameraId == null) {
            Log.e(TAG, "Cannot open camera, no camera ID selected.");
            return;
        }
        if (!isSurfaceAvailable) {
             Log.d(TAG, "Surface not available, delaying camera open.");
             isCameraPendingOpen = true;
             return;
         }
         if (isCameraOpen) {
             Log.d(TAG, "Camera already open.");
             return;
         }

        startBackgroundThread(); // Убедимся, что поток запущен
        if (backgroundHandler == null) {
             Log.e(TAG, "Background handler is null, cannot open camera.");
             return;
        }

        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
             CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
             Size[] previewSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                     .getOutputSizes(SurfaceHolder.class);
             previewSize = chooseOptimalPreviewSize(previewSizes, cameraSurfaceView.getWidth(), cameraSurfaceView.getHeight());
             Log.d(TAG, "Selected preview size: " + previewSize.getWidth() + "x" + previewSize.getHeight());


             // Инициализация ImageReader (если кнопка Capture не удалена)


             synchronized (cameraOpenCloseLock) { // Используем объект блокировки
                 if (!isCameraOpen) { // Двойная проверка на всякий случай
                     manager.openCamera(cameraId, cameraStateCallback, backgroundHandler);
                     isCameraOpen = true; // Устанавливаем флаг до вызова, чтобы избежать гонок
                     Log.d(TAG, "Opening camera: " + cameraId);
                 } else {
                      Log.d(TAG, "Camera open called, but already open inside lock.");
                 }
             }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Cannot access camera " + cameraId, e);
            Toast.makeText(this, "Cannot access camera", Toast.LENGTH_LONG).show();
            isCameraOpen = false; // Сбрасываем флаг при ошибке
        } catch (NullPointerException e) {
            Log.e(TAG, "NullPointerException during camera setup (StreamConfigurationMap?). Camera ID: " + cameraId, e);
            Toast.makeText(this, "Error getting camera configuration", Toast.LENGTH_LONG).show();
             isCameraOpen = false;
        } catch (IllegalStateException e) {
            Log.e(TAG, "IllegalStateException opening camera (maybe background thread died?)", e);
             isCameraOpen = false;
        }
    }

     // --- Camera State Callback ---
     private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
         @Override
         public void onOpened(@NonNull CameraDevice camera) {
             synchronized (cameraOpenCloseLock) {
                 cameraDevice = camera;
                 isCameraOpen = true;
                 isCameraPendingOpen = false; // Камера открыта, сбрасываем ожидание
                 Log.d(TAG, "Camera " + camera.getId() + " opened.");
             }
              // Теперь создаем сессию превью
             startCameraPreview();
         }

         @Override
         public void onDisconnected(@NonNull CameraDevice camera) {
              Log.w(TAG, "Camera " + camera.getId() + " disconnected.");
             closeCamera(); // Закрываем ресурсы корректно
         }

         @Override
         public void onError(@NonNull CameraDevice camera, int error) {
             Log.e(TAG, "Camera " + camera.getId() + " error: " + error);
             final String errorMsg = "Camera error: " + error;
             runOnUiThread(() -> Toast.makeText(MainActivity.this, errorMsg, Toast.LENGTH_LONG).show());
             closeCamera(); // Закрываем ресурсы корректно
         }
     };


    private Size[] getPreviewSizes() {
        if (cameraId == null) return new Size[]{ new Size(1280, 720) };
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            return characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(SurfaceHolder.class);
        } catch (CameraAccessException | NullPointerException e) {
            Log.e(TAG, "Error getting preview sizes for camera " + cameraId, e);
            return new Size[]{new Size(1280, 720)}; // Возвращаем дефолтный размер
        }
    }

    private Size chooseOptimalPreviewSize(Size[] choices, int viewWidth, int viewHeight) {
        if (choices == null || choices.length == 0) {
            Log.e(TAG, "No preview sizes available, using default");
            return new Size(1280, 720);
        }

        // Получаем соотношение сторон SurfaceView
        double targetRatio = (viewWidth > 0 && viewHeight > 0) ? (double) viewWidth / viewHeight : (16.0 / 9.0); // Используем 16:9 если размеры еще не известны

        Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;
        final int MAX_PREVIEW_AREA = 1920 * 1080; // Ограничиваем максимальный размер превью

        // Сначала ищем точное совпадение по соотношению сторон
        for (Size size : choices) {
             if (size.getWidth() * size.getHeight() > MAX_PREVIEW_AREA) continue; // Пропускаем слишком большие размеры
             double ratio = (double) size.getWidth() / size.getHeight();
             if (Math.abs(ratio - targetRatio) < 0.05) { // Допуск 5%
                 if (optimalSize == null || size.getWidth() * size.getHeight() > optimalSize.getWidth() * optimalSize.getHeight()) {
                     optimalSize = size; // Выбираем наибольший из подходящих
                 }
             }
        }

        // Если точного совпадения нет, ищем ближайшее по соотношению сторон
         if (optimalSize == null) {
             for (Size size : choices) {
                 if (size.getWidth() * size.getHeight() > MAX_PREVIEW_AREA) continue;
                 double ratio = (double) size.getWidth() / size.getHeight();
                 double diff = Math.abs(ratio - targetRatio);
                 if (diff < minDiff) {
                     minDiff = diff;
                     optimalSize = size;
                 } else if (diff == minDiff) { // Если разница одинаковая, берем больший размер
                     if (optimalSize == null || size.getWidth() * size.getHeight() > optimalSize.getWidth() * optimalSize.getHeight()) {
                         optimalSize = size;
                     }
                 }
             }
         }


        // Если все еще не нашли, берем самый большой доступный (в пределах лимита)
        if (optimalSize == null) {
             optimalSize = choices[0]; // Начнем с первого
             for(Size size : choices) {
                 if (size.getWidth() * size.getHeight() <= MAX_PREVIEW_AREA) {
                     if (size.getWidth() * size.getHeight() > optimalSize.getWidth() * optimalSize.getHeight()) {
                         optimalSize = size;
                     }
                 }
             }
        }

        Log.d(TAG, "Chosen preview size: " + optimalSize.getWidth() + "x" + optimalSize.getHeight() +
                " for view " + viewWidth + "x" + viewHeight + " (targetRatio=" + targetRatio + ")");
        return optimalSize;
    }

    private void startCameraPreview() {
         synchronized (cameraOpenCloseLock) {
             if (cameraDevice == null || !isSurfaceAvailable || !isCameraOpen || backgroundHandler == null) {
                 Log.w(TAG, "Cannot start preview - device/surface not ready or background handler is null.");
                 return;
             }

             try {
                 closeCameraPreviewSession(); // Закрываем предыдущую сессию, если была

                 if (previewSize == null) { // Убедимся что размер выбран
                     previewSize = chooseOptimalPreviewSize(getPreviewSizes(), cameraSurfaceView.getWidth(), cameraSurfaceView.getHeight());
                 }

                 // Устанавливаем размер SurfaceView под выбранное превью
                  runOnUiThread(() -> {
                      if (cameraSurfaceView != null && previewSize != null) {
                           ViewGroup.LayoutParams params = cameraSurfaceView.getLayoutParams();
                           // Простая установка размера (можно улучшить с сохранением пропорций)
                           params.width = previewSize.getWidth();
                           params.height = previewSize.getHeight();
                          //  cameraSurfaceView.setLayoutParams(params);
                           //  Log.d(TAG, "Resized SurfaceView for preview: " + params.width + "x" + params.height);
                           // Пробуем установить размер буфера SurfaceHolder
                           if(cameraSurfaceHolder != null && cameraSurfaceHolder.getSurface().isValid()){
                               cameraSurfaceHolder.setFixedSize(previewSize.getWidth(), previewSize.getHeight());
                                Log.d(TAG, "Set SurfaceHolder fixed size for preview: " + previewSize.getWidth() + "x" + previewSize.getHeight());
                           } else {
                               Log.w(TAG, "SurfaceHolder or Surface invalid when setting fixed size.");
                           }
                      }
                  });


                 Surface surface = cameraSurfaceView.getHolder().getSurface();
                 if (!surface.isValid()) {
                      Log.e(TAG, "Preview surface is invalid!");
                      return;
                 }

                 previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                 previewRequestBuilder.addTarget(surface);

                 List<Surface> surfaces = new ArrayList<>();
                 surfaces.add(surface);
                  } else {
                       Log.w(TAG, "ImageReader is null, capture button might not work.");
                  }


                 cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                     @Override
                     public void onConfigured(@NonNull CameraCaptureSession session) {
                         synchronized (cameraOpenCloseLock) {
                             if (cameraDevice == null || !isCameraOpen) {
                                 Log.w(TAG, "Camera closed during session configuration.");
                                 session.close(); // Закрываем сессию, если камера уже закрыта
                                 return;
                             }
                             cameraCaptureSession = session;
                             try {
                                 previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                 // previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON); // Автоэкспозиция
                                 cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                                 Log.d(TAG, "Camera preview session configured and repeating request started.");
                             } catch (CameraAccessException | IllegalStateException e) {
                                 Log.e(TAG, "Error setting repeating request for preview", e);
                             }
                         }
                     }

                     @Override
                     public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                         Log.e(TAG, "Failed to configure camera preview session.");
                         runOnUiThread(() -> Toast.makeText(MainActivity.this, "Failed to configure camera preview", Toast.LENGTH_LONG).show());
                     }
                 }, backgroundHandler);
             } catch (CameraAccessException | IllegalStateException | NullPointerException e) {
                 Log.e(TAG, "Error creating camera preview session", e);
                 runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error starting preview", Toast.LENGTH_SHORT).show());
             }
         }
     }

    private void closeCameraPreviewSession() {
         synchronized (cameraOpenCloseLock) {
             if (cameraCaptureSession != null) {
                 try {
                     cameraCaptureSession.close();
                     Log.d(TAG, "CameraCaptureSession closed.");
                 } catch (IllegalStateException e) {
                      Log.w(TAG, "IllegalStateException closing preview session (already closed?)", e);
                 } catch (Exception e){
                      Log.e(TAG, "Exception closing preview session", e);
                 } finally {
                     cameraCaptureSession = null;
                 }
             }
         }
     }

    private void closeCamera() {
         Log.d(TAG, "Closing camera...");
         try {
             // Ждем завершения текущих операций с камерой
             if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                 Log.e(TAG, "Timeout waiting for camera lock to close.");
                 // Возможно, стоит прервать фоновый поток или принять другие меры
                 // Но не будем блокировать UI поток надолго
                 // return; // Прерываем закрытие, если не получили лок
             }

             synchronized (cameraOpenCloseLock) { // Используем synchronized для доп. гарантии
                 closeCameraPreviewSession(); // Закрываем сессию

                 if (cameraDevice != null) {
                     cameraDevice.close();
                     cameraDevice = null;
                     Log.d(TAG, "CameraDevice closed.");
                 }
                 }
                 isCameraOpen = false;
                 isCameraPendingOpen = false; // Сбрасываем ожидание
             }

         } catch (InterruptedException e) {
              Log.e(TAG, "Interrupted while trying to acquire camera lock for closing.", e);
              Thread.currentThread().interrupt(); // Восстанавливаем статус прерывания
         } finally {
             if (cameraOpenCloseLock.availablePermits() == 0) { // Освобождаем лок, только если он был взят
                 cameraOpenCloseLock.release();
             }
             stopBackgroundThread(); // Останавливаем фоновый поток
             Log.d(TAG, "Camera close sequence finished.");
         }
     }


    private void switchCamera() {
        if (cameraIds == null || cameraIds.length < 2) {
             Toast.makeText(this, "Only one camera available", Toast.LENGTH_SHORT).show();
             return;
        }
        Log.d(TAG, "Switching camera...");
        closeCamera(); // Закрываем текущую камеру
        currentCameraIndex = (currentCameraIndex + 1) % cameraIds.length;
        cameraId = cameraIds[currentCameraIndex];
        Log.d(TAG, "Switched to camera ID: " + cameraId);
        previewSize = null; // Сбросить размер превью, чтобы он пересчитался
        openCamera(); // Открываем новую камеру
    }

    // --- Image Capture Logic (Если кнопка не удаляется) ---

            try {
                final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                captureBuilder.addTarget(imageReader.getSurface());

                // Настройки для фото
                captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE); // Автофокус
                // captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH); // Вспышка (если нужна)
                // captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation()); // Ориентация фото

                // Останавливаем превью перед съемкой (часто рекомендуется)
                // cameraCaptureSession.stopRepeating();
                // cameraCaptureSession.abortCaptures();

                cameraCaptureSession.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                        Log.d(TAG, "Image capture completed.");
                        // Можно снова запустить превью, если останавливали
                        // startCameraPreview();
                    }
                     @Override
                     public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull android.hardware.camera2.CaptureFailure failure) {
                         Log.e(TAG, "Image capture failed. Reason: " + failure.getReason());
                         Toast.makeText(MainActivity.this, "Capture failed", Toast.LENGTH_SHORT).show();
                         // Можно снова запустить превью, если останавливали
                         // startCameraPreview();
                     }
                }, backgroundHandler);
            } catch (CameraAccessException | IllegalStateException e) {
                Log.e(TAG, "Error initiating image capture", e);
                Toast.makeText(this, "Error capturing image", Toast.LENGTH_LONG).show();
            }
        }
    }

         ByteBuffer buffer = image.getPlanes()[0].getBuffer();
         byte[] bytes = new byte[buffer.remaining()];
         buffer.get(bytes);
         try {
             return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
         } catch (OutOfMemoryError e) {
             Log.e(TAG, "OutOfMemoryError decoding captured image", e);
             runOnUiThread(() -> Toast.makeText(this, "Capture OOM", Toast.LENGTH_SHORT).show());
             return null;
         }
     }

