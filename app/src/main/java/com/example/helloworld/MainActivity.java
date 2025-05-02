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
// import android.hardware.camera2.TotalCaptureResult; // Не используется
// import android.media.Image; // Не используется
// import android.media.ImageReader; // Не используется
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
import java.io.InputStream; // Добавлен для загрузки по Uri
// import java.nio.ByteBuffer; // Не используется
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit; // Добавлен для tryAcquire

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
    // private Button captureButton; // Удалено

    // Camera
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private String cameraId;
    private Size previewSize;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private final Object cameraOpenCloseLock = new Object(); // Можно использовать Semaphore, но Object проще для synchronized
    private volatile boolean isSurfaceAvailable = false;
    private volatile boolean isCameraPendingOpen = false;
    private volatile boolean isCameraOpen = false;
    private String[] cameraIds;
    private int currentCameraIndex = 0;

    // Image Capture (Логика удалена)
    // private ImageReader imageReader;
    // private static final int CAPTURE_WIDTH = 1280;
    // private static final int CAPTURE_HEIGHT = 720;

    // Image Manipulation
    private Bitmap originalBitmap;
    private Bitmap pencilBitmap;
    private Bitmap[] layerBitmaps;
    private boolean[] layerVisibility;
    private Matrix matrix = new Matrix();
    private Matrix savedMatrix = new Matrix();
    private float scaleFactor = 1.0f;
    private boolean isPencilMode = false;
    private boolean isImageVisible = true;

    // Gesture Detection
    private ScaleGestureDetector scaleGestureDetector;
    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    private int touchMode = NONE;
    private final PointF startPoint = new PointF();
    private final PointF midPoint = new PointF();
    private float initialAngle = 0f;

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
        // captureButton = findViewById(R.id.captureButton); // Удалено

        // --- Initialize Gesture Detectors ---
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());
        imageView.setOnTouchListener(new TouchAndRotateListener());
        imageView.setScaleType(ImageView.ScaleType.MATRIX);

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
                if (cameraDevice != null && isSurfaceAvailable) {
                    startCameraPreview(); // Перезапускаем превью при изменении поверхности
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
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { setImageAlpha(progress); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        pickImageButton.setOnClickListener(v -> pickImage());

        pencilModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isPencilMode = isChecked;
            layerSelectButton.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (isPencilMode) {
                processPencilEffect();
            } else {
                recyclePencilBitmaps(); // Очищаем слои при выключении режима
            }
            updateImageDisplay();
        });

        layerSelectButton.setOnClickListener(v -> showLayerSelectionDialog());
        controlsVisibilityCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> updateControlsVisibility(isChecked));
        hideImageCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isImageVisible = !isChecked;
            updateImageDisplay();
        });

        saveParametersButton.setOnClickListener(v -> saveParameters());
        loadParametersButton.setOnClickListener(v -> loadParameters());
        switchCameraButton.setOnClickListener(v -> switchCamera());
        // captureButton.setOnClickListener(v -> captureImage()); // Удалено

        // --- Permissions & Camera Setup ---
        checkPermissionsAndSetupCamera();

        // Initialize Layer Visibility
        layerVisibility = new boolean[20];
        Arrays.fill(layerVisibility, true);
        updateControlsVisibility(controlsVisibilityCheckbox.isChecked());
    }

    // --- Gesture Handling ---
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            if (touchMode == ZOOM) {
                float scale = detector.getScaleFactor();
                scaleFactor *= scale;
                matrix.set(savedMatrix);
                matrix.postScale(scale, scale, midPoint.x, midPoint.y);
                // Вращение применяется в TouchAndRotateListener, здесь только масштабируем
                // applyTransformations(); // Применение будет в TouchAndRotateListener
            }
            return true;
        }
        @Override public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
             return originalBitmap != null && !originalBitmap.isRecycled();
         }
    }

    private class TouchAndRotateListener implements View.OnTouchListener {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (originalBitmap == null || originalBitmap.isRecycled()) return false;

            scaleGestureDetector.onTouchEvent(event); // Передаем событие ScaleGestureDetector'у

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    savedMatrix.set(matrix);
                    startPoint.set(event.getX(), event.getY());
                    touchMode = DRAG;
                    Log.d(TAG, "Touch Mode: DRAG");
                    break;

                case MotionEvent.ACTION_POINTER_DOWN:
                    if (event.getPointerCount() >= 2) {
                        savedMatrix.set(matrix);
                        midPoint(midPoint, event);
                        initialAngle = rotation(event);
                        touchMode = ZOOM;
                        Log.d(TAG, "Touch Mode: ZOOM/ROTATE");
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                    if (touchMode == DRAG && event.getPointerCount() == 1 && !scaleGestureDetector.isInProgress()) {
                        matrix.set(savedMatrix);
                        float dx = event.getX() - startPoint.x;
                        float dy = event.getY() - startPoint.y;
                        matrix.postTranslate(dx, dy);
                        applyTransformations();
                    } else if (touchMode == ZOOM && event.getPointerCount() >= 2) {
                        // --- Применяем и масштаб (из ScaleListener) и вращение ---
                        // Масштаб уже применен к 'matrix' внутри onScale
                        float currentAngle = rotation(event);
                        float deltaAngle = currentAngle - initialAngle;

                        // Вращаем текущую (уже смасштабированную) матрицу
                        matrix.postRotate(deltaAngle, midPoint.x, midPoint.y);

                        applyTransformations(); // Применяем итоговую матрицу
                        // НЕ обновляем savedMatrix здесь, только угол
                        // savedMatrix.set(matrix);
                        // Обновляем угол для следующего шага
                        // initialAngle = currentAngle; // Нет, иначе вращение будет кумулятивным за один жест MOVE
                    }
                    break;

                case MotionEvent.ACTION_POINTER_UP:
                     // Обновляем savedMatrix когда палец убирается, чтобы сохранить текущее состояние
                     savedMatrix.set(matrix);
                    if (event.getPointerCount() <= 2) {
                         touchMode = NONE; // Сначала сбрасываем режим
                         int remainingPointerIndex = (event.getActionIndex() == 0) ? 1 : 0;
                         if(event.getPointerCount() > remainingPointerIndex) {
                             // savedMatrix УЖЕ СОХРАНЕНА выше
                             startPoint.set(event.getX(remainingPointerIndex), event.getY(remainingPointerIndex));
                             touchMode = DRAG;
                             Log.d(TAG, "Touch Mode changed to DRAG after POINTER_UP");
                         } else {
                              Log.d(TAG, "Touch Mode: NONE (Pointer Up, <2 pointers left or invalid index)");
                         }
                    }
                    // Если пальцев было больше 2, остаемся в режиме ZOOM, savedMatrix обновлена
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    touchMode = NONE;
                    Log.d(TAG, "Touch Mode: NONE (Up/Cancel)");
                    break;
            }
            return true;
        }

        private void midPoint(PointF point, MotionEvent event) {
            if (event.getPointerCount() < 2) return;
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
        // captureButton.setVisibility(visibility); // Удалено
     }

    // --- Permission Handling ---
    private void checkPermissionsAndSetupCamera() {
         String[] permissionsToRequest = {
                 Manifest.permission.CAMERA,
                 Manifest.permission.READ_EXTERNAL_STORAGE,
                 Manifest.permission.WRITE_EXTERNAL_STORAGE
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
             setupCamera();
         }
     }

     @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean allGranted = true;
        if (requestCode == REQUEST_CAMERA_PERMISSION || requestCode == REQUEST_STORAGE_PERMISSION) {
             if (grantResults.length > 0) {
                 for (int result : grantResults) {
                     if (result != PackageManager.PERMISSION_GRANTED) { allGranted = false; break; }
                 }
             } else { allGranted = false; }

             if (allGranted) {
                 Log.d(TAG, "All required permissions granted.");
                 setupCamera();
                 if (isSurfaceAvailable && !isCameraOpen) { openCamera(); }
             } else {
                 Toast.makeText(this, "Required permissions are necessary", Toast.LENGTH_LONG).show();
             }
        }
    }

     // --- Camera Setup & Control ---
     private void setupCamera() {
         CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
         try {
             cameraIds = manager.getCameraIdList();
             if (cameraIds.length > 0) {
                 String foundRearCamera = null;
                 for (String id : cameraIds) {
                      CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                      Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                      if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { foundRearCamera = id; break; }
                 }
                 cameraId = (foundRearCamera != null) ? foundRearCamera : cameraIds[0];
                 currentCameraIndex = Arrays.asList(cameraIds).indexOf(cameraId);
                 Log.d(TAG, "Default camera selected: ID=" + cameraId);
             } else {
                 Log.e(TAG, "No cameras found.");
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
            stopBackgroundThread();
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
                backgroundThread.join(500);
                if (backgroundThread.isAlive()) { Log.w(TAG, "Background thread did not stop."); }
                backgroundThread = null;
                backgroundHandler = null;
                Log.d(TAG, "Background thread stopped.");
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping background thread", e);
                Thread.currentThread().interrupt();
            }
        }
    }

    private void openCamera() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { checkPermissionsAndSetupCamera(); return; }
        if (cameraId == null) { Log.e(TAG, "Cannot open camera, no camera ID."); return; }
        if (!isSurfaceAvailable) { Log.d(TAG, "Surface not available, delaying camera open."); isCameraPendingOpen = true; return; }
        if (isCameraOpen) { Log.d(TAG, "Camera already open."); return; }

        startBackgroundThread();
        if (backgroundHandler == null) { Log.e(TAG, "Background handler is null."); return; }

        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
             CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
             Size[] previewSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(SurfaceHolder.class);
             previewSize = chooseOptimalPreviewSize(previewSizes, cameraSurfaceView.getWidth(), cameraSurfaceView.getHeight());
             Log.d(TAG, "Selected preview size: " + previewSize);

             // ImageReader больше не нужен

             synchronized (cameraOpenCloseLock) {
                 if (!isCameraOpen) {
                     manager.openCamera(cameraId, cameraStateCallback, backgroundHandler);
                     isCameraOpen = true;
                     Log.d(TAG, "Opening camera: " + cameraId);
                 } else { Log.d(TAG, "Camera already open inside lock."); }
             }
        } catch (CameraAccessException | NullPointerException | IllegalStateException | SecurityException e) { // Добавили SecurityException
            Log.e(TAG, "Failed to open camera " + cameraId, e);
            isCameraOpen = false;
            runOnUiThread(() -> Toast.makeText(this, "Failed to open camera", Toast.LENGTH_SHORT).show());
        }
    }

     private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
         @Override
         public void onOpened(@NonNull CameraDevice camera) {
             synchronized (cameraOpenCloseLock) {
                 cameraDevice = camera;
                 isCameraOpen = true;
                 isCameraPendingOpen = false;
                 Log.d(TAG, "Camera " + camera.getId() + " opened.");
             }
             startCameraPreview();
         }
         @Override public void onDisconnected(@NonNull CameraDevice camera) { Log.w(TAG, "Camera " + camera.getId() + " disconnected."); closeCamera(); }
         @Override public void onError(@NonNull CameraDevice camera, int error) {
             Log.e(TAG, "Camera " + camera.getId() + " error: " + error);
             runOnUiThread(() -> Toast.makeText(MainActivity.this, "Camera error: " + error, Toast.LENGTH_LONG).show());
             closeCamera();
         }
     };

    private Size[] getPreviewSizes() {
        if (cameraId == null) return new Size[]{ new Size(1280, 720) };
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            return characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(SurfaceHolder.class);
        } catch (CameraAccessException | NullPointerException e) {
            Log.e(TAG, "Error getting preview sizes for camera " + cameraId, e);
            return new Size[]{new Size(1280, 720)};
        }
    }

    private Size chooseOptimalPreviewSize(Size[] choices, int viewWidth, int viewHeight) {
        if (choices == null || choices.length == 0) { return new Size(1280, 720); }
        double targetRatio = (viewWidth > 0 && viewHeight > 0) ? (double) viewWidth / viewHeight : (16.0 / 9.0);
        Size optimalSize = null; double minDiff = Double.MAX_VALUE;
        final int MAX_PREVIEW_AREA = 1920 * 1080;

        List<Size> suitableSizes = new ArrayList<>();
        for (Size size : choices) {
             if (size.getWidth() * size.getHeight() <= MAX_PREVIEW_AREA) {
                 suitableSizes.add(size);
             }
        }
        if(suitableSizes.isEmpty()) { // Если все размеры слишком большие, берем наименьший из доступных
             return Collections.min(Arrays.asList(choices), Comparator.comparingLong(s -> (long)s.getWidth() * s.getHeight()));
        }

        // Ищем лучший среди подходящих по размеру
        for (Size size : suitableSizes) {
             double ratio = (double) size.getWidth() / size.getHeight();
             double diff = Math.abs(ratio - targetRatio);
             if (diff < minDiff) {
                 minDiff = diff;
                 optimalSize = size;
             } else if (diff == minDiff) {
                 if (optimalSize == null || size.getWidth() * size.getHeight() > optimalSize.getWidth() * optimalSize.getHeight()) {
                     optimalSize = size;
                 }
             }
        }
         // Если совсем ничего не подошло по соотношению, берем самый большой из подходящих по размеру
         if (optimalSize == null) {
              optimalSize = Collections.max(suitableSizes, Comparator.comparingLong(s -> (long)s.getWidth() * s.getHeight()));
         }

        Log.d(TAG, "Chosen preview size: " + optimalSize + " for view " + viewWidth + "x" + viewHeight);
        return optimalSize;
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
                 }

                 Surface surface = cameraSurfaceView.getHolder().getSurface();
                 if (!surface.isValid()) { Log.e(TAG, "Preview surface is invalid!"); return; }

                  // Устанавливаем размер буфера SurfaceHolder
                  final Size finalPreviewSize = previewSize; // Для лямбды
                   runOnUiThread(() -> {
                       if(cameraSurfaceView.getHolder() != null && cameraSurfaceView.getHolder().getSurface().isValid()){
                           cameraSurfaceView.getHolder().setFixedSize(finalPreviewSize.getWidth(), finalPreviewSize.getHeight());
                           Log.d(TAG, "Set SurfaceHolder fixed size for preview: " + finalPreviewSize);
                       } else {
                           Log.w(TAG, "SurfaceHolder or Surface invalid when setting fixed size in startPreview.");
                       }
                   });


                 previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                 previewRequestBuilder.addTarget(surface);

                 // Используем Collections.singletonList, так как ImageReader удален
                 cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                     @Override
                     public void onConfigured(@NonNull CameraCaptureSession session) {
                         synchronized (cameraOpenCloseLock) {
                             if (cameraDevice == null || !isCameraOpen) { session.close(); return; }
                             cameraCaptureSession = session;
                             try {
                                 previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                 cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                                 Log.d(TAG, "Camera preview session configured and repeating request started.");
                             } catch (CameraAccessException | IllegalStateException e) {
                                 Log.e(TAG, "Error setting repeating request for preview", e);
                             }
                         }
                     }
                     @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                         Log.e(TAG, "Failed to configure camera preview session.");
                         runOnUiThread(() -> Toast.makeText(MainActivity.this, "Failed to configure camera preview", Toast.LENGTH_LONG).show());
                     }
                 }, backgroundHandler);
             } catch (CameraAccessException | IllegalStateException | NullPointerException e) {
                 Log.e(TAG, "Error creating camera preview session", e);
                 runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error starting preview", Toast.LENGTH_SHORT).show());
             }
         }
     } // Конец startCameraPreview

    private void closeCameraPreviewSession() {
         synchronized (cameraOpenCloseLock) {
             if (cameraCaptureSession != null) {
                 try {
                     cameraCaptureSession.close();
                     Log.d(TAG, "CameraCaptureSession closed.");
                 } catch (Exception e){ Log.e(TAG, "Exception closing preview session", e); }
                 finally { cameraCaptureSession = null; }
             }
         }
     } // Конец closeCameraPreviewSession

    private void closeCamera() {
         Log.d(TAG, "Closing camera...");
         try {
             // Не будем использовать tryAcquire здесь, используем synchronized block
             synchronized(cameraOpenCloseLock) {
                 closeCameraPreviewSession();

                 if (cameraDevice != null) {
                     cameraDevice.close();
                     cameraDevice = null;
                     Log.d(TAG, "CameraDevice closed.");
                 }
                 // ImageReader удален, его закрывать не нужно
                 isCameraOpen = false;
                 isCameraPendingOpen = false;
             } // Конец synchronized block
         } catch(Exception e) { // Ловим любые неожиданные ошибки
             Log.e(TAG, "Unexpected error during camera close", e);
         } finally {
             stopBackgroundThread(); // Останавливаем фоновый поток в любом случае
             Log.d(TAG, "Camera close sequence finished.");
         }
     } // Конец closeCamera

    private void switchCamera() {
        if (cameraIds == null || cameraIds.length < 2) { Toast.makeText(this, "Only one camera", Toast.LENGTH_SHORT).show(); return; }
        Log.d(TAG, "Switching camera...");
        closeCamera();
        currentCameraIndex = (currentCameraIndex + 1) % cameraIds.length;
        cameraId = cameraIds[currentCameraIndex];
        Log.d(TAG, "Switched to camera ID: " + cameraId);
        previewSize = null;
        openCamera();
    } // Конец switchCamera

    // --- Логика Capture Image Удалена ---
    // private void captureImage() { ... }
    // private Bitmap imageToBitmap(Image image) { ... }
    // private void processCapturedImage(final Bitmap capturedBitmap) { ... }

    // --- Image Picking ---
    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        checkPermissionsAndSetupCamera(); // Проверяем разрешение на чтение
         try { startActivityForResult(intent, PICK_IMAGE_REQUEST); }
         catch (Exception e) { Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show(); }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri imageUri = data.getData();
            if (imageUri != null) { loadImageFromUri(imageUri); }
            else { Toast.makeText(this, "Failed to get image URI", Toast.LENGTH_SHORT).show(); }
        }
    }

     private void loadImageFromUri(Uri imageUri) {
         try {
             new Thread(() -> {
                 Bitmap loadedBitmap = null;
                 try (InputStream inputStream = getContentResolver().openInputStream(imageUri)) {
                     BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 2; // Пример уменьшения
                     loadedBitmap = BitmapFactory.decodeStream(inputStream, null, options);
                 } catch (IOException | OutOfMemoryError e) { Log.e(TAG, "Error loading image", e); runOnUiThread(()-> Toast.makeText(this, "Error loading image", Toast.LENGTH_SHORT).show()); }

                 final Bitmap finalBitmap = loadedBitmap;
                 runOnUiThread(() -> {
                     if (finalBitmap != null) {
                         recycleBitmaps(); // Очищаем старые
                         originalBitmap = finalBitmap;
                          if (isPencilMode) { isPencilMode = false; pencilModeSwitch.setChecked(false); layerSelectButton.setVisibility(View.GONE); }
                         layerVisibility = new boolean[20]; Arrays.fill(layerVisibility, true);
                         resetTransformationsAndFit();
                         updateImageDisplay();
                     } else { Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show(); }
                 });
             }).start();
         } catch (Exception e) { Log.e(TAG, "Error starting image loading", e); }
     } // Конец loadImageFromUri

    // --- Image Transformation & Display ---
    private void resetTransformationsAndFit() {
        matrix.reset();
        if (originalBitmap == null || originalBitmap.isRecycled() || imageView.getWidth() == 0 || imageView.getHeight() == 0) {
            scaleFactor = 1.0f; if (imageView != null) runOnUiThread(()-> imageView.setImageMatrix(matrix)); return;
        }
        final float viewWidth = imageView.getWidth(); final float viewHeight = imageView.getHeight();
        final float bmpWidth = originalBitmap.getWidth(); final float bmpHeight = originalBitmap.getHeight();
        float scale = Math.min(viewWidth / bmpWidth, viewHeight / bmpHeight);
        float dx = (viewWidth - bmpWidth * scale) / 2f; float dy = (viewHeight - bmpHeight * scale) / 2f;
        matrix.setScale(scale, scale); matrix.postTranslate(dx, dy); scaleFactor = scale;
        Log.d(TAG, "Image reset & fit. Scale: " + scaleFactor);
        applyTransformations();
    } // Конец resetTransformationsAndFit

    private void applyTransformations() { if (imageView != null) { runOnUiThread(() -> imageView.setImageMatrix(matrix)); } } // Конец applyTransformations

    private void setImageAlpha(int progress) { float alpha = Math.max(0.0f, Math.min(1.0f, progress / 100.0f)); if (imageView != null) { runOnUiThread(() -> imageView.setAlpha(alpha)); } } // Конец setImageAlpha

    // --- Pencil Effect Logic ---
     private void processPencilEffect() {
         Log.d(TAG, "Processing pencil effect requested.");
         if (originalBitmap == null || originalBitmap.isRecycled()) { /* ... обработка ошибки ... */ return; }
          new Thread(() -> {
              Log.d(TAG, "Starting pencil effect processing in background.");
              recyclePencilBitmaps();
              Bitmap grayBitmap = null; Bitmap[] newLayerBitmaps = new Bitmap[PENCIL_HARDNESS.length];
              boolean success = false;
              try {
                  int width = originalBitmap.getWidth(); int height = originalBitmap.getHeight();
                  grayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                  Canvas canvasGray = new Canvas(grayBitmap); Paint paintGray = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                  ColorMatrix cmGray = new ColorMatrix(); cmGray.setSaturation(0); paintGray.setColorFilter(new ColorMatrixColorFilter(cmGray));
                  canvasGray.drawBitmap(originalBitmap, 0, 0, paintGray);
                  int[] pixels = new int[width * height]; grayBitmap.getPixels(pixels, 0, width, 0, 0, width, height);
                  int[][] layerPixels = new int[newLayerBitmaps.length][width * height];
                  for (int i = 0; i < newLayerBitmaps.length; i++) { Arrays.fill(layerPixels[i], Color.TRANSPARENT); }
                  for (int i = 0; i < pixels.length; i++) {
                      int gray = Color.red(pixels[i]); int layerIndex = getLayerIndex(gray);
                      if (layerIndex >= 0 && layerIndex < newLayerBitmaps.length) { layerPixels[layerIndex][i] = pixels[i]; }
                  }
                  pixels = null; grayBitmap.recycle(); grayBitmap = null;
                  for (int i = 0; i < newLayerBitmaps.length; i++) { newLayerBitmaps[i] = Bitmap.createBitmap(layerPixels[i], width, height, Bitmap.Config.ARGB_8888); layerPixels[i] = null; }
                  layerPixels = null; success = true; Log.d(TAG, "Pencil effect processed successfully.");
              } catch (OutOfMemoryError e) { Log.e(TAG, "OOM processing pencil effect", e); if (grayBitmap != null && !grayBitmap.isRecycled()) grayBitmap.recycle(); for(Bitmap b : newLayerBitmaps) if(b!=null && !b.isRecycled()) b.recycle(); runOnUiThread(() -> Toast.makeText(this, "OOM for pencil effect", Toast.LENGTH_LONG).show()); }
              catch (Exception e) { Log.e(TAG, "Error processing pencil effect", e); runOnUiThread(() -> Toast.makeText(this, "Error creating pencil layers", Toast.LENGTH_SHORT).show()); }

              final boolean finalSuccess = success; final Bitmap[] finalLayerBitmaps = newLayerBitmaps;
              runOnUiThread(() -> {
                  if (finalSuccess) { pencilBitmap = null; layerBitmaps = finalLayerBitmaps; }
                  else { isPencilMode = false; pencilModeSwitch.setChecked(false); layerSelectButton.setVisibility(View.GONE); layerBitmaps = null; }
                  updateImageDisplay();
              });
          }).start();
      } // Конец processPencilEffect

      private int getLayerIndex(int grayValue) { int numLayers = PENCIL_HARDNESS.length; if (numLayers <= 0) return -1; int index = (int) (((float)grayValue / 256.0f) * numLayers); return Math.max(0, Math.min(index, numLayers - 1)); } // Конец getLayerIndex

     private void recyclePencilBitmaps() {
         if (pencilBitmap != null && !pencilBitmap.isRecycled()) { pencilBitmap.recycle(); Log.d(TAG, "Recycled pencilBitmap"); } pencilBitmap = null;
         if (layerBitmaps != null) { for (Bitmap b : layerBitmaps) { if (b != null && !b.isRecycled()) { b.recycle(); } } Log.d(TAG, "Recycled layerBitmaps."); layerBitmaps = null; }
     } // Конец recyclePencilBitmaps

     private void updateImageDisplay() {
         Log.d(TAG, "Updating image display: isPencilMode=" + isPencilMode + ", isImageVisible=" + isImageVisible);
         if (imageView == null) return;
         if (!isImageVisible || originalBitmap == null || originalBitmap.isRecycled()) { runOnUiThread(() -> { imageView.setImageBitmap(null); imageView.setVisibility(View.INVISIBLE); }); return; }

          new Thread(() -> {
              Bitmap bitmapToDisplay = null; boolean displayOriginal = true;
              if (isPencilMode && layerBitmaps != null) {
                   Log.d(TAG, "Composing pencil layers...");
                   try {
                       bitmapToDisplay = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
                       Canvas canvas = new Canvas(bitmapToDisplay); canvas.drawColor(Color.TRANSPARENT);
                       Paint layerPaint = new Paint(Paint.FILTER_BITMAP_FLAG); boolean drawnSomething = false;
                       for (int i = 0; i < layerBitmaps.length; i++) { if (layerVisibility[i] && layerBitmaps[i] != null && !layerBitmaps[i].isRecycled()) { canvas.drawBitmap(layerBitmaps[i], 0, 0, layerPaint); drawnSomething = true; } }
                       if (!drawnSomething) { if (bitmapToDisplay != null && !bitmapToDisplay.isRecycled()) bitmapToDisplay.recycle(); bitmapToDisplay = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); bitmapToDisplay.eraseColor(Color.TRANSPARENT); }
                       displayOriginal = false;
                   } catch (OutOfMemoryError e) { Log.e(TAG, "OOM composing layers", e); bitmapToDisplay = null; runOnUiThread(() -> Toast.makeText(this, "OOM displaying layers", Toast.LENGTH_SHORT).show()); }
                   catch (Exception e) { Log.e(TAG, "Error composing layers", e); bitmapToDisplay = null; runOnUiThread(() -> Toast.makeText(this, "Error displaying layers", Toast.LENGTH_SHORT).show()); }
              }
              if (displayOriginal) { bitmapToDisplay = originalBitmap; }
              final Bitmap finalBitmap = bitmapToDisplay; final boolean finalDisplayOriginal = displayOriginal;
              runOnUiThread(() -> {
                  if (imageView != null) {
                       if (finalBitmap != null && !finalBitmap.isRecycled()) {
                           imageView.setImageBitmap(finalBitmap); imageView.setVisibility(View.VISIBLE); imageView.setImageMatrix(matrix); setImageAlpha(transparencySeekBar.getProgress()); imageView.invalidate();
                           Log.d(TAG, "ImageView updated with " + (finalDisplayOriginal ? "original" : "composed") + " bitmap.");
                       } else { imageView.setImageBitmap(null); imageView.setVisibility(View.INVISIBLE); }
                  }
              });
          }).start();
      } // Конец updateImageDisplay

    // --- Layer Selection Dialog ---
    private void showLayerSelectionDialog() {
        Dialog dialog = new Dialog(this); dialog.setContentView(R.layout.dialog_layer_selection); dialog.setTitle(R.string.layer_selection_title);
        RecyclerView recyclerView = dialog.findViewById(R.id.layerRecyclerView);
        if (recyclerView == null) { Log.e(TAG, "RecyclerView 'layerRecyclerView' not found!"); return; }
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        LayerAdapter adapter = new LayerAdapter(PENCIL_HARDNESS, layerVisibility, this);
        recyclerView.setAdapter(adapter); dialog.show();
    } // Конец showLayerSelectionDialog

    @Override
    public void onLayerVisibilityChanged(int position, boolean isVisible) {
        if (position >= 0 && position < layerVisibility.length) { layerVisibility[position] = isVisible; Log.d(TAG, "Layer " + position + " visibility: " + isVisible); updateImageDisplay(); }
        else { Log.w(TAG, "Invalid position from LayerAdapter: " + position); }
    } // Конец onLayerVisibilityChanged

    // --- Save/Load Parameters ---
    private void saveParameters() {
        try {
            File file = new File(getFilesDir(), "parameters.dat");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(("scaleFactor=" + scaleFactor + "\n").getBytes());
                float[] matrixValues = new float[9]; matrix.getValues(matrixValues); StringBuilder matrixStr = new StringBuilder("matrix=");
                for (int i = 0; i < matrixValues.length; i++) { matrixStr.append(matrixValues[i]); if (i < 8) matrixStr.append(","); }
                fos.write((matrixStr.toString() + "\n").getBytes());
                fos.write(("isPencilMode=" + isPencilMode + "\n").getBytes());
                fos.write(("isImageVisible=" + isImageVisible + "\n").getBytes());
                fos.write(("controlsVisible=" + controlsVisibilityCheckbox.isChecked() + "\n").getBytes());
                fos.write(("transparency=" + transparencySeekBar.getProgress() + "\n").getBytes());
                StringBuilder layersStr = new StringBuilder("layerVisibility=");
                for (int i = 0; i < layerVisibility.length; i++) { layersStr.append(layerVisibility[i]); if (i < layerVisibility.length - 1) layersStr.append(","); }
                fos.write((layersStr.toString() + "\n").getBytes());
                Toast.makeText(this, "Parameters saved", Toast.LENGTH_SHORT).show(); Log.d(TAG, "Parameters saved to " + file.getAbsolutePath());
            }
        } catch (IOException e) { Log.e(TAG, "Error saving parameters", e); Toast.makeText(this, "Error saving parameters", Toast.LENGTH_LONG).show(); }
    } // Конец saveParameters

    private void loadParameters() {
        try {
            File file = new File(getFilesDir(), "parameters.dat");
            if (!file.exists()) { Toast.makeText(this, "No saved parameters", Toast.LENGTH_SHORT).show(); return; }
            try (FileInputStream fis = new FileInputStream(file); java.util.Scanner scanner = new java.util.Scanner(fis)) {
                boolean loadedPencilMode = isPencilMode;
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine(); String[] parts = line.split("=", 2); if (parts.length != 2) continue;
                    String key = parts[0]; String value = parts[1];
                    try {
                        switch (key) {
                            case "scaleFactor": scaleFactor = Float.parseFloat(value); break;
                            case "matrix": String[] matrixValuesStr = value.split(","); if (matrixValuesStr.length == 9) { float[] values = new float[9]; for (int i = 0; i < 9; i++) values[i] = Float.parseFloat(matrixValuesStr[i]); matrix.setValues(values); float scaleX = values[Matrix.MSCALE_X], skewY = values[Matrix.MSKEW_Y]; scaleFactor = (float)Math.sqrt(scaleX * scaleX + skewY * skewY); } break;
                            case "isPencilMode": isPencilMode = Boolean.parseBoolean(value); break;
                            case "isImageVisible": isImageVisible = Boolean.parseBoolean(value); break;
                             case "controlsVisible": controlsVisibilityCheckbox.setChecked(Boolean.parseBoolean(value)); break;
                             case "transparency": transparencySeekBar.setProgress(Integer.parseInt(value)); break;
                            case "layerVisibility": String[] visibilityValues = value.split(","); for (int i = 0; i < layerVisibility.length && i < visibilityValues.length; i++) layerVisibility[i] = Boolean.parseBoolean(visibilityValues[i]); break;
                        }
                    } catch (NumberFormatException e) { Log.w(TAG, "Error parsing value for '" + key + "'", e); }
                }
                 pencilModeSwitch.setChecked(isPencilMode); hideImageCheckbox.setChecked(!isImageVisible); updateControlsVisibility(controlsVisibilityCheckbox.isChecked()); applyTransformations();
                 if (isPencilMode && originalBitmap != null) { if (!loadedPencilMode) processPencilEffect(); else updateImageDisplay(); } else updateImageDisplay();
                Toast.makeText(this, "Parameters loaded", Toast.LENGTH_SHORT).show(); Log.d(TAG, "Parameters loaded from " + file.getAbsolutePath());
            }
        } catch (IOException | NumberFormatException e) { Log.e(TAG, "Error loading parameters", e); Toast.makeText(this, "Error loading parameters", Toast.LENGTH_LONG).show(); }
    } // Конец loadParameters

    // --- Activity Lifecycle ---
    @Override
    protected void onResume() {
        super.onResume(); Log.d(TAG, "onResume"); startBackgroundThread();
        if (isSurfaceAvailable) { Log.d(TAG, "Surface available onResume."); if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { if (!isCameraOpen) openCamera(); else startCameraPreview(); } else checkPermissionsAndSetupCamera(); }
        else { Log.d(TAG, "Surface not available onResume."); }
    } // Конец onResume

    @Override
    protected void onPause() { Log.d(TAG, "onPause"); closeCamera(); stopBackgroundThread(); super.onPause(); } // Конец onPause

    @Override
    protected void onDestroy() { Log.d(TAG, "onDestroy"); closeCamera(); recycleBitmaps(); super.onDestroy(); } // Конец onDestroy

    private void recycleBitmaps() { if (originalBitmap != null && !originalBitmap.isRecycled()) { originalBitmap.recycle(); Log.d(TAG, "Recycled originalBitmap."); } originalBitmap = null; recyclePencilBitmaps(); } // Конец recycleBitmaps

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) { super.onConfigurationChanged(newConfig); Log.d(TAG, "Configuration changed."); imageView.postDelayed(this::resetTransformationsAndFit, 100); } // Конец onConfigurationChanged

} // Конец класса MainActivity
