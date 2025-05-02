#!/bin/bash

# Убедимся, что мы в корневой директории проекта
PROJECT_DIR="/root/android-hello-world-app"
cd $PROJECT_DIR || { echo "Директория проекта не найдена!"; exit 1; }

# Создание или обновление strings.xml
echo "Создание/обновление strings.xml..."
mkdir -p app/src/main/res/values
cat > app/src/main/res/values/strings.xml << 'EOL'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">HelloWorld</string>
    <string name="layer_selection_title">Select Pencil Layers</string>
    <string name="pick_image">Pick Image</string>
    <string name="pencil_mode">Pencil Mode</string>
    <string name="select_layers">Select Layers</string>
    <string name="show_controls">Show Controls</string>
    <string name="hide_image">Hide Image</string>
    <string name="save_parameters">Save Parameters</string>
    <string name="load_parameters">Load Parameters</string>
    <string name="switch_camera">Switch Camera</string>
    <string name="capture_image">Capture Image</string>
</resources>
EOL

# Очистка проекта
echo "Очистка проекта..."
./gradlew clean

# Пересборка проекта
echo "Пересборка проекта..."
./gradlew assembleDebug

echo "Исправление завершено!"
