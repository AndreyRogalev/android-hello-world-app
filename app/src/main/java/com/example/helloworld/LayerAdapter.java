package com.example.helloworld;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class LayerAdapter extends RecyclerView.Adapter<LayerAdapter.LayerViewHolder> {

    private final String[] layerNames;
    private final boolean[] visibility;
    private final OnLayerVisibilityChangedListener listener;

    public interface OnLayerVisibilityChangedListener {
        void onLayerVisibilityChanged(int position, boolean isVisible);
    }

    public LayerAdapter(String[] layerNames, boolean[] visibility, OnLayerVisibilityChangedListener listener) {
        this.layerNames = layerNames;
        this.visibility = visibility;
        this.listener = listener;
    }

    @NonNull
    @Override
    public LayerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(android.R.layout.simple_list_item_2, parent, false);
        return new LayerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LayerViewHolder holder, int position) {
        holder.layerName.setText(layerNames[position]);
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
        return layerNames.length;
    }

    static class LayerViewHolder extends RecyclerView.ViewHolder {
        TextView layerName;
        CheckBox checkBox;

        LayerViewHolder(@NonNull View itemView) {
            super(itemView);
            layerName = itemView.findViewById(android.R.id.text1);
            checkBox = itemView.findViewById(android.R.id.text2);
            if (checkBox == null) {
                checkBox = new CheckBox(itemView.getContext());
                checkBox.setId(android.R.id.checkbox);
                ((ViewGroup) itemView).addView(checkBox);
            }
        }
    }
}
