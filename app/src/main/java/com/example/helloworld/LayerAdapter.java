package com.example.helloworld;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckedTextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class LayerAdapter extends RecyclerView.Adapter<LayerAdapter.ViewHolder> {

    private final String[] layers;
    private final boolean[] visibility;
    private final OnLayerVisibilityChangedListener listener;

    public interface OnLayerVisibilityChangedListener {
        void onLayerVisibilityChanged(int position, boolean isVisible);
    }

    public LayerAdapter(String[] layers, boolean[] visibility, OnLayerVisibilityChangedListener listener) {
        this.layers = layers;
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
        holder.checkedTextView.setText(layers[position]);
        holder.checkedTextView.setChecked(visibility[position]);
        holder.checkedTextView.setOnClickListener(v -> {
            visibility[position] = !visibility[position];
            holder.checkedTextView.setChecked(visibility[position]);
            if (listener != null) {
                listener.onLayerVisibilityChanged(position, visibility[position]);
            }
        });
    }

    @Override
    public int getItemCount() {
        return layers.length;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        final CheckedTextView checkedTextView;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            checkedTextView = itemView.findViewById(android.R.id.text1);
        }
    }
}
