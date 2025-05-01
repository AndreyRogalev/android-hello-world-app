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
                .inflate(android.R.layout.simple_list_item_2, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.text1.setText(layers[position]);
        holder.checkBox.setChecked(visibility[position]);
        holder.checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            visibility[position] = isChecked;
            if (listener != null) {
                listener.onLayerVisibilityChanged(position, isChecked);
            }
        });
    }

    @Override
    public int getItemCount() {
        return layers.length;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView text1;
        CheckBox checkBox;

        ViewHolder(View itemView) {
            super(itemView);
            text1 = itemView.findViewById(android.R.id.text1);
            checkBox = new CheckBox(itemView.getContext());
            ViewGroup parent = (ViewGroup) text1.getParent();
            parent.addView(checkBox);
        }
    }
}
