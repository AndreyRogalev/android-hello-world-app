package com.example.helloworld;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox; // Используем CheckBox
import android.widget.TextView;  // Используем TextView
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class LayerAdapter extends RecyclerView.Adapter<LayerAdapter.ViewHolder> {

    private final String[] layers;
    private final boolean[] visibility;
    private final OnLayerVisibilityChangedListener listener;

    // Интерфейс для обратного вызова при изменении видимости слоя
    public interface OnLayerVisibilityChangedListener {
        void onLayerVisibilityChanged(int position, boolean isVisible);
    }

    // Конструктор адаптера
    public LayerAdapter(String[] layers, boolean[] visibility, OnLayerVisibilityChangedListener listener) {
        this.layers = layers;
        this.visibility = visibility;
        this.listener = listener;
    }

    // Создание нового ViewHolder (используем item_layer.xml)
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Используем ваш кастомный макет item_layer.xml
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_layer, parent, false); // *** ИЗМЕНЕНО ЗДЕСЬ ***
        return new ViewHolder(view);
    }

    // Привязка данных к ViewHolder
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        // Устанавливаем текст слоя в TextView
        holder.layerText.setText(layers[position]); // *** ИЗМЕНЕНО ЗДЕСЬ ***

        // Сначала удаляем слушатель, чтобы он не сработал при установке состояния
        holder.layerCheckBox.setOnCheckedChangeListener(null);
        // Устанавливаем состояние CheckBox
        holder.layerCheckBox.setChecked(visibility[position]); // *** ИЗМЕНЕНО ЗДЕСЬ ***

        // Устанавливаем слушатель изменения состояния CheckBox
        holder.layerCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> { // *** ИЗМЕНЕНО ЗДЕСЬ ***
            // Обновляем массив видимости
            int currentPosition = holder.getAdapterPosition(); // Получаем актуальную позицию
             if (currentPosition != RecyclerView.NO_POSITION) { // Проверяем, что позиция валидна
                 visibility[currentPosition] = isChecked;
                 // Уведомляем MainActivity через интерфейс
                 if (listener != null) {
                     listener.onLayerVisibilityChanged(currentPosition, isChecked);
                 }
             }
        });
    }

    // Возвращаем общее количество элементов
    @Override
    public int getItemCount() {
        // Проверяем на null на всякий случай
        return (layers != null) ? layers.length : 0;
    }

    // Класс ViewHolder, хранящий ссылки на View одного элемента списка
    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView layerText;     // *** ИЗМЕНЕНО ЗДЕСЬ *** Ссылка на TextView из item_layer.xml
        CheckBox layerCheckBox; // *** ИЗМЕНЕНО ЗДЕСЬ *** Ссылка на CheckBox из item_layer.xml

        ViewHolder(View itemView) {
            super(itemView);
            // Находим View по их ID из item_layer.xml
            layerText = itemView.findViewById(R.id.layerText);       // *** ИЗМЕНЕНО ЗДЕСЬ ***
            layerCheckBox = itemView.findViewById(R.id.layerCheckBox); // *** ИЗМЕНЕНО ЗДЕСЬ ***
        }
    }
}
