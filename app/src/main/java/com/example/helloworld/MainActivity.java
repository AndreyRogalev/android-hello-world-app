package com.example.helloworld;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Switch;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.List;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private Camera camera;
    private SurfaceView cameraSurfaceView;
    private SurfaceHolder surfaceHolder;
    private ImageView imageView;
    private SeekBar transparencySeekBar;
    private Button pickImageButton, layerSelectButton, saveParametersButton, loadParametersButton, switchCameraButton;
    private Switch pencilModeSwitch;
    private CheckBox controlsVisibilityCheckbox, hideImageCheckbox;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;
    private boolean isFrontCamera = false; // Для переключения камеры

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Инициализация элементов интерфейса
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

        // Настройка SurfaceHolder для предпросмотра камеры
        surfaceHolder = cameraSurfaceView.getHolder();
        surfaceHolder.addCallback(this);
        surfaceHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);

        // Настройка кнопки переключения камеры
        switchCameraButton.setOnClickListener(v -> {
            isFrontCamera = !isFrontCamera;
            if (camera != null) {
                camera.stopPreview();
                camera.release();
                camera = null;
            }
            initializeCamera();
        });

        // Запрос разрешения на использование камеры
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
        } else {
            initializeCamera();
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d("MainActivity", "Surface Created");
        try {
            if (camera == null) {
                initializeCamera();
            }
            camera.setPreviewDisplay(holder);
            camera.startPreview();
            Log.d("MainActivity", "Preview Started");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (surfaceHolder.getSurface() != null) {
            try {
                camera.stopPreview();
                camera.setPreviewDisplay(holder);
                camera.startPreview();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (camera != null) {
            camera.stopPreview();
            camera.release();
            camera = null;
        }
    }

    private void initializeCamera() {
        if (camera == null) {
            try {
                int cameraId = isFrontCamera ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK;
                camera = Camera.open(cameraId);
                System.out.println("cam " + (isFrontCamera ? "front" : "back"));
                Camera.Parameters parameters = camera.getParameters();
                List<Camera.Size> sizes = parameters.getSupportedPreviewSizes();
                Camera.Size optimalSize = sizes.get(0); // Выбор первого доступного размера
                parameters.setPreviewSize(optimalSize.width, optimalSize.height);
                camera.setParameters(parameters);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeCamera();
            } else {
                Log.e("MainActivity", "Camera permission denied");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (camera != null) {
            camera.stopPreview();
            camera.release();
            camera = null;
        }
    }
}
