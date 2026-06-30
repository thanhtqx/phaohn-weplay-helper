package com.phaohn.spyhelper

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.phaohn.spyhelper.databinding.ItemWordPairBinding

class PairsAdapter(
    private val onEdit: (WordPair) -> Unit,
    private val onDelete: (WordPair) -> Unit,
    private val onReport: (WordPair) -> Unit,
) : RecyclerView.Adapter<PairsAdapter.VH>() {

    private val items = mutableListOf<WordPair>()

    fun submit(list: List<WordPair>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemWordPairBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding, onEdit, onDelete, onReport)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    class VH(
        private val binding: ItemWordPairBinding,
        private val onEdit: (WordPair) -> Unit,
        private val onDelete: (WordPair) -> Unit,
        private val onReport: (WordPair) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(pair: WordPair) {
            binding.civilianText.text = pair.civilianWord
            binding.spyText.text = pair.spyWord
            binding.root.setBackgroundResource(
                if (bindingAdapterPosition % 2 == 0) R.drawable.bg_table_row_grid
                else R.drawable.bg_table_row_grid_alt
            )
            binding.btnReport.setOnClickListener { onReport(pair) }
            binding.btnEdit.setOnClickListener { onEdit(pair) }
            binding.btnDelete.setOnClickListener { onDelete(pair) }
        }
    }
}