package com.otterhub.app

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.otterhub.app.databinding.ItemFileBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileListAdapter(
    private val onClick: (FileRow) -> Unit,
    private val onLongClick: (FileRow) -> Unit,
) : RecyclerView.Adapter<FileListAdapter.Holder>() {

    private companion object {
        val DATE_FORMAT = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.getDefault())
    }

    private val rows = ArrayList<FileRow>()

    fun submit(newRows: List<FileRow>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    fun remove(key: String) {
        val index = rows.indexOfFirst { it.key == key }
        if (index >= 0) {
            rows.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    class Holder(val binding: ItemFileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        val ctx: Context = holder.itemView.context
        holder.binding.itemTitle.text = row.displayName
        holder.binding.itemSubtitle.text = buildString {
            append(typeLabel(ctx, row.type))
            if (row.size > 0L) append(" · ").append(formatSize(row.size))
            if (row.uploadedAt > 0L) append(" · ").append(DATE_FORMAT.format(Date(row.uploadedAt)))
            if (row.isPrivate) append(" · ").append(ctx.getString(R.string.badge_private))
        }
        holder.binding.itemIcon.setImageResource(
            when (row.type) {
                "img" -> R.drawable.ic_type_img
                "video" -> R.drawable.ic_type_video
                "audio" -> R.drawable.ic_type_audio
                else -> R.drawable.ic_type_doc
            }
        )
        holder.itemView.setOnClickListener { onClick(row) }
        holder.itemView.setOnLongClickListener {
            onLongClick(row)
            true
        }
    }

    private fun typeLabel(ctx: Context, type: String): String = ctx.getString(
        when (type) {
            "img" -> R.string.filter_img
            "video" -> R.string.filter_video
            "audio" -> R.string.filter_audio
            else -> R.string.filter_doc
        }
    )
}
