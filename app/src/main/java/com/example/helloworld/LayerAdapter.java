package com.example.helloworld;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class LayerAdapter extends RecyclerView.Adapter<LayerAdapter.ViewHolder> {
    private final String[] hardnessLevels;
    private final boolean[] visibility;
    private final OnLayerVisibilityChangedListener listener;

    public interface OnLayerVisibilityChangedListener {
        void onLayerVisibilityChanged(int position, boolean isVisible);
    }

    public LayerAdapter(String[] hardnessLevels, boolean[] visibility, OnLayerVisibilityChangedListener listener) {
        this.hardnessLevels = hardnessLevels;
        this.visibility = visibility;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(android.R.layout.simple_list_item_multiple_choice, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.checkBox.setText(hardnessLevels[position]);
        holder.checkBox.setChecked(visibility[position]);
        holder.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            visibility[position] = isChecked;
            listener.onLayerVisibilityChanged(position, isChecked);
        });
    }

    @Override
    public int getItemCount() {
        return hardnessLevels.length;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        CheckBox checkBox;

        ViewHolder(View itemView) {
            super(itemView);
            checkBox = itemView.findViewById(android.R.id.text1);
        }
    }
}
