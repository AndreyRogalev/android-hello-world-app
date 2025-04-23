package com.example.helloworld;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
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
import android.hardware.Camera;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.List;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private static final String TAG = "MainActivity";
    private static final int PICK_IMAGE_REQUEST = 1;
    private static final int CAMERA_PERMISSION_CODE = 100;
    private static final int STORAGE_PERMISSION_CODE = 101;

    // Ключи для сохранения состояния
    private static final String KEY_IMAGE_URI = "imageUri";
    private static final String KEY_SCALE_FACTOR = "scaleFactor";
    private static final String KEY_ROTATION_ANGLE = "rotationAngle";
    private static final String KEY_MATRIX_VALUES = "matrixValues";
    private static final String KEY_CONTROLS_VISIBLE = "controlsVisible";

    // UI Элементы
    private ImageView imageView;
    private SeekBar transparencySeekBar;
    private Button pickImageButton;
    private SurfaceView cameraSurfaceView;
    private SurfaceHolder cameraSurfaceHolder;
    private CheckBox controlsVisibilityCheckbox;
    private Switch pencilModeSwitch;
    private LinearLayout layerControls;
    private CheckBox[] layerCheckBoxes;

    // Камера
    private Camera camera;
    private boolean isPreviewRunning = false;
    private int currentCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;

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
    private boolean[] layerVisibility = new boolean[20]; // Для 9H, 8H, 7H, 6H, 5H, 4H, 3H, 2H, H, F, HB, B, 2B, 3B, 4B, 5B, 6B, 7B, 8B, 9B
    private static final String[] PENCIL_HARDNESS = {
            "9H", "8H", "7H", "6H", "5H", "4H", "3H", "2H", "H", "F",
            "HB", "B", "2B", "3B", "4B", "5B", "6B", "7B", "8B", "9B"
    };

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Полноэкранный режим
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        setContentView(R.layout.activity_main);

        // Инициализация UI
        imageView = findViewById(R.id.imageView);
        transparencySeekBar = findViewById(R.id.transparencySeekBar);
        pickImageButton = findViewById(R.id.pickImageButton);
        cameraSurfaceView = findViewById(R.id.cameraSurfaceView);
        controlsVisibilityCheckbox = findViewById(R.id.controlsVisibilityCheckbox);
        pencilModeSwitch = findViewById(R.id.pencilModeSwitch);
        layerControls = findViewById(R.id.layerControls);

        // Настройка ImageView для трансформаций
        imageView.setScaleType(ImageView.ScaleType.MATRIX);

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

        // Слушатель для CheckBox видимости контролов
        controlsVisibilityCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            updateControlsVisibility(isChecked);
        });

        // Слушатель для Switch карандашного режима
        pencilModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isPencilMode = isChecked;
            layerControls.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            updateImageDisplay();
        });

        // Инициализация CheckBox для слоев в порядке твердости
        layerCheckBoxes = new CheckBox[] {
                findViewById(R.id.layer9H),    // 0: 9H
                findViewById(R.id.layer8H),    // 1: 8H
                findViewById(R.id.layer7H),    // 2: 7H
                findViewById(R.id.layer6H),    // 3: 6H
                findViewById(R.id.layer5H),    // 4: 5H
                findViewById(R.id.layer4H),    // 5: 4H
                findViewById(R.id.layer3H),    // 6: 3H
                findViewById(R.id.layer2H),    // 7: 2H
                findViewById(R.id.layerH),     // 8: H
                findViewById(R.id.layerF),     // 9: F
                findViewById(R.id.layerHB),    // 10: HB
                findViewById(R.id.layerB),     // 11: B
                findViewById(R.id.layer2B),    // 12: 2B
                findViewById(R.id.layer3B),    // 13: 3B
                findViewById(R.id.layer4B),    // 14: 4B
                findViewById(R.id.layer5B),    // 15: 5B
                findViewById(R.id.layer6B),    // 16: 6B
                findViewById(R.id.layer7B),    // 17: 7B
                findViewById(R.id.layer8B),    // 18: 8B
                findViewById(R.id.layer9B)     // 19: 9B
        };

        // Установка слушателей для CheckBox слоев
        for (int i = 0; i < layerCheckBoxes.length; i++) {
            final int index = i;
            layerVisibility[i] = true;
            layerCheckBoxes[i].setOnCheckedChangeListener((buttonView, isChecked) -> {
                layerVisibility[index] = isChecked;
                updateImageDisplay();
            });
        }

        // Настройка распознавания жестов
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());
        imageView.setOnTouchListener(new MyTouchListener());

        // Настройка SurfaceHolder для камеры
        cameraSurfaceHolder = cameraSurfaceView.getHolder();
        cameraSurfaceHolder.addCallback(this);

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
            } else {
                Log.d(TAG, "No matrix values found in saved state.");
                if (currentImageUri != null) {
                    loadImage(currentImageUri, false);
                }
            }
            setImageAlpha(transparencySeekBar.getProgress());

            restoredControlsVisible = savedInstanceState.getBoolean(KEY_CONTROLS_VISIBLE, true);
            controlsVisibilityCheckbox.setChecked(restoredControlsVisible);

            // Восстановление карандашного режима и видимости слоев
            isPencilMode = savedInstanceState.getBoolean("isPencilMode", false);
            boolean[] savedLayerVisibility = savedInstanceState.getBooleanArray("layerVisibility");
            if (savedLayerVisibility != null && savedLayerVisibility.length == layerVisibility.length) {
                System.arraycopy(savedLayerVisibility, 0, layerVisibility, 0, layerVisibility.length);
            }
            pencilModeSwitch.setChecked(isPencilMode);
            layerControls.setVisibility(isPencilMode ? View.VISIBLE : View.GONE);
            for (int i = 0; i < layerCheckBoxes.length; i++) {
                layerCheckBoxes[i].setChecked(layerVisibility[i]);
            }
        } else {
            Log.d(TAG, "No saved state found.");
            restoredControlsVisible = controlsVisibilityCheckbox.isChecked();
        }

        updateControlsVisibility(restoredControlsVisible);
    }

    private void updateControlsVisibility(boolean show) {
        int visibility = show ? View.VISIBLE : View.GONE;
        String checkboxText = show ? getString(R.string.show_controls) : "";

        if (pickImageButton != null) {
            pickImageButton.setVisibility(visibility);
        }
        if (transparencySeekBar != null) {
            transparencySeekBar.setVisibility(visibility);
        }
        if (pencilModeSwitch != null) {
            pencilModeSwitch.setVisibility(visibility);
        }
        if (controlsVisibilityCheckbox != null) {
            controlsVisibilityCheckbox.setText(checkboxText);
        }

        Log.d(TAG, "Controls visibility updated: " + (show ? "VISIBLE" : "GONE"));
    }

    // --- Управление разрешениями ---
    private void checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        } else {
            Log.d(TAG, "Camera permission already granted.");
            if (cameraSurfaceHolder.getSurface() != null && cameraSurfaceHolder.getSurface().isValid()) {
                startCamera();
            }
        }
    }

    private void checkPermissionAndPickImage() {
        String permission;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permission = Manifest.permission.READ_MEDIA_IMAGES;
        } else {
            permission = Manifest.permission.READ_EXTERNAL_STORAGE;
        }

        if (ContextCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{permission}, STORAGE_PERMISSION_CODE);
        } else {
            Log.d(TAG, "Storage permission already granted.");
            openImagePicker();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Camera Permission Granted", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Camera permission granted by user.");
                if (cameraSurfaceHolder.getSurface() != null && cameraSurfaceHolder.getSurface().isValid()) {
                    startCamera();
                }
            } else {
                Toast.makeText(this, "Camera Permission Denied", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Camera permission denied by user.");
            }
        } else if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Storage Permission Granted", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Storage permission granted by user.");
                openImagePicker();
            } else {
                Toast.makeText(this, "Storage Permission Denied", Toast.LENGTH_SHORT).show();
                Log.e(TAG, "Storage permission denied by user.");
            }
        }
    }

    // --- Выбор и загрузка изображения ---
    private void openImagePicker() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        try {
            startActivityForResult(Intent.createChooser(intent, "Select Picture"), PICK_IMAGE_REQUEST);
            Log.d(TAG, "Starting image picker activity.");
        } catch (android.content.ActivityNotFoundException ex) {
            Toast.makeText(this, "Please install a File Manager.", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "No activity found to handle image picking.", ex);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_IMAGE_REQUEST && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            currentImageUri = data.getData();
            Log.d(TAG, "Image selected: " + currentImageUri.toString());
            loadImage(currentImageUri, false);
        } else {
            Log.w(TAG, "Image selection cancelled or failed. ResultCode: " + resultCode);
        }
    }

    private void loadImage(Uri uri, boolean isRestoring) {
        if (uri != null) {
            Log.d(TAG, "Initiating image load for URI: " + uri);
            new LoadImageTask(this, isRestoring).execute(uri);
        } else {
            Log.e(TAG, "Cannot load image, URI is null.");
        }
    }

    private static class LoadImageTask extends AsyncTask<Uri, Void, Bitmap> {
        private final WeakReference<MainActivity> activityReference;
        private final Uri imageUri;
        private final boolean isRestoringState;

        LoadImageTask(MainActivity context, boolean isRestoring) {
            activityReference = new WeakReference<>(context);
            imageUri = context.currentImageUri;
            isRestoringState = isRestoring;
        }

        @Override
        protected Bitmap doInBackground(Uri... uris) {
            MainActivity activity = activityReference.get();
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

                int imageHeight = options.outHeight;
                int imageWidth = options.outWidth;
                Log.d(TAG, "LoadImageTask: Original image size: " + imageWidth + "x" + imageHeight);

                int reqWidth = 1280; // Уменьшено для оптимизации памяти
                int reqHeight = 720;
                options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
                Log.d(TAG, "LoadImageTask: Calculated inSampleSize: " + options.inSampleSize);

                options.inJustDecodeBounds = false;
                inputStream = resolver.openInputStream(imageUri);
                Bitmap bitmap = BitmapFactory.decodeStream(inputStream, null, options);
                Log.d(TAG, "LoadImageTask: Loaded bitmap with size: " + (bitmap != null ? bitmap.getWidth() + "x" + bitmap.getHeight() : "null"));
                return bitmap;

            } catch (FileNotFoundException e) {
                Log.e(TAG, "LoadImageTask: File not found for URI: " + imageUri, e);
                return null;
            } catch (IOException e) {
                Log.e(TAG, "LoadImageTask: IOException during bitmap loading", e);
                return null;
            } catch (OutOfMemoryError e) {
                Log.e(TAG, "LoadImageTask: OutOfMemoryError during bitmap loading", e);
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

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            MainActivity activity = activityReference.get();
            if (activity == null || activity.isFinishing()) {
                Log.w(TAG, "LoadImageTask: Activity is gone, cannot set bitmap.");
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                return;
            }

            if (bitmap != null) {
                Log.d(TAG, "LoadImageTask: Successfully loaded bitmap. Setting to ImageView.");
                if (activity.originalBitmap != null && !activity.originalBitmap.isRecycled()) {
                    Log.d(TAG, "LoadImageTask: Recycling previous bitmap.");
                    activity.originalBitmap.recycle();
                }
                activity.originalBitmap = bitmap;
                activity.pencilBitmap = null;
                activity.layerBitmaps = null;
                activity.updateImageDisplay();

                if (isRestoringState) {
                    Log.d(TAG, "LoadImageTask: Restoring state - applying saved matrix.");
                    activity.imageView.post(() -> activity.imageView.setImageMatrix(activity.matrix));
                } else {
                    Log.d(TAG, "LoadImageTask: New image loaded - resetting transformations and fitting.");
                    activity.resetTransformationsAndFit();
                }
            } else {
                Log.e(TAG, "LoadImageTask: Failed to load bitmap.");
                Toast.makeText(activity, "Failed to load image", Toast.LENGTH_SHORT).show();
                activity.imageView.setVisibility(View.INVISIBLE);
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

    // --- Манипуляции с изображением ---
    private void resetTransformationsAndFit() {
        if (originalBitmap == null || imageView.getWidth() == 0 || imageView.getHeight() == 0) {
            Log.w(TAG, "Cannot reset/fit image: Bitmap is null or ImageView dimensions are zero.");
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
            Log.d(TAG, "Applying initial fit matrix. Initial scale: " + initialScale + ", Initial translation: (" + initialTranslateX + ", " + initialTranslateY + ")");
            imageView.setImageMatrix(matrix);
            scaleFactor = 1.0f;
            rotationAngle = 0.0f;
        });
    }

    private void applyTransformations() {
        if (originalBitmap == null || imageView.getWidth() == 0 || imageView.getHeight() == 0) {
            Log.w(TAG, "Cannot apply transformations: Bitmap is null or ImageView dimensions are zero.");
            return;
        }

        imageView.post(() -> {
            Log.d(TAG, "Applying matrix to ImageView. Scale: " + scaleFactor + ", Rotation: " + rotationAngle);
            imageView.setImageMatrix(matrix);
        });
    }

    private void setImageAlpha(int progress) {
        int alpha = (int) (((float) progress / 100.0f) * 255);
        if (imageView != null) {
            imageView.setImageAlpha(alpha);
            Log.d(TAG, "Setting ImageView alpha to " + alpha + " (progress: " + progress + ")");
        }
    }

    // --- Карандашный режим ---
    private void processPencilEffect() {
        if (originalBitmap == null) {
            Log.w(TAG, "Cannot process pencil effect: originalBitmap is null");
            return;
        }

        if (pencilBitmap != null && !pencilBitmap.isRecycled()) {
            pencilBitmap.recycle();
        }
        if (layerBitmaps != null) {
            for (Bitmap layer : layerBitmaps) {
                if (layer != null && !layer.isRecycled()) {
                    layer.recycle();
                }
            }
        }

        pencilBitmap = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(pencilBitmap);
        Paint paint = new Paint();
        ColorMatrix colorMatrix = new ColorMatrix();
        colorMatrix.setSaturation(0);
        ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
        paint.setColorFilter(filter);
        canvas.drawBitmap(originalBitmap, 0, 0, paint);

        layerBitmaps = new Bitmap[20]; // Для 9H–9B
        for (int i = 0; i < 20; i++) {
            layerBitmaps[i] = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
            layerBitmaps[i].eraseColor(Color.TRANSPARENT);
        }

        int[] pixels = new int[originalBitmap.getWidth() * originalBitmap.getHeight()];
        pencilBitmap.getPixels(pixels, 0, originalBitmap.getWidth(), 0, 0, originalBitmap.getWidth(), originalBitmap.getHeight());

        for (int i = 0; i < pixels.length; i++) {
            int gray = Color.red(pixels[i]);
            int layerIndex = getLayerIndex(gray);
            if (layerIndex >= 0 && layerIndex < 20) {
                layerBitmaps[layerIndex].setPixel(i % originalBitmap.getWidth(), i / originalBitmap.getWidth(), pixels[i]);
            }
        }

        Log.d(TAG, "Pencil effect processed and layers created");
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
        if (originalBitmap == null) {
            imageView.setVisibility(View.INVISIBLE);
            return;
        }

        if (isPencilMode) {
            if (pencilBitmap == null || layerBitmaps == null) {
                processPencilEffect();
            }

            Bitmap resultBitmap = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(resultBitmap);
            canvas.drawColor(Color.TRANSPARENT);

            for (int i = 0; i < layerBitmaps.length; i++) {
                if (layerVisibility[i] && layerBitmaps[i] != null) {
                    canvas.drawBitmap(layerBitmaps[i], 0, 0, null);
                }
            }

            imageView.setImageBitmap(resultBitmap);
            setImageAlpha(transparencySeekBar.getProgress());
            imageView.post(() -> imageView.setImageMatrix(matrix));
        } else {
            imageView.setImageBitmap(originalBitmap);
            setImageAlpha(transparencySeekBar.getProgress());
            imageView.post(() -> imageView.setImageMatrix(matrix));
        }

        imageView.setVisibility(View.VISIBLE);
        Log.d(TAG, "Image display updated. Pencil mode: " + isPencilMode);
    }

    // --- Обработка жестов ---
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            if (originalBitmap == null) return false;

            float previousScaleFactor = scaleFactor;
            scaleFactor *= detector.getScaleFactor();
            scaleFactor = Math.max(0.1f, Math.min(scaleFactor, 5.0f));

            float scaleChange = scaleFactor / previousScaleFactor;
            matrix.postScale(scaleChange, scaleChange, detector.getFocusX(), detector.getFocusY());

            Log.d(TAG, "Scale Gesture: Factor=" + scaleFactor + ", Focus=(" + detector.getFocusX() + ", " + detector.getFocusY() + ")");
            applyTransformations();
            return true;
        }
    }

    private class MyTouchListener implements View.OnTouchListener {
        private static final String TOUCH_TAG = "MyTouchListener";
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
                    Log.d(TOUCH_TAG, "ACTION_DOWN");
                    lastEventX = event.getX();
                    lastEventY = event.getY();
                    startPoint.set(event.getX(), event.getY());
                    touchMode = DRAG;
                    break;

                case MotionEvent.ACTION_POINTER_DOWN:
                    Log.d(TOUCH_TAG, "ACTION_POINTER_DOWN (" + pointerCount + " pointers)");
                    if (pointerCount >= 2) {
                        touchMode = ROTATE;
                        initialDistance = spacing(event);
                        initialAngle = rotation(event);
                        midPoint(midPoint, event);
                        Log.d(TOUCH_TAG, "Mode: ROTATE. Initial distance: " + initialDistance + ", Initial angle: " + initialAngle);
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

                        Log.d(TOUCH_TAG, "Rotating by " + deltaAngle + " degrees around (" + midPoint.x + ", " + midPoint.y + ")");
                        initialAngle = currentAngle;
                        rotationAngle += deltaAngle;

                        applyTransformations();
                    }
                    break;

                case MotionEvent.ACTION_UP:
                    Log.d(TOUCH_TAG, "ACTION_UP");
                    touchMode = NONE;
                    break;

                case MotionEvent.ACTION_POINTER_UP:
                    Log.d(TOUCH_TAG, "ACTION_POINTER_UP");
                    if (pointerCount <= 2) {
                        touchMode = DRAG;
                        int remainingPointerIndex = (event.getActionIndex() == 0) ? 1 : 0;
                        if (remainingPointerIndex < event.getPointerCount()) {
                            lastEventX = event.getX(remainingPointerIndex);
                            lastEventY = event.getY(remainingPointerIndex);
                            Log.d(TOUCH_TAG, "Switching back to DRAG mode. Updated last touch point.");
                        } else {
                            Log.w(TOUCH_TAG, "Pointer index out of bounds after POINTER_UP");
                            touchMode = NONE;
                        }
                    } else {
                        if (pointerCount >= 2) {
                            initialDistance = spacing(event);
                            initialAngle = rotation(event);
                            midPoint(midPoint, event);
                        }
                    }
                    break;

                case MotionEvent.ACTION_CANCEL:
                    Log.d(TOUCH_TAG, "ACTION_CANCEL");
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

    // --- Управление камерой ---
    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        Log.d(TAG, "Surface created.");
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            Log.w(TAG, "Surface created, but camera permission not granted yet.");
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "Surface changed. New dimensions: " + width + "x" + height);
        if (cameraSurfaceHolder.getSurface() == null) {
            Log.e(TAG, "Surface is null in surfaceChanged, returning.");
            return;
        }

        if (isPreviewRunning) {
            try {
                camera.stopPreview();
                Log.d(TAG, "Preview stopped.");
                isPreviewRunning = false;
            } catch (Exception e) {
                Log.e(TAG, "Error stopping preview", e);
            }
        }

        if (camera != null) {
            try {
                Camera.Parameters parameters = camera.getParameters();
                Camera.Size bestPreviewSize = getBestPreviewSize(parameters, width, height);
                if (bestPreviewSize != null) {
                    parameters.setPreviewSize(bestPreviewSize.width, bestPreviewSize.height);
                    Log.d(TAG, "Setting preview size to: " + bestPreviewSize.width + "x" + bestPreviewSize.height);
                } else {
                    Log.w(TAG, "Could not find optimal preview size.");
                }

                List<String> focusModes = parameters.getSupportedFocusModes();
                if (focusModes != null) {
                    if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                        Log.d(TAG, "Setting focus mode to CONTINUOUS_PICTURE");
                    } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                        Log.d(TAG, "Setting focus mode to AUTO");
                    }
                }

                try {
                    camera.setParameters(parameters);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Error setting camera parameters", e);
                    try {
                        Log.w(TAG, "Trying to set only preview size after parameter error");
                        parameters = camera.getParameters();
                        if (bestPreviewSize != null) {
                            parameters.setPreviewSize(bestPreviewSize.width, bestPreviewSize.height);
                            camera.setParameters(parameters);
                        }
                    } catch (RuntimeException e2) {
                        Log.e(TAG, "Failed to set even preview size", e2);
                    }
                }

                setCameraDisplayOrientation();
                camera.setPreviewDisplay(cameraSurfaceHolder);
                camera.startPreview();
                isPreviewRunning = true;
                Log.d(TAG, "Preview started.");

            } catch (IOException e) {
                Log.e(TAG, "IOException setting preview display", e);
            } catch (RuntimeException e) {
                Log.e(TAG, "RuntimeException starting preview or setting parameters", e);
                releaseCamera();
            }
        } else {
            Log.e(TAG, "Camera object is null in surfaceChanged.");
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        Log.d(TAG, "Surface destroyed.");
        releaseCamera();
    }

    private void startCamera() {
        if (camera != null) {
            Log.w(TAG, "startCamera called when camera is already initialized.");
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "startCamera called without camera permission!");
            return;
        }

        try {
            Log.d(TAG, "Attempting to open camera ID: " + currentCameraId);
            camera = Camera.open(currentCameraId);
            if (camera == null) {
                Log.e(TAG, "Failed to open camera with ID: " + currentCameraId);
                try {
                    Log.w(TAG, "Trying to open default camera (ID 0)");
                    currentCameraId = 0;
                    camera = Camera.open(currentCameraId);
                    if (camera == null) {
                        Log.e(TAG, "Failed to open default camera as well.");
                        Toast.makeText(this, "Failed to open camera", Toast.LENGTH_LONG).show();
                        return;
                    }
                } catch (RuntimeException e) {
                    Log.e(TAG, "RuntimeException opening default camera", e);
                    Toast.makeText(this, "Cannot access camera", Toast.LENGTH_LONG).show();
                    return;
                }
            }
            Log.d(TAG, "Camera opened successfully. ID: " + currentCameraId);

            if (cameraSurfaceHolder != null && cameraSurfaceHolder.getSurface() != null && cameraSurfaceHolder.getSurface().isValid()) {
                try {
                    camera.setPreviewDisplay(cameraSurfaceHolder);
                    Log.d(TAG, "Preview display set in startCamera (surface was ready).");
                } catch (IOException e) {
                    Log.e(TAG, "IOException setting preview display in startCamera", e);
                    releaseCamera();
                }
            } else {
                Log.d(TAG, "Surface not ready in startCamera, preview display will be set in surfaceChanged.");
            }

        } catch (RuntimeException e) {
            Log.e(TAG, "RuntimeException opening camera ID: " + currentCameraId, e);
            Toast.makeText(this, "Cannot access camera. Is it in use?", Toast.LENGTH_LONG).show();
            camera = null;
        }
    }

    private void releaseCamera() {
        if (camera != null) {
            Log.d(TAG, "Releasing camera...");
            if (isPreviewRunning) {
                try {
                    camera.stopPreview();
                    Log.d(TAG, "Preview stopped before release.");
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping preview during release", e);
                }
                isPreviewRunning = false;
            }
            try {
                camera.release();
                Log.d(TAG, "Camera released.");
            } catch (Exception e) {
                Log.e(TAG, "Error releasing camera", e);
            }
            camera = null;
        }
    }

    private Camera.Size getBestPreviewSize(Camera.Parameters parameters, int surfaceWidth, int surfaceHeight) {
        List<Camera.Size> supportedPreviewSizes = parameters.getSupportedPreviewSizes();
        if (supportedPreviewSizes == null || supportedPreviewSizes.isEmpty()) {
            Log.e(TAG, "Camera has no supported preview sizes!");
            return null;
        }

        Camera.Size bestSize = null;
        double targetRatio = (double) surfaceWidth / surfaceHeight;
        int currentOrientation = getResources().getConfiguration().orientation;
        if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) {
            targetRatio = (double) surfaceHeight / surfaceWidth;
            Log.d(TAG, "Portrait orientation detected, using target ratio: " + targetRatio + " (H/W)");
        } else {
            Log.d(TAG, "Landscape orientation detected, using target ratio: " + targetRatio + " (W/H)");
        }

        double minDiff = Double.MAX_VALUE;
        supportedPreviewSizes.sort((a, b) -> Integer.compare(b.width * b.height, a.width * a.height));

        Log.d(TAG, "Supported preview sizes:");
        for (Camera.Size size : supportedPreviewSizes) {
            Log.d(TAG, " - " + size.width + "x" + size.height);
        }

        for (Camera.Size size : supportedPreviewSizes) {
            double ratio = (double) size.width / size.height;
            double diff = Math.abs(ratio - targetRatio);
            Log.v(TAG, "Checking size: " + size.width + "x" + size.height + ", Ratio: " + ratio + ", Diff: " + diff);

            if (diff < minDiff) {
                bestSize = size;
                minDiff = diff;
                Log.v(TAG, "New best candidate (minDiff): " + bestSize.width + "x" + bestSize.height);
            } else if (Math.abs(diff - minDiff) < 0.01) {
                if (bestSize == null || size.width * size.height > bestSize.width * bestSize.height) {
                    bestSize = size;
                    Log.v(TAG, "New best candidate (larger size with similar ratio): " + bestSize.width + "x" + bestSize.height);
                }
            }
        }

        if (bestSize == null && !supportedPreviewSizes.isEmpty()) {
            bestSize = supportedPreviewSizes.get(0);
            Log.w(TAG, "Could not find good match, falling back to first supported size: " + bestSize.width + "x" + bestSize.height);
        }

        if (bestSize != null) {
            Log.i(TAG, "Best preview size chosen: " + bestSize.width + "x" + bestSize.height);
        } else {
            Log.e(TAG, "Failed to find any suitable preview size.");
        }

        return bestSize;
    }

    private void setCameraDisplayOrientation() {
        if (camera == null) {
            Log.e(TAG, "Cannot set display orientation, camera is null.");
            return;
        }

        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(currentCameraId, info);

        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            Log.e(TAG, "WindowManager is null, cannot get display rotation.");
            return;
        }
        Display display = windowManager.getDefaultDisplay();
        int rotation = display.getRotation();
        int degrees = 0;

        switch (rotation) {
            case Surface.ROTATION_0: degrees = 0; break;
            case Surface.ROTATION_90: degrees = 90; break;
            case Surface.ROTATION_180: degrees = 180; break;
            case Surface.ROTATION_270: degrees = 270; break;
        }

        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;
        } else {
            result = (info.orientation - degrees + 360) % 360;
        }

        Log.d(TAG, "Setting camera display orientation. Device rotation: " + degrees +
                ", Camera sensor orientation: " + info.orientation +
                ", Facing: " + (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? "Front" : "Back") +
                ". Resulting display orientation: " + result);

        try {
            camera.setDisplayOrientation(result);
        } catch (Exception e) {
            Log.e(TAG, "Error setting camera display orientation", e);
        }
    }

    // --- Жизненный цикл Activity ---
    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        if (camera == null && cameraSurfaceHolder != null && cameraSurfaceHolder.getSurface() != null && cameraSurfaceHolder.getSurface().isValid()) {
            Log.d(TAG, "Resuming: Camera was null and surface is valid, starting camera.");
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Log.w(TAG, "Resuming: Cannot start camera, permission not granted.");
            }
        } else if (camera != null && !isPreviewRunning && cameraSurfaceHolder != null && cameraSurfaceHolder.getSurface() != null && cameraSurfaceHolder.getSurface().isValid()) {
            Log.d(TAG, "Resuming: Camera exists but preview not running, attempting restart via surfaceChanged logic.");
            surfaceChanged(cameraSurfaceHolder, 0, cameraSurfaceView.getWidth(), cameraSurfaceView.getHeight());
        }

        if (currentImageUri != null && originalBitmap == null) {
            Log.d(TAG, "Resuming: Image URI exists but bitmap is null, reloading image.");
            loadImage(currentImageUri, true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
        releaseCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        releaseCamera();
        if (originalBitmap != null && !originalBitmap.isRecycled()) {
            Log.d(TAG, "Recycling bitmap in onDestroy.");
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
            Log.d(TAG, "Saved URI: " + currentImageUri.toString());
        } else {
            Log.d(TAG, "No image URI to save.");
        }

        if (originalBitmap != null) {
            outState.putFloat(KEY_SCALE_FACTOR, scaleFactor);
            outState.putFloat(KEY_ROTATION_ANGLE, rotationAngle);
            float[] matrixValues = new float[9];
            matrix.getValues(matrixValues);
            outState.putFloatArray(KEY_MATRIX_VALUES, matrixValues);
            Log.d(TAG, "Saved scaleFactor: " + scaleFactor);
            Log.d(TAG, "Saved rotationAngle: " + rotationAngle);
            Log.d(TAG, "Saved Matrix values: TX=" + matrixValues[Matrix.MTRANS_X] + ", TY=" + matrixValues[Matrix.MTRANS_Y] + ", SX=" + matrixValues[Matrix.MSCALE_X]);
        } else {
            Log.d(TAG, "No bitmap, not saving transformation state.");
        }

        if (controlsVisibilityCheckbox != null) {
            outState.putBoolean(KEY_CONTROLS_VISIBLE, controlsVisibilityCheckbox.isChecked());
            Log.d(TAG, "Saved controlsVisible (isChecked): " + controlsVisibilityCheckbox.isChecked());
        }

        outState.putBoolean("isPencilMode", isPencilMode);
        outState.putBooleanArray("layerVisibility", layerVisibility);
    }

    public void switchCamera() {
        if (camera == null) {
            Log.w(TAG, "Cannot switch camera, camera is null.");
            checkCameraPermission();
            return;
        }

        int numberOfCameras = Camera.getNumberOfCameras();
        if (numberOfCameras < 2) {
            Toast.makeText(this, "Only one camera available", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "Switch camera called, but only one camera detected.");
            return;
        }

        Log.d(TAG, "Switching camera...");
        releaseCamera();

        currentCameraId = (currentCameraId == Camera.CameraInfo.CAMERA_FACING_BACK)
                ? Camera.CameraInfo.CAMERA_FACING_FRONT
                : Camera.CameraInfo.CAMERA_FACING_BACK;

        Log.d(TAG, "Switched camera ID to: " + currentCameraId);

        if (cameraSurfaceHolder != null && cameraSurfaceHolder.getSurface() != null && cameraSurfaceHolder.getSurface().isValid()) {
            startCamera();
            if (camera != null) {
                Log.d(TAG, "Manually calling surfaceChanged after camera switch.");
                surfaceChanged(cameraSurfaceHolder, 0, cameraSurfaceView.getWidth(), cameraSurfaceView.getHeight());
            }
        } else {
            Log.w(TAG, "Surface not ready after camera switch, camera will start later via surfaceCreated/Changed.");
        }
    }
}
