package com.example.helloworld;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class LayerAdapter extends RecyclerView.Adapter<LayerAdapter.LayerViewHolder> {

    private final String[] layers;
    private final boolean[] layerVisibility;
    private final OnLayerVisibilityChangedListener listener;

    public interface OnLayerVisibilityChangedListener {
        void onLayerVisibilityChanged(int position, boolean isVisible);
    }

    public LayerAdapter(String[] layers, boolean[] layerVisibility, OnLayerVisibilityChangedListener listener) {
        this.layers = layers;
        this.layerVisibility = layerVisibility;
        this.listener = listener;
    }

    @NonNull
    @Override
    public LayerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_layer, parent, false);
        return new LayerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LayerViewHolder holder, int position) {
        holder.bind(layers[position], layerVisibility[position], position);
    }

    @Override
    public int getItemCount() {
        return layers.length;
    }

    class LayerViewHolder extends RecyclerView.ViewHolder {
        private final CheckBox checkBox;

        LayerViewHolder(@NonNull View itemView) {
            super(itemView);
            checkBox = itemView.findViewById(R.id.layerCheckBox);
        }

        void bind(String layerName, boolean isVisible, int position) {
            checkBox.setText(layerName);
            checkBox.setChecked(isVisible);
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                layerVisibility[position] = isChecked;
                if (listener != null) {
                    listener.onLayerVisibilityChanged(position, isChecked);
                }
            });
        }
    }
}
