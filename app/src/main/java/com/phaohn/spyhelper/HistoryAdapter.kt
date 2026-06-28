package com.phaohn.spyhelper

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.phaohn.spyhelper.databinding.ItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val onDelete: (LookupHistory) -> Unit,
) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    private val items = mutableListOf<LookupHistory>()
    private val timeFmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

    fun submit(list: List<LookupHistory>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding, onDelete)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position], position + 1)

    override fun getItemCount() = items.size

    inner class VH(
        private val binding: ItemHistoryBinding,
        private val onDelete: (LookupHistory) -> Unit,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: LookupHistory, stt: Int) {
            binding.historyStt.text = stt.toString()
            binding.historyTime.text = timeFmt.format(Date(entry.playedAt))
            binding.historyMyWord.text = entry.myWord
            val others = entry.otherWords.split("|").filter { it.isNotBlank() }
            binding.historyOthers.text = if (others.isEmpty()) {
                binding.root.context.getString(R.string.history_no_other)
            } else {
                others.joinToString(", ")
            }
            binding.root.setBackgroundResource(
                if (bindingAdapterPosition % 2 == 0) R.drawable.bg_table_row_grid
                else R.drawable.bg_table_row_grid_alt
            )
            binding.btnDeleteHistory.setOnClickListener { onDelete(entry) }
        }
    }
}