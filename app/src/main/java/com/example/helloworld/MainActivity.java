package com.example.helloworld;

import android.Manifest;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
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
import android.hardware.camera2.params.StreamConfigurationMap;
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

public class MainActivity extends AppCompatActivity implements LayerAdapter.OnLayerVisibilityChangedListener, SurfaceHolder.Callback {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 100;
    private static final int REQUEST_STORAGE_PERMISSION = 101;
    private static final int PICK_IMAGE_REQUEST = 1;

    // --- UI Elements ---
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

    // --- Camera ---
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private String cameraId;
    private Size previewSize;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private final Object cameraOpenCloseLock = new Object();
    private volatile boolean isSurfaceAvailable = false;
    private volatile boolean isCameraOpen = false;
    private String[] cameraIds;
    private int currentCameraIndex = 0;

    // --- Image Manipulation ---
    private Bitmap originalBitmap;
    private Bitmap pencilBitmap;
    private Bitmap[] layerBitmaps;
    private boolean[] layerVisibility;
    private Matrix matrix = new Matrix();
    private Matrix savedMatrix = new Matrix();
    private boolean isPencilMode = false;
    private boolean isImageVisible = true;

    // --- Gesture Detection ---
    private ScaleGestureDetector scaleGestureDetector;
    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2;
    private int touchMode = NONE;
    private final PointF startPoint = new PointF();
    private final PointF midPoint = new PointF();
    private float initialAngle = 0f;

