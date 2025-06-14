// WardrobeAdapter.kt (最终修正版)
package com.example.smartwardrobe.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.smartwardrobe.R

class WardrobeAdapter(
    private var items: MutableList<WardrobeItem>,
    private val listener: OnItemInteractionListener
) : RecyclerView.Adapter<WardrobeAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val nameText: TextView = view.findViewById(R.id.tvName)
        val detailsText: TextView = view.findViewById(R.id.tvDetails)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_clothing, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.nameText.text = item.name
        holder.detailsText.text = "${item.category} • ${item.style} • ${item.thickness}"

        // 点击整个 item 触发编辑
        holder.itemView.setOnClickListener {
            listener.onEditClick(item)
        }


    }

    override fun getItemCount() = items.size
}