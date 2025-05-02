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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
    // private volatile boolean isCameraPendingOpen = false; // Удалено - упрощаем логику
    private volatile boolean isCameraOpen = false;
    private String[] cameraIds;
    private int currentCameraIndex = 0;

    // Image Manipulation
    private Bitmap originalBitmap;
    private Bitmap pencilBitmap;
    private Bitmap[] layerBitmaps;
    private boolean[] layerVisibility;
    private Matrix matrix = new Matrix();
    private Matrix savedMatrix = new Matrix();
    private float scaleFactor = 1.0f; // Используется для сохранения/загрузки, но не напрямую для жестов
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
    private float initialDistance = 1f; // Для масштабирования жестом

    // Pencil Mode Layers
    private static final String[] PENCIL_HARDNESS = { // Оставляем этот порядок для строк
            "9H", "8H", "7H", "6H", "5H", "4H", "3H", "2H", "H", "F",
            "HB", "B", "2B", "3B", "4B", "5B", "6B", "7B", "8B", "9B"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeUI();

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
                // Пытаемся открыть камеру СРАЗУ при создании поверхности, если есть разрешения
                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    openCamera();
                } else {
                    checkPermissionsAndSetupCamera(); // Запрашиваем, если их нет
                }
            }
            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                Log.d(TAG, "Surface changed: " + width + "x" + height);
                // Перезапускаем превью, если камера уже открыта и поверхность изменилась
                if (isCameraOpen && cameraDevice != null) {
                    startCameraPreview();
                }
            }
            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                Log.d(TAG, "Surface destroyed"); isSurfaceAvailable = false; closeCamera();
            }
        });

        setupUIListeners();
        checkPermissionsAndSetupCamera(); // Проверяем разрешения при старте

        layerVisibility = new boolean[20]; Arrays.fill(layerVisibility, true);
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
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) { setImageAlpha(p); }
            @Override public void onStartTrackingTouch(SeekBar s) {} @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        pickImageButton.setOnClickListener(v -> pickImage());
        pencilModeSwitch.setOnCheckedChangeListener((v, isChecked) -> {
            isPencilMode = isChecked; layerSelectButton.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (isPencilMode) processPencilEffect(); else recyclePencilBitmaps();
            updateImageDisplay();
        });
        layerSelectButton.setOnClickListener(v -> showLayerSelectionDialog());
        controlsVisibilityCheckbox.setOnCheckedChangeListener((v, c) -> updateControlsVisibility(c));
        hideImageCheckbox.setOnCheckedChangeListener((v, c) -> { isImageVisible = !c; updateImageDisplay(); });
        saveParametersButton.setOnClickListener(v -> saveParameters());
        loadParametersButton.setOnClickListener(v -> loadParameters());
        switchCameraButton.setOnClickListener(v -> switchCamera());
    }

    // --- Gesture Handling ---
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        // ScaleListener больше не будет напрямую менять матрицу,
        // только предоставлять фактор масштаба для TouchAndRotateListener
        @Override public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
             return originalBitmap != null && !originalBitmap.isRecycled() && touchMode == ZOOM; // Масштаб только в режиме ZOOM
         }
        @Override public boolean onScale(@NonNull ScaleGestureDetector detector) { return true; } // Просто возвращаем true
    }

    private class TouchAndRotateListener implements View.OnTouchListener {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (originalBitmap == null || originalBitmap.isRecycled()) return false;

            scaleGestureDetector.onTouchEvent(event); // Передаем событие

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    savedMatrix.set(matrix); startPoint.set(event.getX(), event.getY());
                    touchMode = DRAG;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (event.getPointerCount() >= 2) {
                        initialDistance = distance(event);
                        if (initialDistance > 10f) { // Игнорируем слишком близкие точки
                            savedMatrix.set(matrix); midPoint(midPoint, event);
                            initialAngle = rotation(event); touchMode = ZOOM;
                        }
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (touchMode == DRAG && event.getPointerCount() == 1) {
                        matrix.set(savedMatrix); matrix.postTranslate(event.getX() - startPoint.x, event.getY() - startPoint.y);
                        applyTransformations();
                    } else if (touchMode == ZOOM && event.getPointerCount() >= 2) {
                        float newDist = distance(event);
                        float newAngle = rotation(event);
                        if (newDist > 10f) {
                            matrix.set(savedMatrix); // Начинаем с сохраненной матрицы
                            float scale = newDist / initialDistance; // Рассчитываем масштаб
                            matrix.postScale(scale, scale, midPoint.x, midPoint.y); // Применяем масштаб
                            float deltaAngle = newAngle - initialAngle; // Рассчитываем вращение
                            matrix.postRotate(deltaAngle, midPoint.x, midPoint.y); // Применяем вращение
                            applyTransformations();
                        }
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    // Когда палец убирается, сохраняем текущее состояние
                    // и переключаемся в режим DRAG, если остался один палец
                    savedMatrix.set(matrix); // Важно сохранить после жеста
                    if (event.getPointerCount() <= 2) {
                        touchMode = NONE;
                        int remainingPointerIndex = (event.getActionIndex() == 0) ? 1 : 0;
                        if(event.getPointerCount() > remainingPointerIndex) {
                             startPoint.set(event.getX(remainingPointerIndex), event.getY(remainingPointerIndex));
                             touchMode = DRAG;
                        }
                    }
                    // Если пальцев > 2, остаемся в режиме ZOOM, но savedMatrix обновлена
                    break;
                case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                    touchMode = NONE;
                    break;
            }
            return true;
        }
        private void midPoint(PointF point, MotionEvent event) { float x = event.getX(0) + event.getX(1); float y = event.getY(0) + event.getY(1); point.set(x / 2f, y / 2f); }
        private float rotation(MotionEvent event) { double dx = event.getX(0) - event.getX(1); double dy = event.getY(0) - event.getY(1); return (float) Math.toDegrees(Math.atan2(dy, dx)); }
        private float distance(MotionEvent event) { float dx = event.getX(0) - event.getX(1); float dy = event.getY(0) - event.getY(1); return (float)Math.sqrt(dx * dx + dy * dy); }
    }

    // --- UI Updates ---
     private void updateControlsVisibility(boolean show) {
        int visibility = show ? View.VISIBLE : View.GONE;
        transparencySeekBar.setVisibility(visibility); pickImageButton.setVisibility(visibility);
        pencilModeSwitch.setVisibility(visibility); layerSelectButton.setVisibility(show && isPencilMode ? View.VISIBLE : View.GONE);
        hideImageCheckbox.setVisibility(visibility); saveParametersButton.setVisibility(visibility);
        loadParametersButton.setVisibility(visibility); switchCameraButton.setVisibility(visibility);
     }

    // --- Permission Handling ---
    private void checkPermissionsAndSetupCamera() {
         String[] permissions = { Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE };
         List<String> needed = new ArrayList<>();
         for (String p : permissions) if (ActivityCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) needed.add(p);
         if (!needed.isEmpty()) ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQUEST_CAMERA_PERMISSION);
         else setupCamera();
     }

     @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        boolean allGranted = results.length > 0;
        for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) allGranted = false;
        if (allGranted) {
             setupCamera();
             // Пытаемся открыть камеру СРАЗУ после получения разрешений, если поверхность готова
             if (isSurfaceAvailable && !isCameraOpen) {
                 openCamera();
             }
        } else Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show();
    }

     // --- Camera Setup & Control ---
     private void setupCamera() {
         CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
         try {
             cameraIds = manager.getCameraIdList();
             if (cameraIds.length > 0) {
                 String foundRear = null;
                 for (String id : cameraIds) { Integer f = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING); if (f != null && f == CameraCharacteristics.LENS_FACING_BACK) { foundRear = id; break; } }
                 cameraId = (foundRear != null) ? foundRear : cameraIds[0]; currentCameraIndex = Arrays.asList(cameraIds).indexOf(cameraId);
             } else { cameraId = null; }
         } catch (Exception e) { Log.e(TAG, "Camera setup error", e); }
         if (cameraId == null) Toast.makeText(this, "No cameras", Toast.LENGTH_LONG).show();
     }

    private void startBackgroundThread() { if (backgroundThread == null || !backgroundThread.isAlive()) { stopBackgroundThread(); backgroundThread = new HandlerThread("CameraBg"); backgroundThread.start(); backgroundHandler = new Handler(backgroundThread.getLooper()); } }
    private void stopBackgroundThread() { if (backgroundThread != null) { backgroundThread.quitSafely(); try { backgroundThread.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } backgroundThread = null; backgroundHandler = null; } }

    private void openCamera() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { return; } // Разрешения проверяются до вызова
        if (cameraId == null || !isSurfaceAvailable || isCameraOpen) { return; } // Не открываем, если не готовы или уже открыта
        startBackgroundThread(); if (backgroundHandler == null) return;
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
             CameraCharacteristics ch = manager.getCameraCharacteristics(cameraId);
             previewSize = chooseOptimalPreviewSize(ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(SurfaceHolder.class), cameraSurfaceView.getWidth(), cameraSurfaceView.getHeight());
             synchronized (cameraOpenCloseLock) {
                 if (!isCameraOpen) { // Доп. проверка внутри лока
                     manager.openCamera(cameraId, cameraStateCallback, backgroundHandler);
                     isCameraOpen = true; // Устанавливаем флаг *до* выхода из лока
                 }
             }
        } catch (Exception e) { isCameraOpen = false; runOnUiThread(() -> Toast.makeText(this, "Failed to open camera", Toast.LENGTH_SHORT).show()); Log.e(TAG, "Open camera error", e); }
    }

     private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
         @Override public void onOpened(@NonNull CameraDevice camera) { synchronized (cameraOpenCloseLock) { cameraDevice = camera; isCameraOpen = true; /* isCameraPendingOpen = false; Удалено */ } startCameraPreview(); }
         @Override public void onDisconnected(@NonNull CameraDevice camera) { closeCamera(); }
         @Override public void onError(@NonNull CameraDevice camera, int error) { runOnUiThread(() -> Toast.makeText(MainActivity.this, "Cam Error: " + error, Toast.LENGTH_LONG).show()); closeCamera(); }
     };

    private Size[] getPreviewSizes() { if (cameraId == null) return new Size[]{new Size(1280, 720)}; try { return ((CameraManager) getSystemService(CAMERA_SERVICE)).getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(SurfaceHolder.class); } catch (Exception e) { return new Size[]{new Size(1280, 720)}; } }
    private Size chooseOptimalPreviewSize(Size[] choices, int viewWidth, int viewHeight) { if (choices == null || choices.length == 0) return new Size(1280, 720); double targetRatio = (viewWidth > 0 && viewHeight > 0) ? (double) viewWidth / viewHeight : (16.0 / 9.0); Size optimal = null; double minDiff = Double.MAX_VALUE; final int MAX_AREA = 1920 * 1080; List<Size> suitable = new ArrayList<>(); for (Size s : choices) if ((long)s.getWidth() * s.getHeight() <= MAX_AREA) suitable.add(s); if(suitable.isEmpty()) return Collections.min(Arrays.asList(choices), Comparator.comparingLong(s -> (long)s.getWidth() * s.getHeight())); for (Size s : suitable) { double r = (double) s.getWidth() / s.getHeight(); double d = Math.abs(r - targetRatio); if (d < minDiff) { minDiff = d; optimal = s; } else if (d == minDiff && optimal != null && (long)s.getWidth() * s.getHeight() > (long)optimal.getWidth() * optimal.getHeight()) optimal = s; } if (optimal == null) optimal = Collections.max(suitable, Comparator.comparingLong(s -> (long)s.getWidth() * s.getHeight())); return optimal; }

    private void startCameraPreview() {
         synchronized (cameraOpenCloseLock) {
             if (cameraDevice == null || !isSurfaceAvailable || !isCameraOpen || backgroundHandler == null) return;
             try {
                 closeCameraPreviewSession(); if (previewSize == null) previewSize = chooseOptimalPreviewSize(getPreviewSizes(), cameraSurfaceView.getWidth(), cameraSurfaceView.getHeight());
                 Surface surface = cameraSurfaceView.getHolder().getSurface(); if (!surface.isValid()) return;
                 final Size finalSize = previewSize; runOnUiThread(() -> { if(cameraSurfaceView.getHolder().getSurface().isValid()) cameraSurfaceView.getHolder().setFixedSize(finalSize.getWidth(), finalSize.getHeight()); });
                 previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW); previewRequestBuilder.addTarget(surface);
                 cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                     @Override public void onConfigured(@NonNull CameraCaptureSession session) { synchronized (cameraOpenCloseLock) { if (cameraDevice == null || !isCameraOpen) { session.close(); return; } cameraCaptureSession = session; try { previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE); cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler); } catch (Exception e) { Log.e(TAG, "Preview repeat err", e); } } }
                     @Override public void onConfigureFailed(@NonNull CameraCaptureSession session) { runOnUiThread(() -> Toast.makeText(MainActivity.this, "Preview cfg fail", Toast.LENGTH_LONG).show()); }
                 }, backgroundHandler);
             } catch (Exception e) { runOnUiThread(() -> Toast.makeText(MainActivity.this, "Preview start err", Toast.LENGTH_SHORT).show()); }
         }
     }

    private void closeCameraPreviewSession() { synchronized (cameraOpenCloseLock) { if (cameraCaptureSession != null) { try { cameraCaptureSession.close(); } catch (Exception e){} finally { cameraCaptureSession = null; } } } }
    private void closeCamera() { try { synchronized(cameraOpenCloseLock) { closeCameraPreviewSession(); if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; } isCameraOpen = false; /* isCameraPendingOpen = false; Удалено */ } } catch(Exception e) { Log.e(TAG, "Cam close err", e); } finally { stopBackgroundThread(); } }
    private void switchCamera() { if (cameraIds == null || cameraIds.length < 2) { Toast.makeText(this, "1 cam", Toast.LENGTH_SHORT).show(); return; } closeCamera(); currentCameraIndex = (currentCameraIndex + 1) % cameraIds.length; cameraId = cameraIds[currentCameraIndex]; previewSize = null; openCamera(); }

    // --- Image Picking ---
    private void pickImage() { Intent i = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI); checkPermissionsAndSetupCamera(); try { startActivityForResult(i, PICK_IMAGE_REQUEST); } catch (Exception e) { Toast.makeText(this, "No gallery", Toast.LENGTH_SHORT).show(); } }
    @Override protected void onActivityResult(int req, int res, Intent data) { super.onActivityResult(req, res, data); if (req == PICK_IMAGE_REQUEST && res == RESULT_OK && data != null) { Uri uri = data.getData(); if (uri != null) loadImageFromUri(uri); else Toast.makeText(this, "No URI", Toast.LENGTH_SHORT).show(); } }
     private void loadImageFromUri(Uri uri) {
         try {
             new Thread(() -> {
                 Bitmap bmp = null;
                 try (InputStream is = getContentResolver().openInputStream(uri)) { BitmapFactory.Options opts = new BitmapFactory.Options(); opts.inSampleSize = 2; bmp = BitmapFactory.decodeStream(is, null, opts); }
                 catch (Exception e) { runOnUiThread(()-> Toast.makeText(this, "Load err", Toast.LENGTH_SHORT).show()); }
                 final Bitmap finalBmp = bmp;
                 runOnUiThread(() -> { if (finalBmp != null) { recycleBitmaps(); originalBitmap = finalBmp; if (isPencilMode) { isPencilMode = false; pencilModeSwitch.setChecked(false); layerSelectButton.setVisibility(View.GONE); } layerVisibility = new boolean[20]; Arrays.fill(layerVisibility, true); resetTransformationsAndFit(); updateImageDisplay(); } else Toast.makeText(this, "Load fail", Toast.LENGTH_SHORT).show(); });
             }).start();
         } catch (Exception e) { Log.e(TAG, "Load thread start err", e); }
     }

    // --- Image Transformation & Display ---
    private void resetTransformationsAndFit() { matrix.reset(); if (originalBitmap == null || originalBitmap.isRecycled() || imageView.getWidth() == 0 || imageView.getHeight() == 0) { scaleFactor = 1.0f; if (imageView != null) runOnUiThread(()-> imageView.setImageMatrix(matrix)); return; } final float vw = imageView.getWidth(), vh = imageView.getHeight(), bw = originalBitmap.getWidth(), bh = originalBitmap.getHeight(); float scale = Math.min(vw / bw, vh / bh); float dx = (vw - bw * scale) / 2f, dy = (vh - bh * scale) / 2f; matrix.setScale(scale, scale); matrix.postTranslate(dx, dy); // scaleFactor обновляется при сохранении или загрузке матрицы; applyTransformations(); }
    private void applyTransformations() { if (imageView != null) { runOnUiThread(() -> imageView.setImageMatrix(matrix)); } }
    private void setImageAlpha(int p) { float a = Math.max(0.0f, Math.min(1.0f, p / 100.0f)); if (imageView != null) { runOnUiThread(() -> imageView.setAlpha(a)); } }

    // --- Pencil Effect Logic ---
     private void processPencilEffect() {
         if (originalBitmap == null || originalBitmap.isRecycled()) { return; }
          new Thread(() -> {
              recyclePencilBitmaps(); Bitmap gray = null; Bitmap[] layers = new Bitmap[PENCIL_HARDNESS.length]; boolean ok = false;
              try {
                  int w = originalBitmap.getWidth(), h = originalBitmap.getHeight(); gray = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                  Canvas cg = new Canvas(gray); Paint pg = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG); ColorMatrix cm = new ColorMatrix(); cm.setSaturation(0); pg.setColorFilter(new ColorMatrixColorFilter(cm)); cg.drawBitmap(originalBitmap, 0, 0, pg);
                  int[] pix = new int[w * h]; gray.getPixels(pix, 0, w, 0, 0, w, h); int[][] lpix = new int[layers.length][w * h]; for (int i = 0; i < layers.length; i++) Arrays.fill(lpix[i], Color.TRANSPARENT);
                  for (int i = 0; i < pix.length; i++) {
                      int layerIdx = getLayerIndex(Color.red(pix[i])); // Используем измененный getLayerIndex
                      if (layerIdx >= 0 && layerIdx < layers.length) lpix[layerIdx][i] = pix[i];
                  }
                  pix = null; gray.recycle(); gray = null;
                  for (int i = 0; i < layers.length; i++) { layers[i] = Bitmap.createBitmap(lpix[i], w, h, Bitmap.Config.ARGB_8888); lpix[i] = null; } lpix = null; ok = true;
              } catch (OutOfMemoryError e) { if (gray != null && !gray.isRecycled()) gray.recycle(); for(Bitmap b : layers) if(b!=null && !b.isRecycled()) b.recycle(); runOnUiThread(() -> Toast.makeText(this, "OOM Pencil", Toast.LENGTH_LONG).show()); }
              catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, "Pencil Err", Toast.LENGTH_SHORT).show()); }
              final boolean finalOk = ok; final Bitmap[] finalLayers = layers;
              runOnUiThread(() -> { if (finalOk) { pencilBitmap = null; layerBitmaps = finalLayers; } else { isPencilMode = false; pencilModeSwitch.setChecked(false); layerSelectButton.setVisibility(View.GONE); layerBitmaps = null; } updateImageDisplay(); });
          }).start();
      }

      // --- ИЗМЕНЕНО: Инвертируем порядок слоев ---
      private int getLayerIndex(int grayValue) {
        int numLayers = PENCIL_HARDNESS.length;
        if (numLayers <= 0) return -1;
        // Вычисляем "сырой" индекс от 0 (темный) до numLayers-1 (светлый)
        int rawIndex = (int) (((float)grayValue / 256.0f) * numLayers);
        rawIndex = Math.max(0, Math.min(rawIndex, numLayers - 1)); // Ограничиваем
        // Инвертируем индекс: 0 становится numLayers-1, numLayers-1 становится 0
        int invertedIndex = (numLayers - 1) - rawIndex;
        return invertedIndex; // Теперь индекс 0 соответствует самому светлому слою
    }

     private void recyclePencilBitmaps() { if (pencilBitmap != null && !pencilBitmap.isRecycled()) pencilBitmap.recycle(); pencilBitmap = null; if (layerBitmaps != null) { for (Bitmap b : layerBitmaps) if (b != null && !b.isRecycled()) b.recycle(); layerBitmaps = null; } }
     private void updateImageDisplay() {
         if (imageView == null) return; if (!isImageVisible || originalBitmap == null || originalBitmap.isRecycled()) { runOnUiThread(() -> { imageView.setImageBitmap(null); imageView.setVisibility(View.INVISIBLE); }); return; }
          new Thread(() -> {
              Bitmap bmp = null; boolean displayOrig = true;
              if (isPencilMode && layerBitmaps != null) {
                   try {
                       bmp = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888); Canvas c = new Canvas(bmp); c.drawColor(Color.TRANSPARENT); Paint p = new Paint(Paint.FILTER_BITMAP_FLAG); boolean drawn = false;
                       // Рисуем слои как и раньше, getLayerIndex уже инвертирован при создании
                       for (int i = 0; i < layerBitmaps.length; i++) if (layerVisibility[i] && layerBitmaps[i] != null && !layerBitmaps[i].isRecycled()) { c.drawBitmap(layerBitmaps[i], 0, 0, p); drawn = true; }
                       if (!drawn) { if (bmp != null && !bmp.isRecycled()) bmp.recycle(); bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); bmp.eraseColor(Color.TRANSPARENT); } displayOrig = false;
                   } catch (Exception e) { bmp = null; runOnUiThread(() -> Toast.makeText(this, "Layer Err", Toast.LENGTH_SHORT).show()); }
              }
              if (displayOrig) bmp = originalBitmap;
              final Bitmap finalBmp = bmp; final boolean finalOrig = displayOrig;
              runOnUiThread(() -> { if (imageView != null) { if (finalBmp != null && !finalBmp.isRecycled()) { imageView.setImageBitmap(finalBmp); imageView.setVisibility(View.VISIBLE); imageView.setImageMatrix(matrix); setImageAlpha(transparencySeekBar.getProgress()); imageView.invalidate(); } else { imageView.setImageBitmap(null); imageView.setVisibility(View.INVISIBLE); } } });
          }).start();
      }

    // --- Layer Selection Dialog ---
    private void showLayerSelectionDialog() { Dialog d = new Dialog(this); d.setContentView(R.layout.dialog_layer_selection); d.setTitle(R.string.layer_selection_title); RecyclerView rv = d.findViewById(R.id.layerRecyclerView); if (rv == null) return; rv.setLayoutManager(new LinearLayoutManager(this)); LayerAdapter a = new LayerAdapter(PENCIL_HARDNESS, layerVisibility, this); rv.setAdapter(a); d.show(); }
    @Override public void onLayerVisibilityChanged(int p, boolean isVisible) { if (p >= 0 && p < layerVisibility.length) { layerVisibility[p] = isVisible; updateImageDisplay(); } }

    // --- Save/Load Parameters ---
    private void saveParameters() { try { File f = new File(getFilesDir(), "parameters.dat"); try (FileOutputStream fos = new FileOutputStream(f)) { /* ... остальной код сохранения ... */ float[] mv = new float[9]; matrix.getValues(mv); StringBuilder ms = new StringBuilder("matrix="); for (int i = 0; i < 9; i++) { ms.append(mv[i]); if (i < 8) ms.append(","); } fos.write((ms.toString() + "\n").getBytes()); /* ... остальной код сохранения ... */ Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show(); } } catch (IOException e) { Toast.makeText(this, "Save Err", Toast.LENGTH_LONG).show(); } }
    private void loadParameters() { try { File f = new File(getFilesDir(), "parameters.dat"); if (!f.exists()) { Toast.makeText(this, "No params", Toast.LENGTH_SHORT).show(); return; } try (FileInputStream fis = new FileInputStream(f); java.util.Scanner sc = new java.util.Scanner(fis)) { boolean loadedPencil = isPencilMode; while (sc.hasNextLine()) { String l = sc.nextLine(); String[] p = l.split("=", 2); if (p.length != 2) continue; String k = p[0], v = p[1]; try { switch (k) { /* ... остальной код загрузки ... */ case "matrix": String[] mvs = v.split(","); if (mvs.length == 9) { float[] vals = new float[9]; for (int i = 0; i < 9; i++) vals[i] = Float.parseFloat(mvs[i]); matrix.setValues(vals); /* scaleFactor извлекать не обязательно, матрица сама его содержит */ } break; /* ... остальной код загрузки ... */ } } catch (Exception e) { Log.w(TAG, "Parse err", e); } } pencilModeSwitch.setChecked(isPencilMode); hideImageCheckbox.setChecked(!isImageVisible); updateControlsVisibility(controlsVisibilityCheckbox.isChecked()); applyTransformations(); if (isPencilMode && originalBitmap != null) { if (!loadedPencil) processPencilEffect(); else updateImageDisplay(); } else updateImageDisplay(); Toast.makeText(this, "Loaded", Toast.LENGTH_SHORT).show(); } } catch (Exception e) { Toast.makeText(this, "Load Err", Toast.LENGTH_LONG).show(); } }

    // --- Activity Lifecycle ---
    @Override
    protected void onResume() {
        super.onResume(); Log.d(TAG, "onResume"); startBackgroundThread();
        // Пытаемся открыть камеру, если поверхность готова и есть разрешения
        if (isSurfaceAvailable && ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
             if (!isCameraOpen) {
                 openCamera();
             } else {
                 // Камера уже открыта, возможно, нужно перезапустить превью
                 startCameraPreview();
             }
        }
        // Если поверхность не готова, камера откроется в surfaceCreated
    }
    @Override protected void onPause() { closeCamera(); stopBackgroundThread(); super.onPause(); }
    @Override protected void onDestroy() { closeCamera(); recycleBitmaps(); super.onDestroy(); }
    private void recycleBitmaps() { if (originalBitmap != null && !originalBitmap.isRecycled()) originalBitmap.recycle(); originalBitmap = null; recyclePencilBitmaps(); }
    @Override public void onConfigurationChanged(@NonNull Configuration newConfig) { super.onConfigurationChanged(newConfig); imageView.postDelayed(this::resetTransformationsAndFit, 100); }

} // End MainActivity