    // --- Pencil Mode Layers ---
    private static final String[] PENCIL_HARDNESS = {
        "9H", "8H", "7H", "6H", "5H", "4H", "3H", "2H", "H", "F",
        "HB", "B", "2B", "3B", "4B", "5B", "6B", "7B", "8B", "9B"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initializeUI();
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());
        imageView.setOnTouchListener(new TouchAndRotateListener());
        imageView.setScaleType(ImageView.ScaleType.MATRIX);
        cameraSurfaceView.getHolder().addCallback(this);
        setupUIListeners();
        checkPermissionsAndSetupCamera();
        layerVisibility = new boolean[PENCIL_HARDNESS.length];
        Arrays.fill(layerVisibility, true);
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
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean u) { setImageAlpha(p); }
            @Override
            public void onStartTrackingTouch(SeekBar s) {}
            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });
        pickImageButton.setOnClickListener(v -> pickImage());
        pencilModeSwitch.setOnCheckedChangeListener((v, isChecked) -> {
            isPencilMode = isChecked;
            layerSelectButton.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (isPencilMode) processPencilEffect();
            else recyclePencilBitmaps();
            updateImageDisplay();
        });
        layerSelectButton.setOnClickListener(v -> showLayerSelectionDialog());
        controlsVisibilityCheckbox.setOnCheckedChangeListener((v, c) -> updateControlsVisibility(c));
        hideImageCheckbox.setOnCheckedChangeListener((v, c) -> {
            isImageVisible = !c;
            updateImageDisplay();
        });
        saveParametersButton.setOnClickListener(v -> saveParameters());
        loadParametersButton.setOnClickListener(v -> loadParameters());
        switchCameraButton.setOnClickListener(v -> switchCamera());
    }

    // --- SurfaceHolder.Callback implementation ---
    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        Log.d(TAG, "Surface created");
        isSurfaceAvailable = true;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        } else {
            checkPermissionsAndSetupCamera();
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "Surface changed: " + width + "x" + height);
        if (isCameraOpen && cameraDevice != null) {
            previewSize = chooseOptimalPreviewSize(getPreviewSizes(), width, height);
            startCameraPreview();
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        Log.d(TAG, "Surface destroyed");
        isSurfaceAvailable = false;
        closeCamera();
    }

    // --- Camera setup ---
    private void setupCamera() {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            cameraIds = manager.getCameraIdList();
            if (cameraIds.length > 0) {
                String rearCameraId = null;
                for (String id : cameraIds) {
                    CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        rearCameraId = id;
                        break;
                    }
                }
                cameraId = rearCameraId != null ? rearCameraId : cameraIds[0];
                currentCameraIndex = Arrays.asList(cameraIds).indexOf(cameraId);
            } else {
                cameraId = null;
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Camera setup error", e);
        }
        if (cameraId == null) {
            Toast.makeText(this, "No cameras found", Toast.LENGTH_LONG).show();
        }
    }

    private void openCamera() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (cameraId == null || !isSurfaceAvailable || isCameraOpen) {
            return;
        }
        startBackgroundThread();
        if (backgroundHandler == null) {
            return;
        }
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            previewSize = chooseOptimalPreviewSize(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(SurfaceHolder.class), cameraSurfaceView.getWidth(), cameraSurfaceView.getHeight());
            synchronized (cameraOpenCloseLock) {
                if (!isCameraOpen) {
                    manager.openCamera(cameraId, cameraStateCallback, backgroundHandler);
                    isCameraOpen = true;
                }
            }
        } catch (Exception e) {
            isCameraOpen = false;
            runOnUiThread(() -> Toast.makeText(this, "Failed to open camera", Toast.LENGTH_SHORT).show());
            Log.e(TAG, "Error opening camera", e);
        }
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            synchronized (cameraOpenCloseLock) {
                cameraDevice = camera;
                isCameraOpen = true;
            }
            startCameraPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            closeCamera();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Camera error: " + error, Toast.LENGTH_LONG).show());
            closeCamera();
        }
    };

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
                    if (previewSize == null) {
                        Log.e(TAG, "Failed to choose preview size!");
                        return;
                    }
                }

                Surface surface = cameraSurfaceView.getHolder().getSurface();
                if (!surface.isValid()) {
                    Log.e(TAG, "Preview surface is invalid!");
                    return;
                }

                runOnUiThread(() -> {
                    SurfaceHolder holder = cameraSurfaceView.getHolder();
                    if (holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
                        try {
                            holder.setFixedSize(previewSize.getWidth(), previewSize.getHeight());
                            Log.d(TAG, "Set SurfaceHolder fixed size for preview: " + previewSize);
                        } catch (Exception e) {
                            Log.e(TAG, "Error setting fixed size for SurfaceHolder", e);
                        }
                    }
                });

                previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                previewRequestBuilder.addTarget(surface);
                cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(@NonNull CameraCaptureSession session) {
                        synchronized (cameraOpenCloseLock) {
                            if (cameraDevice == null || !isCameraOpen) {
                                session.close();
                                return;
                            }
                            cameraCaptureSession = session;
                            try {
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
                            } catch (Exception e) {
                                Log.e(TAG, "Error setting repeating request", e);
                            }
                        }
                    }

                    @Override
                    public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "Preview configuration failed", Toast.LENGTH_LONG).show());
                    }
                }, backgroundHandler);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error starting preview", Toast.LENGTH_SHORT).show());
                Log.e(TAG, "Preview start exception", e);
            }
        }
    }

    private void closeCameraPreviewSession() {
        synchronized (cameraOpenCloseLock) {
            if (cameraCaptureSession != null) {
                try {
                    cameraCaptureSession.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error closing capture session", e);
                } finally {
                    cameraCaptureSession = null;
                }
            }
        }
    }

    private void closeCamera() {
        try {
            synchronized (cameraOpenCloseLock) {
                closeCameraPreviewSession();
                if (cameraDevice != null) {
                    cameraDevice.close();
                    cameraDevice = null;
                }
                isCameraOpen = false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error closing camera", e);
        } finally {
            stopBackgroundThread();
        }
    }

    private Size[] getPreviewSizes() {
        if (cameraId == null) {
            return new Size[]{new Size(1280, 720)};
        }
        try {
            CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            return map != null ? map.getOutputSizes(SurfaceHolder.class) : new Size[]{new Size(1280, 720)};
        } catch (Exception e) {
            Log.e(TAG, "Error getting preview sizes", e);
            return new Size[]{new Size(1280, 720)};
        }
    }

    private Size chooseOptimalPreviewSize(Size[] choices, int width, int height) {
        if (choices == null || choices.length == 0) {
            return new Size(1280, 720);
        }
        double targetRatio = (width > 0 && height > 0) ? (double) width / height : (16.0 / 9.0);
        Size optimal = null;
        double minDiff = Double.MAX_VALUE;
        long maxArea = 1920 * 1080;
        List<Size> suitable = new ArrayList<>();
        for (Size s : choices) {
            if ((long) s.getWidth() * s.getHeight() <= maxArea) {
                suitable.add(s);
            }
        }
        if (suitable.isEmpty()) {
            return Collections.min(Arrays.asList(choices), Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight()));
        }
        for (Size s : suitable) {
            double ratio = (double) s.getWidth() / s.getHeight();
            double diff = Math.abs(ratio - targetRatio);
            if (diff < minDiff) {
                minDiff = diff;
                optimal = s;
            } else if (diff == minDiff && optimal != null && (long) s.getWidth() * s.getHeight() > (long) optimal.getWidth() * optimal.getHeight()) {
                optimal = s;
            }
        }
        if (optimal == null) {
            optimal = Collections.max(suitable, Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight()));
        }
        Log.d(TAG, "Chosen preview size: " + optimal);
        return optimal;
    }

    // --- Permissions ---
    private void checkPermissionsAndSetupCamera() {
        String[] permissions = {Manifest.permission.CAMERA, Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE};
        List<String> neededPermissions = new ArrayList<>();
        for (String permission : permissions) {
            if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(permission);
            }
        }
        if (!neededPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toArray(new String[0]), REQUEST_CAMERA_PERMISSION);
        } else {
            setupCamera();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        boolean allGranted = results.length > 0;
        for (int result : results) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (allGranted) {
            setupCamera();
            if (isSurfaceAvailable && !isCameraOpen) {
                openCamera();
            }
        } else {
            Toast.makeText(this, "Permissions required", Toast.LENGTH_LONG).show();
        }
    }

    // --- Image Picking ---
    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        checkPermissionsAndSetupCamera();
        try {
            startActivityForResult(intent, PICK_IMAGE_REQUEST);
        } catch (Exception e) {
            Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                loadImageFromUri(uri);
            } else {
                Toast.makeText(this, "No image URI", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void loadImageFromUri(Uri uri) {
        try {
            new Thread(() -> {
                Bitmap bitmap = null;
                try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 2;
                    bitmap = BitmapFactory.decodeStream(inputStream, null, options);
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this, "Error loading image", Toast.LENGTH_SHORT).show());
                }
                final Bitmap finalBitmap = bitmap;
                runOnUiThread(() -> {
                    if (finalBitmap != null) {
                        recycleBitmaps();
                        originalBitmap = finalBitmap;
                        if (isPencilMode) {
                            isPencilMode = false;
                            pencilModeSwitch.setChecked(false);
                            layerSelectButton.setVisibility(View.GONE);
                        }
                        layerVisibility = new boolean[PENCIL_HARDNESS.length];
                        Arrays.fill(layerVisibility, true);
                        resetTransformationsAndFit();
                        updateImageDisplay();
                    } else {
                        Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show();
                    }
                });
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "Error starting image load thread", e);
        }
    }

    // --- Image Display and Transformation ---
    private void updateImageDisplay() {
        if (imageView == null) return;
        if (!isImageVisible || originalBitmap == null || originalBitmap.isRecycled()) {
            runOnUiThread(() -> {
                imageView.setImageBitmap(null);
                imageView.setVisibility(View.INVISIBLE);
            });
            return;
        }
        new Thread(() -> {
            Bitmap bitmap = null;
            boolean displayOriginal = true;
            if (isPencilMode && layerBitmaps != null) {
                try {
                    bitmap = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bitmap);
                    canvas.drawColor(Color.TRANSPARENT);
                    Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
                    boolean drawn = false;
                    for (int i = 0; i < layerBitmaps.length; i++) {
                        if (layerVisibility[i] && layerBitmaps[i] != null && !layerBitmaps[i].isRecycled()) {
                            canvas.drawBitmap(layerBitmaps[i], 0, 0, paint);
                            drawn = true;
                        }
                    }
                    if (!drawn) {
                        bitmap.recycle();
                        bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
                        bitmap.eraseColor(Color.TRANSPARENT);
                    }
                    displayOriginal = false;
                } catch (Exception e) {
                    bitmap = null;
                    runOnUiThread(() -> Toast.makeText(this, "Layer error", Toast.LENGTH_SHORT).show());
                }
            }
            if (displayOriginal) {
                bitmap = originalBitmap;
            }
            final Bitmap finalBitmap = bitmap;
            runOnUiThread(() -> {
                if (imageView != null) {
                    if (finalBitmap != null && !finalBitmap.isRecycled()) {
                        imageView.setImageBitmap(finalBitmap);
                        imageView.setVisibility(View.VISIBLE);
                        imageView.setImageMatrix(matrix);
                        setImageAlpha(transparencySeekBar.getProgress());
                        imageView.invalidate();
                    } else {
                        imageView.setImageBitmap(null);
                        imageView.setVisibility(View.INVISIBLE);
                    }
                }
            });
        }).start();
    }

    private void resetTransformationsAndFit() {
        matrix.reset();
        if (originalBitmap == null || originalBitmap.isRecycled() || imageView.getWidth() == 0 || imageView.getHeight() == 0) {
            if (imageView != null) {
                runOnUiThread(() -> imageView.setImageMatrix(matrix));
            }
            return;
        }
        float viewWidth = imageView.getWidth();
        float viewHeight = imageView.getHeight();
        float bitmapWidth = originalBitmap.getWidth();
        float bitmapHeight = originalBitmap.getHeight();
        float scale = Math.min(viewWidth / bitmapWidth, viewHeight / bitmapHeight);
        float dx = (viewWidth - bitmapWidth * scale) / 2f;
        float dy = (viewHeight - bitmapHeight * scale) / 2f;
        matrix.setScale(scale, scale);
        matrix.postTranslate(dx, dy);
        Log.d(TAG, "Reset & fit. Scale: " + scale + ", Offset: (" + dx + ", " + dy + ")");
        applyTransformations();
    }

    // --- Pencil Effect ---
    private void processPencilEffect() {
        if (originalBitmap == null || originalBitmap.isRecycled()) {
            return;
        }
        new Thread(() -> {
            recyclePencilBitmaps();
            Bitmap grayBitmap = null;
            Bitmap[] layers = new Bitmap[PENCIL_HARDNESS.length];
            boolean success = false;
            try {
                int width = originalBitmap.getWidth();
                int height = originalBitmap.getHeight();
                grayBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(grayBitmap);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                ColorMatrix colorMatrix = new ColorMatrix();
                colorMatrix.setSaturation(0);
                paint.setColorFilter(new ColorMatrixColorFilter(colorMatrix));
                canvas.drawBitmap(originalBitmap, 0, 0, paint);
                int[] pixels = new int[width * height];
                grayBitmap.getPixels(pixels, 0, width, 0, 0, width, height);
                int[][] layerPixels = new int[layers.length][width * height];
                for (int i = 0; i < layers.length; i++) {
                    Arrays.fill(layerPixels[i], Color.TRANSPARENT);
                }
                for (int i = 0; i < pixels.length; i++) {
                    int layerIndex = getLayerIndex(Color.red(pixels[i]));
                    if (layerIndex >= 0 && layerIndex < layers.length) {
                        layerPixels[layerIndex][i] = pixels[i];
                    }
                }
                pixels = null;
                grayBitmap.recycle();
                grayBitmap = null;
                for (int i = 0; i < layers.length; i++) {
                    layers[i] = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    layers[i].setPixels(layerPixels[i], 0, width, 0, 0, width, height);
                    layerPixels[i] = null;
                }
                layerPixels = null;
                success = true;
            } catch (OutOfMemoryError e) {
                if (grayBitmap != null && !grayBitmap.isRecycled()) {
                    grayBitmap.recycle();
                }
                for (Bitmap b : layers) {
                    if (b != null && !b.isRecycled()) {
                        b.recycle();
                    }
                }
                runOnUiThread(() -> Toast.makeText(this, "Out of memory during pencil effect", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Error processing pencil effect", Toast.LENGTH_SHORT).show());
            }
            final boolean finalSuccess = success;
            final Bitmap[] finalLayers = layers;
            runOnUiThread(() -> {
                if (finalSuccess) {
                    pencilBitmap = null;
                    layerBitmaps = finalLayers;
                } else {
                    isPencilMode = false;
                    pencilModeSwitch.setChecked(false);
                    layerSelectButton.setVisibility(View.GONE);
                    layerBitmaps = null;
                }
                updateImageDisplay();
            });
        }).start();
    }

    private int getLayerIndex(int grayValue) {
        int numLayers = PENCIL_HARDNESS.length;
        if (numLayers <= 0) {
            return -1;
        }
        int rawIndex = (int) (((float) grayValue / 256.0f) * numLayers);
        rawIndex = Math.max(0, Math.min(rawIndex, numLayers - 1));
        int invertedIndex = (numLayers - 1) - rawIndex;
        return invertedIndex;
    }

    private void recyclePencilBitmaps() {
        if (pencilBitmap != null && !pencilBitmap.isRecycled()) {
            pencilBitmap.recycle();
        }
        pencilBitmap = null;
        if (layerBitmaps != null) {
            for (Bitmap b : layerBitmaps) {
                if (b != null && !b.isRecycled()) {
                    b.recycle();
                }
            }
            layerBitmaps = null;
        }
    }

    // --- Layer Selection Dialog ---
    private void showLayerSelectionDialog() {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_layer_selection);
        dialog.setTitle(R.string.layer_selection_title);
        RecyclerView recyclerView = dialog.findViewById(R.id.recyclerView);
        if (recyclerView == null) {
            return;
        }
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        LayerAdapter adapter = new LayerAdapter(PENCIL_HARDNESS, layerVisibility, this);
        recyclerView.setAdapter(adapter);
        dialog.show();
    }

    @Override
    public void onLayerVisibilityChanged(int position, boolean isVisible) {
        if (position >= 0 && position < layerVisibility.length) {
            layerVisibility[position] = isVisible;
            updateImageDisplay();
        }
    }

    // --- Save and Load Parameters ---
    private float getMatrixScale(Matrix mat) {
        float[] values = new float[9];
        mat.getValues(values);
        float scaleX = values[Matrix.MSCALE_X];
        float skewY = values[Matrix.MSKEW_Y];
        return (float) Math.sqrt(scaleX * scaleX + skewY * skewY);
    }

    private void saveParameters() {
        try {
            File file = new File(getFilesDir(), "parameters.dat");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                float[] matrixValues = new float[9];
                matrix.getValues(matrixValues);
                fos.write(("matrix=" + matrixValues[0] + "," + matrixValues[1] + "," + matrixValues[2] + "," + matrixValues[3] + "," + matrixValues[4] + "," + matrixValues[5] + "," + matrixValues[6] + "," + matrixValues[7] + "," + matrixValues[8] + "\n").getBytes());
                fos.write(("isPencilMode=" + isPencilMode + "\n").getBytes());
                fos.write(("isImageVisible=" + isImageVisible + "\n").getBytes());
                fos.write(("controlsVisible=" + controlsVisibilityCheckbox.isChecked() + "\n").getBytes());
                fos.write(("transparency=" + transparencySeekBar.getProgress() + "\n").getBytes());
                StringBuilder layerVisibilityString = new StringBuilder("layerVisibility=");
                for (int i = 0; i < layerVisibility.length; i++) {
                    layerVisibilityString.append(layerVisibility[i]);
                    if (i < layerVisibility.length - 1) {
                        layerVisibilityString.append(",");
                    }
                }
                fos.write((layerVisibilityString.toString() + "\n").getBytes());
                Toast.makeText(this, "Parameters saved", Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            Toast.makeText(this, "Error saving parameters", Toast.LENGTH_LONG).show();
        }
    }

    private void loadParameters() {
        try {
            File file = new File(getFilesDir(), "parameters.dat");
            if (!file.exists()) {
                Toast.makeText(this, "No parameters file found", Toast.LENGTH_SHORT).show();
                return;
            }
            try (FileInputStream fis = new FileInputStream(file); java.util.Scanner scanner = new java.util.Scanner(fis)) {
                boolean loadedPencilMode = isPencilMode;
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    String[] parts = line.split("=", 2);
                    if (parts.length != 2) {
                        continue;
                    }
                    String key = parts[0];
                    String value = parts[1];
                    try {
                        switch (key) {
                            case "matrix":
                                String[] matrixValues = value.split(",");
                                if (matrixValues.length == 9) {
                                    float[] vals = new float[9];
                                    for (int i = 0; i < 9; i++) {
                                        vals[i] = Float.parseFloat(matrixValues[i]);
                                    }
                                    matrix.setValues(vals);
                                }
                                break;
                            case "isPencilMode":
                                isPencilMode = Boolean.parseBoolean(value);
                                break;
                            case "isImageVisible":
                                isImageVisible = Boolean.parseBoolean(value);
                                break;
                            case "controlsVisible":
                                controlsVisibilityCheckbox.setChecked(Boolean.parseBoolean(value));
                                break;
                            case "transparency":
                                transparencySeekBar.setProgress(Integer.parseInt(value));
                                break;
                            case "layerVisibility":
                                String[] visibilityValues = value.split(",");
                                for (int i = 0; i < layerVisibility.length && i < visibilityValues.length; i++) {
                                    layerVisibility[i] = Boolean.parseBoolean(visibilityValues[i]);
                                }
                                break;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Error parsing parameter", e);
                    }
                }
                pencilModeSwitch.setChecked(isPencilMode);
                hideImageCheckbox.setChecked(!isImageVisible);
                updateControlsVisibility(controlsVisibilityCheckbox.isChecked());
                applyTransformations();
                if (isPencilMode && originalBitmap != null) {
                    if (!loadedPencilMode) {
                        processPencilEffect();
                    } else {
                        updateImageDisplay();
                    }
                } else {
                    updateImageDisplay();
                }
                Toast.makeText(this, "Parameters loaded", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error loading parameters", Toast.LENGTH_LONG).show();
        }
    }

    // --- Lifecycle Methods ---
    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if (isSurfaceAvailable && ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (!isCameraOpen) {
                openCamera();
            } else {
                startCameraPreview();
            }
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        closeCamera();
        recycleBitmaps();
        super.onDestroy();
    }

    private void recycleBitmaps() {
        if (originalBitmap != null && !originalBitmap.isRecycled()) {
            originalBitmap.recycle();
        }
        originalBitmap = null;
        recyclePencilBitmaps();
    }

    // --- Background Thread ---
    private void startBackgroundThread() {
        if (backgroundThread == null || !backgroundThread.isAlive()) {
            stopBackgroundThread();
            backgroundThread = new HandlerThread("CameraBg");
            backgroundThread.start();
            backgroundHandler = new Handler(backgroundThread.getLooper());
        }
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    // --- Switch Camera ---
    private void switchCamera() {
        if (cameraIds == null || cameraIds.length < 2) {
            Toast.makeText(this, "Only one camera available", Toast.LENGTH_SHORT).show();
            return;
        }
        closeCamera();
        currentCameraIndex = (currentCameraIndex + 1) % cameraIds.length;
        cameraId = cameraIds[currentCameraIndex];
        previewSize = null;
        openCamera();
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
    }

    private void applyTransformations() {
        if (imageView != null) {
            runOnUiThread(() -> imageView.setImageMatrix(matrix));
        }
    }

    private void setImageAlpha(int progress) {
        float alpha = Math.max(0.0f, Math.min(1.0f, progress / 100.0f));
        if (imageView != null) {
            runOnUiThread(() -> imageView.setAlpha(alpha));
        }
    }

    // --- Gesture Handling ---
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
            return originalBitmap != null && !originalBitmap.isRecycled();
        }

        @Override
        public boolean onScale(@NonNull ScaleGestureDetector detector) {
            if (originalBitmap == null || originalBitmap.isRecycled() || touchMode != ZOOM) {
                return false;
            }
            float scale = detector.getScaleFactor();
            matrix.postScale(scale, scale, detector.getFocusX(), detector.getFocusY());
            applyTransformations();
            return true;
        }
    }

    private class TouchAndRotateListener implements View.OnTouchListener {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (originalBitmap == null || originalBitmap.isRecycled()) {
                return false;
            }
            scaleGestureDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    savedMatrix.set(matrix);
                    startPoint.set(event.getX(), event.getY());
                    touchMode = DRAG;
                    break;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (event.getPointerCount() >= 2) {
                        savedMatrix.set(matrix);
                        midPoint(midPoint, event);
                        initialAngle = rotation(event);
                        touchMode = ZOOM;
                    }
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (touchMode == DRAG && event.getPointerCount() == 1) {
                        matrix.set(savedMatrix);
                        matrix.postTranslate(event.getX() - startPoint.x, event.getY() - startPoint.y);
                        applyTransformations();
                    } else if (touchMode == ZOOM && event.getPointerCount() >= 2 && !scaleGestureDetector.isInProgress()) {
                        float currentAngle = rotation(event);
                        float deltaAngle = currentAngle - initialAngle;
                        if (Math.abs(deltaAngle) > 2.0f) {
                            matrix.postRotate(deltaAngle, midPoint.x, midPoint.y);
                            applyTransformations();
                            savedMatrix.set(matrix);
                            initialAngle = currentAngle;
                            midPoint(midPoint, event);
                        }
                    }
                    break;
                case MotionEvent.ACTION_POINTER_UP:
                    savedMatrix.set(matrix);
                    if (event.getPointerCount() <= 2) {
                        touchMode = NONE;
                        int remainingIndex = (event.getActionIndex() == 0) ? 1 : 0;
                        if (event.getPointerCount() > remainingIndex) {
                            startPoint.set(event.getX(remainingIndex), event.getY(remainingIndex));
                            touchMode = DRAG;
                        }
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    touchMode = NONE;
                    break;
            }
            return true;
        }

        private void midPoint(PointF point, MotionEvent event) {
            if (event.getPointerCount() < 2) {
                return;
            }
            point.set((event.getX(0) + event.getX(1)) / 2f, (event.getY(0) + event.getY(1)) / 2f);
        }

        private float rotation(MotionEvent event) {
            if (event.getPointerCount() < 2) {
                return 0f;
            }
            return (float) Math.toDegrees(Math.atan2(event.getY(0) - event.getY(1), event.getX(0) - event.getX(1)));
        }
    }
}
