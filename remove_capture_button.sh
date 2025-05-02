#!/bin/bash

echo "--- Начало удаления логики 'Capture Image' ---"

# --- Определяем пути к файлам ---
ACTIVITY_MAIN_LAYOUT="app/src/main/res/layout/activity_main.xml"
MAIN_ACTIVITY_JAVA="app/src/main/java/com/example/helloworld/MainActivity.java"
STRINGS_XML="app/src/main/res/values/strings.xml"

# --- Проверка существования файлов ---
if [ ! -f "$ACTIVITY_MAIN_LAYOUT" ]; then
  echo "ОШИБКА: Файл $ACTIVITY_MAIN_LAYOUT не найден!"
  exit 1
fi
if [ ! -f "$MAIN_ACTIVITY_JAVA" ]; then
  echo "ОШИБКА: Файл $MAIN_ACTIVITY_JAVA не найден!"
  exit 1
fi
if [ ! -f "$STRINGS_XML" ]; then
  echo "ОШИБКА: Файл $STRINGS_XML не найден!"
  exit 1
fi

# --- Шаг 1: Удаление Button из activity_main.xml ---
echo "1. Удаление кнопки 'captureButton' из $ACTIVITY_MAIN_LAYOUT..."
# Удаляем блок <Button> с id="@+id/captureButton" до закрывающего '/>'
sed -i '/android:id="@+id\/captureButton"/,/ \/>/d' "$ACTIVITY_MAIN_LAYOUT" || { echo "Ошибка при удалении кнопки из XML."; exit 1; }
echo "   Кнопка из XML удалена."

# --- Шаг 2: Удаление строки из strings.xml ---
echo "2. Удаление строки 'capture_image' из $STRINGS_XML..."
sed -i '/<string name="capture_image">/d' "$STRINGS_XML" || { echo "Ошибка при удалении строки."; exit 1; }
echo "   Строка удалена."

# --- Шаг 3: Удаление кода из MainActivity.java ---
echo "3. Модификация $MAIN_ACTIVITY_JAVA..."

# Удаление объявления переменной captureButton
echo "   - Удаление объявления captureButton..."
sed -i '/private Button captureButton;/d' "$MAIN_ACTIVITY_JAVA" || { echo "Ошибка при удалении объявления captureButton."; exit 1; }

# Удаление findViewById для captureButton
echo "   - Удаление findViewById(R.id.captureButton)..."
sed -i '/captureButton = findViewById(R.id.captureButton);/d' "$MAIN_ACTIVITY_JAVA" || { echo "Ошибка при удалении findViewById."; exit 1; }

# Удаление setOnClickListener для captureButton
echo "   - Удаление captureButton.setOnClickListener..."
sed -i '/captureButton.setOnClickListener(v -> captureImage());/d' "$MAIN_ACTIVITY_JAVA" || { echo "Ошибка при удалении setOnClickListener."; exit 1; }

# Удаление метода captureImage()
echo "   - Удаление метода captureImage()..."
# Удаляем блок от сигнатуры метода до закрывающей фигурной скобки на отдельной строке
sed -i '/private void captureImage() {/,/^\s*}/d' "$MAIN_ACTIVITY_JAVA" || { echo "Ошибка при удалении метода captureImage."; exit 1; }

# Удаление метода imageToBitmap()
echo "   - Удаление метода imageToBitmap()..."
sed -i '/private Bitmap imageToBitmap(Image image) {/,/^\s*}/d' "$MAIN_ACTIVITY_JAVA" || { echo "Ошибка при удалении метода imageToBitmap."; exit 1; }

# Удаление метода processCapturedImage()
echo "   - Удаление метода processCapturedImage()..."
# Указываем тип аргумента для большей точности
sed -i '/private void processCapturedImage(final Bitmap capturedBitmap) {/,/^\s*});\s*}/d' "$MAIN_ACTIVITY_JAVA" || { echo "Ошибка при удалении метода processCapturedImage."; exit 1; }

# Удаление объявления переменной imageReader
echo "   - Удаление объявления imageReader..."
sed -i '/private ImageReader imageReader;/d' "$MAIN_ACTIVITY_JAVA" || { echo "Ошибка при удалении объявления imageReader."; exit 1; }

# Удаление блока инициализации ImageReader в openCamera()
echo "   - Удаление инициализации imageReader в openCamera()..."
sed -i '/if (imageReader != null) imageReader.close();/,/}, backgroundHandler);/d' "$MAIN_ACTIVITY_JAVA" || { echo "Ошибка при удалении инициализации ImageReader."; exit 1; }


# Удаление добавления поверхности ImageReader в createCameraPreviewSession()
echo "   - Удаление surfaces.add(imageReader.getSurface())..."
# Используем # как разделитель в sed из-за слешей в паттерне
sed -i '\#surfaces.add(imageReader.getSurface());#d' "$MAIN_ACTIVITY_JAVA" || { echo "Ошибка при удалении surfaces.add(imageReader)."; exit 1; }
# Удаляем также комментарий, связанный с этим
sed -i '/\/\/ Добавляем поверхность ImageReader только если он есть/d' "$MAIN_ACTIVITY_JAVA" || true # Игнорируем ошибку, если комментарий уже удален

# Удаление закрытия ImageReader в closeCamera()
echo "   - Удаление закрытия imageReader в closeCamera()..."
# Удаляем блок if (imageReader != null) { ... }
sed -i '/if (imageReader != null) {/,/Log.d(TAG, "ImageReader closed.");/d' "$MAIN_ACTIVITY_JAVA" || { echo "Ошибка при удалении закрытия imageReader (блок)."; exit 1; }
sed -i '/imageReader = null;/d' "$MAIN_ACTIVITY_JAVA" # Удаляем присвоение null отдельно

# Удаление неиспользуемых импортов
echo "   - Удаление неиспользуемых импортов..."
sed -i '/import android.media.Image;/d' "$MAIN_ACTIVITY_JAVA" || true
sed -i '/import android.media.ImageReader;/d' "$MAIN_ACTIVITY_JAVA" || true
sed -i '/import java.nio.ByteBuffer;/d' "$MAIN_ACTIVITY_JAVA" || true
sed -i '/import android.hardware.camera2.TotalCaptureResult;/d' "$MAIN_ACTIVITY_JAVA" || true

# Удаление видимости captureButton в updateControlsVisibility
echo "   - Удаление captureButton.setVisibility в updateControlsVisibility..."
sed -i '/captureButton.setVisibility(visibility);/d' "$MAIN_ACTIVITY_JAVA" || { echo "Ошибка при удалении captureButton.setVisibility."; exit 1; }

echo "   Модификация MainActivity.java завершена."

echo "--- Удаление логики 'Capture Image' завершено ---"
echo "Пожалуйста, проверьте изменения и пересоберите проект."
