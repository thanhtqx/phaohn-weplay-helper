package com.phaohn.spyhelper

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.phaohn.spyhelper.databinding.DialogAddWordBinding
import com.phaohn.spyhelper.databinding.FragmentWordListBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WordListFragment : Fragment() {

    private var _binding: FragmentWordListBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: WordRepository
    private lateinit var adapter: PairsAdapter
    private var searchJob: Job? = null

    private lateinit var importLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var exportLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importFromUri(it) }
        }
        exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            uri?.let { exportToUri(it) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWordListBinding.inflate(inflater, container, false)
        repository = PhaoHNApp.repo(requireActivity().application)
        adapter = PairsAdapter(
            onEdit = { pair -> showEditDialog(pair) },
            onDelete = { pair -> confirmDelete(pair) },
        )
        binding.recyclerPairs.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerPairs.adapter = adapter
        binding.recyclerPairs.setHasFixedSize(false)
        binding.btnAddWord.setOnClickListener { showAddDialog() }
        binding.btnImport.setOnClickListener { pickImportFile() }
        binding.btnExport.setOnClickListener { pickExportFile() }
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(250)
                    loadPairs(s?.toString().orEmpty())
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadPairs(binding.searchInput.text?.toString().orEmpty())
    }

    override fun onResume() {
        super.onResume()
        loadPairs(binding.searchInput.text?.toString().orEmpty())
    }

    fun loadPairs(query: String = "") {
        val b = _binding ?: return
        lifecycleScope.launch {
            val list = repository.searchPairs(query)
            val total = repository.pairCount()
            if (_binding == null) return@launch
            adapter.submit(list)
            val empty = list.isEmpty()
            b.emptyText.isVisible = empty
            b.recyclerPairs.isVisible = !empty
            b.searchStats.text = when {
                empty && query.isNotEmpty() -> getString(R.string.words_stats_empty)
                query.isNotEmpty() -> getString(R.string.words_stats_filtered, list.size, total)
                else -> getString(R.string.words_stats_total, list.size)
            }
        }
    }

    private fun pickImportFile() {
        importLauncher.launch(arrayOf("text/*", "text/csv", "application/vnd.ms-excel"))
    }

    private fun pickExportFile() {
        val name = "phaohn_tukhoa_${exportTimestamp()}.csv"
        exportLauncher.launch(name)
    }

    private fun importFromUri(uri: Uri) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } ?: return@launch
            val rows = CsvHelper.parsePairs(text)
            if (rows.isEmpty()) {
                Toast.makeText(requireContext(), R.string.import_empty, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val result = repository.importPairs(rows)
            showSaveResult(result)
            if (result.hasSuccess) {
                loadPairs(binding.searchInput.text?.toString().orEmpty())
                (activity as? MainActivity)?.refreshHomeUi()
            }
        }
    }

    private fun exportToUri(uri: Uri) {
        lifecycleScope.launch {
            val pairs = repository.allPairs()
            val csv = CsvHelper.exportPairs(pairs)
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

    private fun showAddDialog() = showWordDialog(null)

    private fun showEditDialog(pair: WordPair) = showWordDialog(pair)

    private fun showWordDialog(existing: WordPair?) {
        val dialogBinding = DialogAddWordBinding.inflate(layoutInflater)
        val isEdit = existing != null
        existing?.let {
            dialogBinding.inputCivilian.setText(it.civilianWord)
            dialogBinding.inputSpy.setText(it.spyWord)
        }
        val titleRes = if (isEdit) R.string.edit_word_title else R.string.add_word_title
        dialogBinding.dialogTitle.setText(titleRes)
        if (isEdit) {
            dialogBinding.addModeToggle.isVisible = false
            dialogBinding.multiFields.isVisible = false
            dialogBinding.singleFields.isVisible = true
        } else {
            dialogBinding.addModeToggle.check(R.id.modeSingle)
            dialogBinding.addModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                val multi = checkedId == R.id.modeMulti
                dialogBinding.singleFields.isVisible = !multi
                dialogBinding.multiFields.isVisible = multi
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.save) { d, _ ->
                lifecycleScope.launch {
                    if (isEdit) {
                        val c = dialogBinding.inputCivilian.text?.toString().orEmpty()
                        val s = dialogBinding.inputSpy.text?.toString().orEmpty()
                        val ok = repository.updatePair(existing!!.id, c, s)
                        val msg = if (ok) R.string.updated_ok else R.string.updated_fail
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                        if (ok) {
                            loadPairs(binding.searchInput.text?.toString().orEmpty())
                            (activity as? MainActivity)?.refreshHomeUi()
                        }
                    } else if (dialogBinding.addModeToggle.checkedButtonId == R.id.modeMulti) {
                        val parse = WordPairParser.parseCommaLines(
                            dialogBinding.inputMultiLines.text?.toString().orEmpty()
                        )
                        if (parse.pairs.isEmpty() && parse.invalidLines == 0) {
                            Toast.makeText(requireContext(), R.string.add_multi_invalid, Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val result = repository.importPairs(parse.pairs)
                            .withInvalid(parse.invalidLines)
                        showSaveResult(result)
                        if (result.hasSuccess) {
                            loadPairs(binding.searchInput.text?.toString().orEmpty())
                            (activity as? MainActivity)?.refreshHomeUi()
                        }
                    } else {
                        val c = dialogBinding.inputCivilian.text?.toString().orEmpty()
                        val s = dialogBinding.inputSpy.text?.toString().orEmpty()
                        val result = repository.addManual(c, s)
                        showSaveResult(result)
                        if (result.hasSuccess) {
                            loadPairs(binding.searchInput.text?.toString().orEmpty())
                            (activity as? MainActivity)?.refreshHomeUi()
                        }
                    }
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSaveResult(result: PairSaveResult) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.save_result_title)
            .setMessage(result.formatMessage(requireContext()))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun confirmDelete(pair: WordPair) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.delete) { d, _ ->
                lifecycleScope.launch {
                    repository.deletePair(pair.id)
                    Toast.makeText(requireContext(), R.string.deleted_ok, Toast.LENGTH_SHORT).show()
                    loadPairs(binding.searchInput.text?.toString().orEmpty())
                    (activity as? MainActivity)?.refreshHomeUi()
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance() = WordListFragment()
    }
}