package com.example.helloworld;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
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

    // Создание нового ViewHolder (вызывается RecyclerView)
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // Используем ваш макет item_layer.xml
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_layer, parent, false);
        return new ViewHolder(view);
    }

    // Привязка данных к ViewHolder (вызывается RecyclerView)
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        // Устанавливаем текст слоя
        holder.layerText.setText(layers[position]);

        // Важно: Сначала удаляем слушатель, чтобы он не сработал при установке состояния
        holder.layerCheckBox.setOnCheckedChangeListener(null);
        // Устанавливаем состояние CheckBox
        holder.layerCheckBox.setChecked(visibility[position]);

        // Устанавливаем слушатель изменения состояния CheckBox
        holder.layerCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // Обновляем массив видимости
            visibility[position] = isChecked;
            // Уведомляем MainActivity через интерфейс
            if (listener != null) {
                listener.onLayerVisibilityChanged(holder.getAdapterPosition(), isChecked); // Используем getAdapterPosition() для надежности
            }
        });
    }

    // Возвращаем общее количество элементов
    @Override
    public int getItemCount() {
        return layers.length;
    }

    // Класс ViewHolder, хранящий ссылки на View одного элемента списка
    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView layerText;     // Ссылка на TextView из item_layer.xml
        CheckBox layerCheckBox; // Ссылка на CheckBox из item_layer.xml

        ViewHolder(View itemView) {
            super(itemView);
            // Находим View по их ID из item_layer.xml
            layerText = itemView.findViewById(R.id.layerText);
            layerCheckBox = itemView.findViewById(R.id.layerCheckBox);
        }
    }
}
