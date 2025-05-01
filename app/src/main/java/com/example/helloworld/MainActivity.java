package com.example.helloworld;

import android.Manifest;
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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;

public class MainActivity extends AppCompatActivity {

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

    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private String cameraId;
    private Size previewSize;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private final Semaphore cameraOpenCloseLock = new Semaphore(1);
    private boolean isSurfaceAvailable = false; // Флаг для отслеживания состояния поверхности

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

        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scaleFactor *= detector.getScaleFactor();
                scaleFactor = Math.max(0.1f, Math.min(scaleFactor, 5.0f));
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
                openCamera(); // Открываем камеру, когда поверхность готова
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                Log.d(TAG, "Surface changed: " + width + "x" + height);
                // Обновляем размер предпросмотра и пересоздаём сессию
                if (cameraDevice != null && isSurfaceAvailable) {
                    closeCameraPreviewSession(); // Закрываем старую сессию
                    previewSize = chooseOptimalPreviewSize(getPreviewSizes(), width, height);
                    createCameraPreviewSession(); // Создаём новую сессию
                }
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                Log.d(TAG, "Surface destroyed");
                isSurfaceAvailable = false;
                closeCamera(); // Закрываем камеру при уничтожении поверхности
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
            updateImageDisplay();
        });

        layerSelectButton.setOnClickListener(v -> {
            Toast.makeText(MainActivity.this, "Layer selection not implemented", Toast.LENGTH_SHORT).show();
        });

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
        });

        hideImageCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isImageVisible = !isChecked;
            updateImageDisplay();
        });

        saveParametersButton.setOnClickListener(v -> saveParameters());

        loadParametersButton.setOnClickListener(v -> loadParameters());

        switchCameraButton.setOnClickListener(v -> switchCamera());

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (isSurfaceAvailable) {
                    openCamera();
                }
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show();
                finish();
            }
        } else if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Разрешение получено
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
        if (!isSurfaceAvailable) {
            Log.d(TAG, "Surface not available, skipping openCamera");
            return;
        }

        startBackgroundThread();
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            if (cameraId == null) {
                cameraId = manager.getCameraIdList()[0]; // По умолчанию задняя камера
            }
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Size[] previewSizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(SurfaceHolder.class);
            previewSize = chooseOptimalPreviewSize(previewSizes, cameraSurfaceView.getWidth(), cameraSurfaceView.getHeight());

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            cameraOpenCloseLock.acquire();
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
                    cameraOpenCloseLock.release();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    cameraOpenCloseLock.release();
                    camera.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    cameraOpenCloseLock.release();
                    camera.close();
                    cameraDevice = null;
                    Toast.makeText(MainActivity.this, "Camera error: " + error, Toast.LENGTH_LONG).show();
                }
            }, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Cannot access camera", e);
            Toast.makeText(this, "Cannot access camera", Toast.LENGTH_LONG).show();
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while opening camera", e);
            cameraOpenCloseLock.release();
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
            return new Size[]{};
        }
    }

    private Size chooseOptimalPreviewSize(Size[] choices, int viewWidth, int viewHeight) {
        if (choices == null || choices.length == 0) {
            Log.e(TAG, "No preview sizes available");
            return new Size(1280, 720); // Запасной размер
        }

        double targetRatio = (double) viewWidth / viewHeight;
        Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;

        for (Size size : choices) {
            double ratio = (double) size.getWidth() / size.getHeight();
            if (Math.abs(ratio - targetRatio) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(ratio - targetRatio);
            }
        }

        if (optimalSize == null) {
            optimalSize = choices[0];
        }

        Log.d(TAG, "Chosen preview size: " + optimalSize.getWidth() + "x" + optimalSize.getHeight());
        return optimalSize;
    }

    private void createCameraPreviewSession() {
        if (!isSurfaceAvailable || cameraDevice == null) {
            Log.d(TAG, "Cannot create preview session: Surface not available or cameraDevice is null");
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

            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (cameraDevice == null || !isSurfaceAvailable) {
                        Log.d(TAG, "Camera device closed or surface not available during session configuration");
                        session.close();
                        return;
                    }
                    cameraCaptureSession = session;
                    try {
                        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Error setting up camera preview", e);
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
        try {
            cameraOpenCloseLock.acquire();
            closeCameraPreviewSession();
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Error closing camera", e);
        } finally {
            cameraOpenCloseLock.release();
        }
        stopBackgroundThread();
    }

    private void switchCamera() {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            closeCamera();
            String[] cameraIds = manager.getCameraIdList();
            int currentCameraIndex = Arrays.asList(cameraIds).indexOf(cameraId);
            int nextCameraIndex = (currentCameraIndex + 1) % cameraIds.length;
            cameraId = cameraIds[nextCameraIndex];
            if (isSurfaceAvailable) {
                openCamera();
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error switching camera", e);
            Toast.makeText(this, "Error switching camera", Toast.LENGTH_LONG).show();
        }
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
    }

    private void setImageAlpha(int progress) {
        float alpha = progress / 100.0f;
        imageView.setAlpha(alpha);
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

    private int getLayerIndex(int搞 grayValue) {
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
        if (isSurfaceAvailable) {
            openCamera();
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
