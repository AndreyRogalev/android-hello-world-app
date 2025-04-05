package com.example.helloworld; // Замените на ваш пакет

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
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
import android.widget.ImageView;
import android.widget.SeekBar;
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
    private static final String KEY_MATRIX_VALUES = "matrixValues"; // Сохраняем всю матрицу

    // UI Элементы
    private ImageView imageView;
    private SeekBar transparencySeekBar;
    private Button pickImageButton;
    private SurfaceView cameraSurfaceView;
    private SurfaceHolder cameraSurfaceHolder;

    // Камера
    private Camera camera;
    private boolean isPreviewRunning = false;
    private int currentCameraId = Camera.CameraInfo.CAMERA_FACING_BACK; // Начинаем с задней камеры

    // Манипуляции с изображением
    private Bitmap originalBitmap = null;
    private Matrix matrix = new Matrix();
    private float scaleFactor = 1.0f;
    private float rotationAngle = 0.0f; // В градусах

    // Распознавание жестов
    private ScaleGestureDetector scaleGestureDetector;
    private float lastTouchX;
    private float lastTouchY;
    private float posX, posY; // Не используется напрямую, т.к. работаем с матрицей

    // Состояние
    private Uri currentImageUri = null;

    // Константы для режимов касания в MyTouchListener
    private static final int NONE = 0;
    private static final int DRAG = 1;
    private static final int ZOOM = 2; // Используется ScaleGestureDetector
    private static final int ROTATE = 3;
    private int touchMode = NONE;

    // Для вращения
    private PointF startPoint = new PointF();
    private PointF midPoint = new PointF();
    private float initialAngle = 0f;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // --- Полноэкранный режим ---
        requestWindowFeature(Window.FEATURE_NO_TITLE); // Скрыть заголовок
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN); // Скрыть строку состояния
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide(); // Скрыть ActionBar, если он есть
        }
        // --- Конец полноэкранного режима ---

        setContentView(R.layout.activity_main);

        // Инициализация UI
        imageView = findViewById(R.id.imageView);
        transparencySeekBar = findViewById(R.id.transparencySeekBar);
        pickImageButton = findViewById(R.id.pickImageButton);
        cameraSurfaceView = findViewById(R.id.cameraSurfaceView);

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

        // Настройка распознавания жестов
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());
        imageView.setOnTouchListener(new MyTouchListener()); // Устанавливаем наш кастомный листенер

        // Настройка SurfaceHolder для камеры
        cameraSurfaceHolder = cameraSurfaceView.getHolder();
        cameraSurfaceHolder.addCallback(this);
        // Для старых версий Android (уже не так актуально, но не повредит)
        // cameraSurfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        // Запрос разрешения на использование камеры при старте
        checkCameraPermission();


        // --- Восстановление состояния ---
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
                  // Применяем сохраненную матрицу сразу, если изображение уже загружено
                 if (currentImageUri != null) {
                     loadImage(currentImageUri, true); // Загружаем, но не сбрасываем матрицу
                 }
             } else {
                Log.d(TAG, "No matrix values found in saved state.");
                // Если матрица не сохранилась, но URI есть, просто загружаем изображение
                if (currentImageUri != null) {
                    loadImage(currentImageUri, false); // Загружаем и сбрасываем трансформации
                }
             }
             setImageAlpha(transparencySeekBar.getProgress()); // Восстанавливаем прозрачность

        } else {
             Log.d(TAG, "No saved state found.");
        }
        // --- Конец восстановления состояния ---
    }

    // --- Управление разрешениями ---

    private void checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
        } else {
             Log.d(TAG, "Camera permission already granted.");
            // Разрешение уже есть, можно пробовать стартовать камеру, если Surface готов
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
                // Пробуем стартовать камеру, если Surface готов
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
        // intent.setType("image/*"); // Можно использовать ACTION_GET_CONTENT
        // intent.setAction(Intent.ACTION_GET_CONTENT);
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
            loadImage(currentImageUri, false); // Загружаем новое изображение, сбрасываем трансформации
        } else {
             Log.w(TAG, "Image selection cancelled or failed. ResultCode: " + resultCode);
        }
    }

    // Загрузка изображения (вызывает AsyncTask)
    private void loadImage(Uri uri, boolean isRestoring) {
        if (uri != null) {
            Log.d(TAG, "Initiating image load for URI: " + uri);
            new LoadImageTask(this, isRestoring).execute(uri);
        } else {
            Log.e(TAG, "Cannot load image, URI is null.");
        }
    }

    // AsyncTask для загрузки Bitmap в фоновом потоке
    private static class LoadImageTask extends AsyncTask<Uri, Void, Bitmap> {
        private final WeakReference<MainActivity> activityReference;
        private final Uri imageUri;
        private final boolean isRestoringState; // Флаг, указывающий на восстановление состояния


        LoadImageTask(MainActivity context, boolean isRestoring) {
            activityReference = new WeakReference<>(context);
            imageUri = context.currentImageUri; // Берем URI из MainActivity
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
                 // Сначала получаем размеры изображения, не загружая его полностью
                 BitmapFactory.Options options = new BitmapFactory.Options();
                 options.inJustDecodeBounds = true;
                 inputStream = resolver.openInputStream(imageUri);
                 BitmapFactory.decodeStream(inputStream, null, options);
                 if (inputStream != null) inputStream.close(); // Закрываем поток

                 int imageHeight = options.outHeight;
                 int imageWidth = options.outWidth;
                 String imageType = options.outMimeType;
                 Log.d(TAG, "LoadImageTask: Original image size: " + imageWidth + "x" + imageHeight);

                 // Устанавливаем максимальный размер (можно использовать размеры экрана)
                 // Для простоты возьмем фиксированные значения, например 1920x1080
                 int reqWidth = 1920;
                 int reqHeight = 1080;

                 // Вычисляем inSampleSize
                 options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);
                 Log.d(TAG, "LoadImageTask: Calculated inSampleSize: " + options.inSampleSize);

                 // Загружаем Bitmap с учетом inSampleSize
                 options.inJustDecodeBounds = false;
                 inputStream = resolver.openInputStream(imageUri);
                 Bitmap bitmap = BitmapFactory.decodeStream(inputStream, null, options);
                 Log.d(TAG, "LoadImageTask: Loaded bitmap with size: " + (bitmap != null ? bitmap.getWidth()+"x"+bitmap.getHeight() : "null"));
                 return bitmap;

             } catch (FileNotFoundException e) {
                 Log.e(TAG, "LoadImageTask: File not found for URI: " + imageUri, e);
                 return null;
             } catch (IOException e) {
                 Log.e(TAG, "LoadImageTask: IOException during bitmap loading", e);
                 return null;
             } catch (OutOfMemoryError e) {
                 Log.e(TAG, "LoadImageTask: OutOfMemoryError during bitmap loading", e);
                 // Можно попробовать загрузить с еще большим inSampleSize
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
                     bitmap.recycle(); // Освобождаем память, если Activity уже нет
                 }
                 return;
             }

             if (bitmap != null) {
                 Log.d(TAG, "LoadImageTask: Successfully loaded bitmap. Setting to ImageView.");
                 // Освобождаем память от предыдущего Bitmap, если он был
                 if (activity.originalBitmap != null && !activity.originalBitmap.isRecycled()) {
                     Log.d(TAG, "LoadImageTask: Recycling previous bitmap.");
                     activity.originalBitmap.recycle();
                 }
                 activity.originalBitmap = bitmap;
                 activity.imageView.setImageBitmap(activity.originalBitmap);
                 activity.imageView.setVisibility(View.VISIBLE);

                 if (isRestoringState) {
                     Log.d(TAG, "LoadImageTask: Restoring state - applying saved matrix.");
                     // Матрица уже восстановлена в onCreate, просто применяем её
                      activity.imageView.post(() -> activity.imageView.setImageMatrix(activity.matrix));
                 } else {
                      Log.d(TAG, "LoadImageTask: New image loaded - resetting transformations and fitting.");
                      // Сбрасываем и центрируем изображение при первой загрузке
                      activity.resetTransformationsAndFit();
                 }
                  // Убеждаемся, что прозрачность соответствует SeekBar
                 activity.setImageAlpha(activity.transparencySeekBar.getProgress());

             } else {
                 Log.e(TAG, "LoadImageTask: Failed to load bitmap.");
                 Toast.makeText(activity, "Failed to load image", Toast.LENGTH_SHORT).show();
                 activity.imageView.setVisibility(View.INVISIBLE);
                 activity.originalBitmap = null; // Убедимся, что ссылка на старый битмап очищена
                 activity.currentImageUri = null; // Сбрасываем URI, если загрузка не удалась
                 activity.matrix.reset(); // Сбрасываем матрицу
                 activity.scaleFactor = 1.0f;
                 activity.rotationAngle = 0.0f;
             }
        }

        // Вспомогательный метод для расчета inSampleSize
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
            return inSampleSize;
        }
    }

    // --- Манипуляции с изображением (Трансформации) ---

     // Сброс трансформаций и центрирование/масштабирование изображения по размеру ImageView
     private void resetTransformationsAndFit() {
         if (originalBitmap == null || imageView.getWidth() == 0 || imageView.getHeight() == 0) {
             Log.w(TAG, "Cannot reset/fit image: Bitmap is null or ImageView dimensions are zero.");
             matrix.reset();
             scaleFactor = 1.0f;
             rotationAngle = 0.0f;
             if(imageView != null) imageView.setImageMatrix(matrix); // Применяем сброшенную матрицу
             return;
         }

         matrix.reset(); // Начинаем с чистой матрицы

         float viewWidth = imageView.getWidth();
         float viewHeight = imageView.getHeight();
         float bmpWidth = originalBitmap.getWidth();
         float bmpHeight = originalBitmap.getHeight();

         // Рассчитываем масштаб для вписывания изображения в ImageView с сохранением пропорций
         float scaleX = viewWidth / bmpWidth;
         float scaleY = viewHeight / bmpHeight;
         float initialScale = Math.min(scaleX, scaleY); // Используем минимальный масштаб, чтобы все влезло

         // Рассчитываем координаты для центрирования
         float scaledBmpWidth = bmpWidth * initialScale;
         float scaledBmpHeight = bmpHeight * initialScale;
         float initialTranslateX = (viewWidth - scaledBmpWidth) / 2f;
         float initialTranslateY = (viewHeight - scaledBmpHeight) / 2f;

         // Применяем начальный масштаб и сдвиг
         matrix.postScale(initialScale, initialScale);
         matrix.postTranslate(initialTranslateX, initialTranslateY);

         // Сохраняем начальный масштаб как базовый (хотя scaleFactor сейчас 1.0f относительно этого состояния)
         // scaleFactor = 1.0f; // Пользовательский масштаб пока не применялся
         // rotationAngle = 0.0f; // Угол тоже сбрасываем

         // Обновляем ImageView (лучше делать это в post, чтобы размеры точно были известны)
         imageView.post(() -> {
              Log.d(TAG, "Applying initial fit matrix. Initial scale: " + initialScale + ", Initial translation: (" + initialTranslateX + ", " + initialTranslateY + ")");
              imageView.setImageMatrix(matrix);
              // После установки начальной матрицы, сбрасываем пользовательские факторы
              // Важно: делаем это *после* применения начальной матрицы
              scaleFactor = 1.0f;
              rotationAngle = 0.0f;
         });
     }


    // Применяет текущие scaleFactor, rotationAngle и translation к матрице
    private void applyTransformations() {
        if (originalBitmap == null || imageView.getWidth() == 0 || imageView.getHeight() == 0) {
             Log.w(TAG, "Cannot apply transformations: Bitmap is null or ImageView dimensions are zero.");
            return;
        }

        // Получаем текущую матрицу (она может содержать начальное размещение или предыдущие трансформации)
        // Важно: Не сбрасываем матрицу здесь! Мы добавляем трансформации к текущему состоянию.

        // Точка вращения и масштабирования - центр ImageView
        float pivotX = imageView.getWidth() / 2f;
        float pivotY = imageView.getHeight() / 2f;


        // Создаем временную матрицу для текущей операции, чтобы применить относительно центра
        Matrix tempMatrix = new Matrix(matrix); // Копируем текущую матрицу

        // --- Применение трансформаций ---
        // Логика применения должна быть аккуратной, чтобы она корректно работала
        // с drag, scale и rotate одновременно или по очереди.
        // MyTouchListener и ScaleListener обновляют scaleFactor, rotationAngle и саму матрицу (для drag).
        // Этот метод просто гарантирует, что финальная матрица установлена в ImageView.
        // Фактически, основная работа по обновлению МАТРИЦЫ происходит в слушателях.

        // Убедимся, что scaleFactor и rotationAngle корректно применены к матрице в слушателях.
        // Этот метод теперь больше для применения финальной матрицы к View.

        // Применяем обновленную матрицу к ImageView
         imageView.post(() -> { // Используем post для надежности
             Log.d(TAG, "Applying matrix to ImageView. Scale: " + scaleFactor + ", Rotation: " + rotationAngle);
             // float[] values = new float[9];
             // matrix.getValues(values);
             // Log.d(TAG, "Matrix values: TX=" + values[Matrix.MTRANS_X] + ", TY=" + values[Matrix.MTRANS_Y] + ", SX=" + values[Matrix.MSCALE_X] + ", Rot=" + Math.round(Math.atan2(values[Matrix.MSKEW_X], values[Matrix.MSCALE_X]) * (180 / Math.PI)));
             imageView.setImageMatrix(matrix);
         });
    }


    // Установка прозрачности для ImageView
    private void setImageAlpha(int progress) {
        // progress от 0 до 100 -> alpha от 0 до 255
        int alpha = (int) (((float) progress / 100.0f) * 255);
        if (imageView != null) {
            // setAlpha(int) устарел, используем setImageAlpha(int)
            imageView.setImageAlpha(alpha);
            Log.d(TAG, "Setting ImageView alpha to " + alpha + " (progress: " + progress + ")");
        }
    }

    // --- Обработка жестов (Внутренние классы) ---

    // Слушатель для масштабирования (Pinch-to-Zoom)
    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
             if (originalBitmap == null) return false; // Нечего масштабировать

             float previousScaleFactor = scaleFactor;
             scaleFactor *= detector.getScaleFactor();

             // Ограничиваем масштаб, чтобы избежать слишком большого/маленького изображения
             scaleFactor = Math.max(0.1f, Math.min(scaleFactor, 5.0f));

             float scaleChange = scaleFactor / previousScaleFactor; // На сколько изменился масштаб в этот раз

             // Масштабируем относительно точки фокуса жеста
             matrix.postScale(scaleChange, scaleChange, detector.getFocusX(), detector.getFocusY());

             Log.d(TAG, "Scale Gesture: Factor=" + scaleFactor + ", Focus=(" + detector.getFocusX() + ", " + detector.getFocusY() + ")");

             applyTransformations(); // Применяем обновленную матрицу
            return true; // Жест обработан
        }
    }

    // Кастомный слушатель касаний для ImageView (Перемещение и Вращение)
    private class MyTouchListener implements View.OnTouchListener {
         private static final String TOUCH_TAG = "MyTouchListener";

        // Переменные для отслеживания пальцев и перемещения/вращения
        private float lastEventX = 0, lastEventY = 0;
        private float initialDistance = 0f; // Начальное расстояние между пальцами для вращения

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (originalBitmap == null) return false; // Если нет изображения, не обрабатываем касания

            // Передаем событие в ScaleGestureDetector В ПЕРВУЮ ОЧЕРЕДЬ
            boolean scaleHandled = scaleGestureDetector.onTouchEvent(event);

            int action = event.getActionMasked();
            int pointerCount = event.getPointerCount();

            switch (action) {
                case MotionEvent.ACTION_DOWN: // Первый палец опущен
                    Log.d(TOUCH_TAG, "ACTION_DOWN");
                    lastEventX = event.getX();
                    lastEventY = event.getY();
                    startPoint.set(event.getX(), event.getY()); // Для возможного вращения
                    touchMode = DRAG;
                    break;

                case MotionEvent.ACTION_POINTER_DOWN: // Дополнительный палец опущен
                     Log.d(TOUCH_TAG, "ACTION_POINTER_DOWN (" + pointerCount + " pointers)");
                    if (pointerCount >= 2) {
                         // Переключаемся в режим вращения (масштабирование обрабатывается ScaleGestureDetector)
                         touchMode = ROTATE;
                         initialDistance = spacing(event); // Расстояние между первыми двумя пальцами
                         initialAngle = rotation(event);  // Начальный угол между пальцами
                         midPoint(midPoint, event);      // Средняя точка между пальцами
                         Log.d(TOUCH_TAG, "Mode: ROTATE. Initial distance: " + initialDistance + ", Initial angle: " + initialAngle);
                    }
                    break;

                case MotionEvent.ACTION_MOVE:
                     // Log.d(TOUCH_TAG, "ACTION_MOVE (" + pointerCount + " pointers, Mode: " + touchMode + ")");
                    if (touchMode == DRAG && pointerCount == 1 && !scaleGestureDetector.isInProgress()) {
                        // --- Перемещение (Drag) ---
                        float dx = event.getX() - lastEventX;
                        float dy = event.getY() - lastEventY;
                        matrix.postTranslate(dx, dy);
                        lastEventX = event.getX();
                        lastEventY = event.getY();
                        // Log.d(TOUCH_TAG, "Dragging by (" + dx + ", " + dy + ")");
                         applyTransformations(); // Обновляем вид
                    } else if (touchMode == ROTATE && pointerCount >= 2 && !scaleGestureDetector.isInProgress()) {
                         // --- Вращение (Rotate) ---
                         // Масштабирование уже обработано ScaleGestureDetector, если было
                         // Здесь обрабатываем только вращение, если оно есть

                         float currentAngle = rotation(event);
                         float deltaAngle = currentAngle - initialAngle;

                         // Применяем вращение к глобальной переменной угла
                         // rotationAngle += deltaAngle; // Накапливаем угол? Или применяем к матрице?
                         // Лучше применять дельту к матрице относительно средней точки

                         midPoint(midPoint, event); // Обновляем среднюю точку
                         matrix.postRotate(deltaAngle, midPoint.x, midPoint.y);

                         Log.d(TOUCH_TAG, "Rotating by " + deltaAngle + " degrees around (" + midPoint.x + ", " + midPoint.y + ")");

                         // Важно: Обновляем initialAngle для следующего шага MOVE,
                         // чтобы deltaAngle была относительно предыдущего положения
                         initialAngle = currentAngle;

                          // Обновляем глобальный угол для сохранения состояния (приблизительно)
                          rotationAngle += deltaAngle;

                         applyTransformations(); // Обновляем вид
                    } else if (scaleGestureDetector.isInProgress()){
                         // Если идет масштабирование, не делаем ни drag, ни rotate здесь
                         // Log.d(TOUCH_TAG, "Scaling in progress, skipping drag/rotate.");
                    }
                    break;

                case MotionEvent.ACTION_UP: // Последний палец поднят
                    Log.d(TOUCH_TAG, "ACTION_UP");
                    touchMode = NONE;
                    break;

                case MotionEvent.ACTION_POINTER_UP: // Один из пальцев поднят (но не последний)
                     Log.d(TOUCH_TAG, "ACTION_POINTER_UP");
                    // Если подняли второй палец, возвращаемся в режим DRAG, если остался один
                    if (pointerCount <= 2) { // Было 2, стал 1
                         touchMode = DRAG;
                         // Обновляем lastEventX/Y для пальца, который остался
                         int remainingPointerIndex = (event.getActionIndex() == 0) ? 1 : 0;
                         if (remainingPointerIndex < event.getPointerCount()) {
                             lastEventX = event.getX(remainingPointerIndex);
                             lastEventY = event.getY(remainingPointerIndex);
                             Log.d(TOUCH_TAG, "Switching back to DRAG mode. Updated last touch point.");
                         } else {
                              Log.w(TOUCH_TAG,"Pointer index out of bounds after POINTER_UP");
                              touchMode = NONE; // На всякий случай
                         }
                    } else { // Осталось больше одного пальца
                         // Можно пересчитать initialAngle/Distance, если нужно продолжать ROTATE/ZOOM
                         // Но пока оставим ROTATE (или ZOOM, если ScaleDetector активен)
                         Log.d(TOUCH_TAG,"More than one pointer remains.");
                         // Пересчитываем начальные параметры для вращения/масштабирования
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

            // Возвращаем true, чтобы показать, что событие обработано здесь
            // или ScaleGestureDetector'ом. Это предотвратит передачу события родительским View.
            return true;
        }

         // Вспомогательный метод: вычисляет расстояние между первыми двумя пальцами
         private float spacing(MotionEvent event) {
             if (event.getPointerCount() < 2) return 0f;
             float x = event.getX(0) - event.getX(1);
             float y = event.getY(0) - event.getY(1);
             return (float) Math.sqrt(x * x + y * y);
         }

         // Вспомогательный метод: вычисляет среднюю точку между первыми двумя пальцами
         private void midPoint(PointF point, MotionEvent event) {
              if (event.getPointerCount() < 2) {
                  point.set(event.getX(), event.getY()); // Если один палец, точка там же
                  return;
              }
             float x = event.getX(0) + event.getX(1);
             float y = event.getY(0) + event.getY(1);
             point.set(x / 2, y / 2);
         }

         // Вспомогательный метод: вычисляет угол между первыми двумя пальцами
         private float rotation(MotionEvent event) {
              if (event.getPointerCount() < 2) return 0f;
             double delta_x = (event.getX(0) - event.getX(1));
             double delta_y = (event.getY(0) - event.getY(1));
             double radians = Math.atan2(delta_y, delta_x);
             return (float) Math.toDegrees(radians);
         }
    }


    // --- Управление камерой (SurfaceHolder.Callback и связанные методы) ---

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        Log.d(TAG, "Surface created.");
        // Поверхность создана, пытаемся запустить камеру
         if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
             Log.w(TAG, "Surface created, but camera permission not granted yet.");
             // Запрос разрешения уже должен был быть сделан в onCreate или onRequestPermissionsResult
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "Surface changed. New dimensions: " + width + "x" + height);
        if (cameraSurfaceHolder.getSurface() == null) {
            Log.e(TAG, "Surface is null in surfaceChanged, returning.");
            return;
        }

        // Останавливаем превью перед изменением параметров
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
                // Получаем параметры камеры
                Camera.Parameters parameters = camera.getParameters();

                 // 1. Устанавливаем оптимальный размер превью
                 Camera.Size bestPreviewSize = getBestPreviewSize(parameters, width, height);
                 if (bestPreviewSize != null) {
                     parameters.setPreviewSize(bestPreviewSize.width, bestPreviewSize.height);
                     Log.d(TAG, "Setting preview size to: " + bestPreviewSize.width + "x" + bestPreviewSize.height);
                 } else {
                      Log.w(TAG, "Could not find optimal preview size.");
                 }

                 // 2. Устанавливаем режим фокуса (если поддерживается)
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

                 // 3. Применяем параметры
                 try {
                      camera.setParameters(parameters);
                 } catch (RuntimeException e) {
                      Log.e(TAG, "Error setting camera parameters", e);
                      // Попробуем получить параметры снова и установить только размер превью
                      try {
                          Log.w(TAG,"Trying to set only preview size after parameter error");
                          parameters = camera.getParameters(); // Получаем свежие параметры
                           if (bestPreviewSize != null) {
                               parameters.setPreviewSize(bestPreviewSize.width, bestPreviewSize.height);
                               camera.setParameters(parameters);
                           }
                      } catch (RuntimeException e2) {
                           Log.e(TAG, "Failed to set even preview size", e2);
                      }
                 }


                // 4. Устанавливаем ориентацию дисплея камеры
                setCameraDisplayOrientation();

                // 5. Устанавливаем Surface для превью
                camera.setPreviewDisplay(cameraSurfaceHolder);

                // 6. Запускаем превью
                camera.startPreview();
                isPreviewRunning = true;
                Log.d(TAG, "Preview started.");

            } catch (IOException e) {
                Log.e(TAG, "IOException setting preview display", e);
            } catch (RuntimeException e) {
                Log.e(TAG, "RuntimeException starting preview or setting parameters", e);
                 releaseCamera(); // Освобождаем камеру в случае серьезной ошибки
            }
        } else {
            Log.e(TAG, "Camera object is null in surfaceChanged.");
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        Log.d(TAG, "Surface destroyed.");
        // Поверхность уничтожена, освобождаем камеру
        releaseCamera();
    }

    // Запуск камеры
    private void startCamera() {
         if (camera != null) {
              Log.w(TAG, "startCamera called when camera is already initialized.");
              // Возможно, нужно перезапустить? Или просто выйти. Пока выйдем.
             return;
         }

         if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
             Log.e(TAG,"startCamera called without camera permission!");
             return;
         }


        try {
            Log.d(TAG, "Attempting to open camera ID: " + currentCameraId);
            camera = Camera.open(currentCameraId);
            if (camera == null) {
                 Log.e(TAG, "Failed to open camera with ID: " + currentCameraId);
                 // Попробовать открыть камеру по умолчанию, если ID не сработал?
                 try {
                     Log.w(TAG, "Trying to open default camera (ID 0)");
                     currentCameraId = 0; // По умолчанию обычно задняя
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

            // Установка Surface для превью будет в surfaceChanged, но можно попробовать здесь
            // если поверхность уже готова
            if (cameraSurfaceHolder != null && cameraSurfaceHolder.getSurface() != null && cameraSurfaceHolder.getSurface().isValid()) {
                 try {
                     camera.setPreviewDisplay(cameraSurfaceHolder);
                     Log.d(TAG,"Preview display set in startCamera (surface was ready).");
                     // Запуск превью все равно лучше делать в surfaceChanged или после установки параметров
                      // Но если surfaceChanged уже был вызван, можно попробовать запустить здесь
                      // Однако, лучше дождаться surfaceChanged для установки правильных размеров
                 } catch (IOException e) {
                     Log.e(TAG, "IOException setting preview display in startCamera", e);
                      releaseCamera(); // Освобождаем, если не можем установить дисплей
                 }
            } else {
                 Log.d(TAG, "Surface not ready in startCamera, preview display will be set in surfaceChanged.");
            }

        } catch (RuntimeException e) {
            Log.e(TAG, "RuntimeException opening camera ID: " + currentCameraId, e);
            Toast.makeText(this, "Cannot access camera. Is it in use?", Toast.LENGTH_LONG).show();
            camera = null; // Убедимся, что ссылка на камеру пуста
        }
    }

    // Освобождение камеры
    private void releaseCamera() {
        if (camera != null) {
            Log.d(TAG, "Releasing camera...");
            if (isPreviewRunning) {
                try {
                    camera.stopPreview();
                    Log.d(TAG, "Preview stopped before release.");
                } catch (Exception e) {
                    // Игнорируем ошибки при остановке, все равно освобождаем
                     Log.e(TAG,"Error stopping preview during release", e);
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
        } else {
             // Log.d(TAG, "releaseCamera called but camera was already null.");
        }
    }


    // Получение наилучшего размера превью для заданных размеров SurfaceView
     private Camera.Size getBestPreviewSize(Camera.Parameters parameters, int surfaceWidth, int surfaceHeight) {
         List<Camera.Size> supportedPreviewSizes = parameters.getSupportedPreviewSizes();
         if (supportedPreviewSizes == null || supportedPreviewSizes.isEmpty()) {
             Log.e(TAG, "Camera has no supported preview sizes!");
             return null;
         }

         Camera.Size bestSize = null;
         double targetRatio = (double) surfaceWidth / surfaceHeight;
          // Если ориентация портретная, инвертируем соотношение для сравнения с размерами камеры
          // (Камера обычно отдает размеры в своей "ландшафтной" ориентации)
          int currentOrientation = getResources().getConfiguration().orientation;
          if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) {
               targetRatio = (double) surfaceHeight / surfaceWidth;
               Log.d(TAG,"Portrait orientation detected, using target ratio: " + targetRatio + " (H/W)");
          } else {
              Log.d(TAG,"Landscape orientation detected, using target ratio: " + targetRatio + " (W/H)");
          }


         double minDiff = Double.MAX_VALUE;
         int targetHeight = Math.min(surfaceWidth, surfaceHeight); // Минимальная желаемая высота (или ширина в портрете)


         // Сортируем по убыванию размера для предпочтения больших разрешений
         supportedPreviewSizes.sort((a, b) -> Integer.compare(b.width * b.height, a.width * a.height));

         Log.d(TAG, "Supported preview sizes:");
         for (Camera.Size size : supportedPreviewSizes) {
              Log.d(TAG, " - " + size.width + "x" + size.height);
         }

         for (Camera.Size size : supportedPreviewSizes) {
             double ratio = (double) size.width / size.height;
             double diff = Math.abs(ratio - targetRatio);
              Log.v(TAG, "Checking size: " + size.width + "x" + size.height + ", Ratio: " + ratio + ", Diff: " + diff);

             // Ищем размер с минимальным отличием в соотношении сторон
             if (diff < minDiff) {
                 bestSize = size;
                 minDiff = diff;
                  Log.v(TAG, "New best candidate (minDiff): " + bestSize.width + "x" + bestSize.height);
             }
              // Дополнительно проверяем, чтобы не было слишком большой разницы,
              // и размер был достаточно большим (хотя бы как targetHeight)
              // Эта проверка на ~0.01 помогает выбрать первый подходящий размер, если есть несколько с одинаковым соотношением
              else if (Math.abs(diff - minDiff) < 0.01) {
                   // Если соотношение сторон почти такое же, выбираем больший размер
                   if (bestSize == null || size.width * size.height > bestSize.width * bestSize.height) {
                       bestSize = size;
                        Log.v(TAG, "New best candidate (larger size with similar ratio): " + bestSize.width + "x" + bestSize.height);
                   }
              }
         }

          // Если лучший размер все еще не найден (маловероятно), просто берем первый из списка
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


     // Установка правильной ориентации превью камеры
     private void setCameraDisplayOrientation() {
         if (camera == null) {
              Log.e(TAG, "Cannot set display orientation, camera is null.");
             return;
         }

         Camera.CameraInfo info = new Camera.CameraInfo();
         Camera.getCameraInfo(currentCameraId, info);

         WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
         if (windowManager == null) {
              Log.e(TAG,"WindowManager is null, cannot get display rotation.");
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
             result = (360 - result) % 360; // Компенсация зеркалирования
         } else { // Задняя камера
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


    // --- Методы жизненного цикла Activity ---

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        // Если камера не запущена и поверхность готова, запускаем камеру
        if (camera == null && cameraSurfaceHolder != null && cameraSurfaceHolder.getSurface() != null && cameraSurfaceHolder.getSurface().isValid()) {
            Log.d(TAG, "Resuming: Camera was null and surface is valid, starting camera.");
             if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                 Log.w(TAG,"Resuming: Cannot start camera, permission not granted.");
            }
        } else if (camera != null && !isPreviewRunning && cameraSurfaceHolder != null && cameraSurfaceHolder.getSurface() != null && cameraSurfaceHolder.getSurface().isValid()){
            // Камера есть, но превью не идет (например, после surfaceChanged с ошибкой)
            Log.d(TAG, "Resuming: Camera exists but preview not running, attempting restart via surfaceChanged logic.");
             // Имитируем surfaceChanged, чтобы перенастроить и запустить превью
             surfaceChanged(cameraSurfaceHolder, 0, cameraSurfaceView.getWidth(), cameraSurfaceView.getHeight());
        }

        // Если есть URI изображения, но Bitmap был освобожден (например, после onPause), перезагружаем
        if (currentImageUri != null && originalBitmap == null) {
             Log.d(TAG, "Resuming: Image URI exists but bitmap is null, reloading image.");
            loadImage(currentImageUri, true); // Перезагружаем, применяя сохраненную матрицу
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause");
        // Освобождаем камеру при приостановке Activity
        releaseCamera();
        // Не освобождаем Bitmap здесь, чтобы он мог быть восстановлен в onResume
        // Однако, система может его уничтожить, если не хватает памяти.
        // Сохранение состояния в onSaveInstanceState позаботится о URI.
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        // Гарантированно освобождаем камеру
        releaseCamera();
        // Освобождаем Bitmap
        if (originalBitmap != null && !originalBitmap.isRecycled()) {
            Log.d(TAG, "Recycling bitmap in onDestroy.");
            originalBitmap.recycle();
            originalBitmap = null;
        }
    }

    // --- Сохранение состояния ---

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        Log.d(TAG, "Saving instance state...");

        // Сохраняем URI изображения
        if (currentImageUri != null) {
            outState.putString(KEY_IMAGE_URI, currentImageUri.toString());
            Log.d(TAG, "Saved URI: " + currentImageUri.toString());
        } else {
            Log.d(TAG, "No image URI to save.");
        }

        // Сохраняем параметры трансформации, только если изображение загружено
        if (originalBitmap != null) {
             outState.putFloat(KEY_SCALE_FACTOR, scaleFactor);
             outState.putFloat(KEY_ROTATION_ANGLE, rotationAngle);

             // Сохраняем всю матрицу
             float[] matrixValues = new float[9];
             matrix.getValues(matrixValues);
             outState.putFloatArray(KEY_MATRIX_VALUES, matrixValues);

             Log.d(TAG, "Saved scaleFactor: " + scaleFactor);
             Log.d(TAG, "Saved rotationAngle: " + rotationAngle);
             Log.d(TAG, "Saved Matrix values: TX=" + matrixValues[Matrix.MTRANS_X] + ", TY=" + matrixValues[Matrix.MTRANS_Y] + ", SX=" + matrixValues[Matrix.MSCALE_X] );
        } else {
             Log.d(TAG,"No bitmap, not saving transformation state.");
        }

        // ID камеры сохранять не обязательно, если мы всегда начинаем с задней
        // Но если бы была кнопка переключения, его бы стоило сохранить.
        // outState.putInt("cameraId", currentCameraId);
    }


     // --- Метод переключения камеры (не используется UI в описании, но реализован) ---
     public void switchCamera() {
         if (camera == null) {
             Log.w(TAG,"Cannot switch camera, camera is null.");
             // Попробовать запустить камеру по умолчанию?
             checkCameraPermission(); // Запросить разрешение и попробовать запустить
             return;
         }

         int numberOfCameras = Camera.getNumberOfCameras();
         if (numberOfCameras < 2) {
             Toast.makeText(this, "Only one camera available", Toast.LENGTH_SHORT).show();
             Log.w(TAG,"Switch camera called, but only one camera detected.");
             return;
         }

         Log.d(TAG, "Switching camera...");
         releaseCamera(); // Освобождаем текущую

         // Меняем ID
         currentCameraId = (currentCameraId == Camera.CameraInfo.CAMERA_FACING_BACK)
                 ? Camera.CameraInfo.CAMERA_FACING_FRONT
                 : Camera.CameraInfo.CAMERA_FACING_BACK;

         Log.d(TAG, "Switched camera ID to: " + currentCameraId);

         // Запускаем новую камеру (если поверхность готова)
         if (cameraSurfaceHolder != null && cameraSurfaceHolder.getSurface() != null && cameraSurfaceHolder.getSurface().isValid()) {
             startCamera();
             // После startCamera, surfaceChanged должен быть вызван автоматически или вручную,
             // чтобы настроить параметры для новой камеры
             // Даем системе время на обработку или вызываем вручную?
              // Пробуем вызвать surfaceChanged явно, т.к. размеры могли не измениться
               if (camera != null) { // Убедимся, что startCamera сработал
                   Log.d(TAG,"Manually calling surfaceChanged after camera switch.");
                   surfaceChanged(cameraSurfaceHolder, 0, cameraSurfaceView.getWidth(), cameraSurfaceView.getHeight());
               }
         } else {
              Log.w(TAG,"Surface not ready after camera switch, camera will start later via surfaceCreated/Changed.");
         }
     }
}
