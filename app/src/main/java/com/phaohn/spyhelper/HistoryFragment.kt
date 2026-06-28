package com.phaohn.spyhelper

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.phaohn.spyhelper.databinding.FragmentHistoryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: WordRepository
    private lateinit var adapter: HistoryAdapter

    private lateinit var exportLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            uri?.let { exportToUri(it) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        repository = PhaoHNApp.repo(requireActivity().application)
        adapter = HistoryAdapter(onDelete = { entry -> confirmDeleteOne(entry) })
        binding.recyclerHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerHistory.adapter = adapter
        binding.btnClearHistory.setOnClickListener { confirmClear() }
        binding.btnExportHistory.setOnClickListener { pickExportFile() }
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        loadHistory()
    }

    fun loadHistory() {
        if (_binding == null) return
        lifecycleScope.launch {
            val list = repository.recentHistory()
            adapter.submit(list)
            val empty = list.isEmpty()
            binding.historyEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            binding.recyclerHistory.visibility = if (empty) View.GONE else View.VISIBLE
            binding.btnClearHistory.isEnabled = !empty
            binding.btnExportHistory.isEnabled = !empty
            binding.historyStats.text = getString(R.string.history_stats_total, list.size)
        }
    }

    private fun pickExportFile() {
        val name = "phaohn_lichsu_${exportTimestamp()}.csv"
        exportLauncher.launch(name)
    }

    private fun exportToUri(uri: Uri) {
        lifecycleScope.launch {
            val history = repository.recentHistory(500)
            val csv = CsvHelper.exportHistory(history)
            withContext(Dispatchers.IO) {
                requireContext().contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(csv.toByteArray(Charsets.UTF_8))
                }
            }
            Toast.makeText(requireContext(), R.string.export_ok, Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportTimestamp(): String {
        return SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
    }

    private fun confirmDeleteOne(entry: LookupHistory) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.history_delete_one_confirm)
            .setPositiveButton(R.string.delete) { d, _ ->
                lifecycleScope.launch {
                    repository.deleteHistoryItem(entry.id)
                    Toast.makeText(requireContext(), R.string.deleted_ok, Toast.LENGTH_SHORT).show()
                    loadHistory()
                    (activity as? MainActivity)?.refreshHomeUi()
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.history_clear)
            .setMessage(R.string.history_clear_confirm)
            .setPositiveButton(R.string.delete) { d, _ ->
                lifecycleScope.launch {
                    repository.clearHistory()
                    Toast.makeText(requireContext(), R.string.history_cleared, Toast.LENGTH_SHORT).show()
                    loadHistory()
                    (activity as? MainActivity)?.refreshHomeUi()
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance() = HistoryFragment()
    }
}