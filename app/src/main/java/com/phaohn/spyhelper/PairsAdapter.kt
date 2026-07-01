package com.phaohn.spyhelper

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.phaohn.spyhelper.databinding.ItemWordPairBinding

class PairsAdapter(
    private val onEdit: (WordPair) -> Unit,
    private val onDelete: (WordPair) -> Unit,
    private val onReport: (WordPair) -> Unit,
) : ListAdapter<WordPair, PairsAdapter.VH>(DIFF) {

    fun submit(list: List<WordPair>) {
        submitList(list.toList())
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemWordPairBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding, onEdit, onDelete, onReport)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    class VH(
        private val binding: ItemWordPairBinding,
        private val onEdit: (WordPair) -> Unit,
        private val onDelete: (WordPair) -> Unit,
        private val onReport: (WordPair) -> Unit,
    ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {
        fun bind(pair: WordPair) {
            binding.civilianText.text = pair.civilianWord
            binding.spyText.text = pair.spyWord
            binding.pendingBadge.visibility =
                if (pair.isPendingApproval) android.view.View.VISIBLE
                else android.view.View.GONE
            binding.root.setBackgroundResource(
                if (bindingAdapterPosition % 2 == 0) R.drawable.bg_table_row_grid
                else R.drawable.bg_table_row_grid_alt
            )
            binding.btnReport.setOnClickListener { onReport(pair) }
            binding.btnEdit.setOnClickListener { onEdit(pair) }
            binding.btnDelete.setOnClickListener { onDelete(pair) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<WordPair>() {
            override fun areItemsTheSame(oldItem: WordPair, newItem: WordPair): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: WordPair, newItem: WordPair): Boolean =
                oldItem == newItem
        }
    }
}