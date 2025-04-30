package com.example.helloworld;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class LayerAdapter extends RecyclerView.Adapter<LayerAdapter.LayerViewHolder> {

    private final String[] pencilHardness;
    private final boolean[] layerVisibility;
    private final OnLayerVisibilityChangedListener listener;

    public interface OnLayerVisibilityChangedListener {
        void onLayerVisibilityChanged(int position, boolean isVisible);
    }

    public LayerAdapter(String[] pencilHardness, boolean[] layerVisibility, OnLayerVisibilityChangedListener listener) {
        this.pencilHardness = pencilHardness;
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
        holder.layerText.setText(pencilHardness[position]);
        holder.layerCheckBox.setChecked(layerVisibility[position]);
        holder.layerCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            layerVisibility[position] = isChecked;
            if (listener != null) {
                listener.onLayerVisibilityChanged(position, isChecked);
            }
        });
    }

    @Override
    public int getItemCount() {
        return pencilHardness.length;
    }

    static class LayerViewHolder extends RecyclerView.ViewHolder {
        TextView layerText;
        CheckBox layerCheckBox;

        LayerViewHolder(@NonNull View itemView) {
            super(itemView);
            layerText = itemView.findViewById(R.id.layerText);
            layerCheckBox = itemView.findViewById(R.id.layerCheckBox);
        }
    }
}
