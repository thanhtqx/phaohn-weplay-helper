package com.phaohn.spyhelper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.phaohn.spyhelper.databinding.DialogAddWordBinding
import com.phaohn.spyhelper.databinding.DialogSyncResultBinding
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
    private lateinit var auth: AuthManager
    private lateinit var adapter: PairsAdapter
    private var searchJob: Job? = null
    private var syncProgressDialog: AlertDialog? = null

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
        auth = AuthManager(requireContext())
        adapter = PairsAdapter(
            onEdit = { pair -> showEditDialog(pair) },
            onDelete = { pair -> confirmDelete(pair) },
            onReport = { pair -> showReportDialog(pair) },
        )
        binding.recyclerPairs.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerPairs.adapter = adapter
        binding.recyclerPairs.setHasFixedSize(true)
        binding.recyclerPairs.itemAnimator = null
        binding.btnAddWord.setOnClickListener { showAddDialog() }
        binding.btnSync.setOnClickListener { syncWithCloud() }
        binding.btnImport.setOnClickListener { pickImportFile() }
        binding.btnExport.setOnClickListener { pickExportFile() }
        binding.btnLookup.setOnClickListener { runLookup(recordHistory = true) }
        binding.btnCloseLookup.setOnClickListener { hideLookupResult() }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runLookup(recordHistory = true)
                true
            } else {
                false
            }
        }
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

    fun loadPairs(query: String = "") {
        val b = _binding ?: return
        lifecycleScope.launch {
            val list = repository.searchPairs(query)
            val total = if (query.isEmpty()) list.size else repository.pairCount()
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

    private fun runLookup(recordHistory: Boolean) {
        val query = binding.searchInput.text?.toString().orEmpty()
        if (query.isEmpty()) {
            hideLookupResult()
            return
        }
        lifecycleScope.launch {
            performLookup(query, recordHistory)
        }
    }

    private suspend fun performLookup(query: String, recordHistory: Boolean) {
        if (_binding == null) return
        showLookupSearching(query)
        when (val result = repository.lookupOthers(query)) {
            is LookupResult.Found -> {
                showLookupResult(result.myWord, result.myRole, result.otherWords)
                if (recordHistory) {
                    repository.recordLookup(query, result.otherWordStrings)
                }
            }
            is LookupResult.NotFound -> {
                showLookupNotFound(query)
                if (recordHistory) {
                    repository.recordLookup(query, emptyList())
                }
            }
            LookupResult.NotInGame -> Unit
        }
    }

    private fun showLookupSearching(query: String) {
        val b = _binding ?: return
        val ctx = requireContext()
        WordLookupUi.bindMyWord(
            b.lookupMyWord,
            query.ifBlank { getString(R.string.word_placeholder) },
            ctx,
        )
        WordLookupUi.bindSearchWords(
            b.lookupResultsScroll,
            b.lookupResults,
            getString(R.string.word_searching),
            ctx,
        )
        b.lookupResultCard.isVisible = true
    }

    private fun showLookupResult(myWord: String, myRole: WordRole, others: List<LabeledWord>) {
        val b = _binding ?: return
        val ctx = requireContext()
        WordLookupUi.bindMyWord(
            b.lookupMyWord,
            RoleTextFormatter.coloredWordApp(ctx, myWord, myRole),
            ctx,
        )
        WordLookupUi.bindSearchWords(
            b.lookupResultsScroll,
            b.lookupResults,
            if (others.isEmpty()) {
                getString(R.string.word_placeholder)
            } else {
                RoleTextFormatter.formatOthersApp(ctx, others)
            },
            ctx,
        )
        b.lookupResultCard.isVisible = true
    }

    private fun showLookupNotFound(myWord: String) {
        val b = _binding ?: return
        val ctx = requireContext()
        WordLookupUi.bindMyWord(b.lookupMyWord, myWord, ctx)
        WordLookupUi.bindSearchWords(
            b.lookupResultsScroll,
            b.lookupResults,
            getString(R.string.word_not_in_list),
            ctx,
        )
        b.lookupResultCard.isVisible = true
    }

    private fun hideLookupResult() {
        _binding?.lookupResultCard?.isVisible = false
    }

    private fun showSyncProgress() {
        dismissSyncProgress()
        syncProgressDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.sync_cloud)
            .setMessage(R.string.sync_running)
            .setCancelable(false)
            .create()
        syncProgressDialog?.show()
    }

    private fun dismissSyncProgress() {
        syncProgressDialog?.dismiss()
        syncProgressDialog = null
    }

    private fun showSyncResultDialog(result: ServerSyncResult) {
        val dialogBinding = DialogSyncResultBinding.inflate(layoutInflater)
        dialogBinding.syncDialogSubtitle.text = getString(R.string.sync_done_subtitle)
        dialogBinding.syncPullValue.text = getString(
            R.string.sync_pull_value,
            result.pulledAdded,
            result.pulledSkipped,
        )
        dialogBinding.syncPushValue.text = getString(
            R.string.sync_push_value,
            result.pushedAdded,
            result.pushedSkipped,
        )
        dialogBinding.syncServerValue.text = getString(R.string.sync_count_value, result.serverTotal)
        dialogBinding.syncLocalValue.text = getString(R.string.sync_count_value, result.localTotal)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialogBinding.btnSyncOk.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun syncWithCloud() {
        val b = _binding ?: return
        b.btnSync.isEnabled = false
        showSyncProgress()
        lifecycleScope.launch {
            try {
                val ctx = requireContext()
                val baseUrl = SpyPrefs.syncBaseUrl(ctx)
                val token = auth.getToken()
                val result = repository.syncWithServer(
                    baseUrl,
                    token,
                    ctx,
                    isAdmin = auth.isAdmin(),
                )
                AdminNotificationHelper.pullAfterServerTouch(
                    ctx,
                    baseUrl,
                    token,
                    activity,
                )
                dismissSyncProgress()
                showSyncResultDialog(result)
                requireContext().sendBroadcast(
                    Intent(SpyAccessibilityService.ACTION_PAIRS_UPDATED)
                        .setPackage(requireContext().packageName),
                )
                loadPairs(b.searchInput.text?.toString().orEmpty())
                (activity as? MainActivity)?.refreshHomeUi()
            } catch (_: AccountLockedException) {
                dismissSyncProgress()
            } catch (e: Exception) {
                dismissSyncProgress()
                val msg = e.message ?: e.javaClass.simpleName
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.sync_fail_title)
                    .setMessage(getString(R.string.sync_fail, msg))
                    .setPositiveButton(R.string.ok, null)
                    .show()
            } finally {
                if (_binding != null) {
                    b.btnSync.isEnabled = true
                }
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
            val result = repository.importPairs(rows, isAdmin = auth.isAdmin())
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
                        val result = repository.importPairs(parse.pairs, isAdmin = auth.isAdmin())
                            .withInvalid(parse.invalidLines)
                        showSaveResult(result)
                        if (result.hasSuccess) {
                            loadPairs(binding.searchInput.text?.toString().orEmpty())
                            (activity as? MainActivity)?.refreshHomeUi()
                        }
                    } else {
                        val c = dialogBinding.inputCivilian.text?.toString().orEmpty()
                        val s = dialogBinding.inputSpy.text?.toString().orEmpty()
                        val result = repository.addManual(c, s, isAdmin = auth.isAdmin())
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
        val msg = buildString {
            append(result.formatMessage(requireContext()))
            if (result.hasPending) {
                append("\n\n")
                append(getString(R.string.word_pending_saved))
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.save_result_title)
            .setMessage(msg)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showReportDialog(pair: WordPair) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val typeGroup = RadioGroup(requireContext()).apply {
            addView(RadioButton(requireContext()).apply {
                id = View.generateViewId()
                text = getString(R.string.report_type_wrong)
                isChecked = true
                tag = "wrong"
            })
            addView(RadioButton(requireContext()).apply {
                id = View.generateViewId()
                text = getString(R.string.report_type_suggest)
                tag = "suggest_edit"
            })
        }
        val messageInput = EditText(requireContext()).apply {
            hint = getString(R.string.report_message_hint)
            setLines(2)
        }
        val suggestCivilian = EditText(requireContext()).apply {
            hint = getString(R.string.report_suggest_civilian)
        }
        val suggestSpy = EditText(requireContext()).apply {
            hint = getString(R.string.report_suggest_spy)
        }
        val suggestBox = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(suggestCivilian)
            addView(suggestSpy)
        }
        typeGroup.setOnCheckedChangeListener { group, checkedId ->
            val checked = group.findViewById<RadioButton>(checkedId)
            suggestBox.visibility = if (checked?.tag == "suggest_edit") View.VISIBLE else View.GONE
        }
        layout.addView(typeGroup)
        layout.addView(messageInput)
        layout.addView(suggestBox)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.report_title)
            .setMessage("${pair.civilianWord} · ${pair.spyWord}")
            .setView(layout)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val checked = typeGroup.findViewById<RadioButton>(typeGroup.checkedRadioButtonId)
                val type = (checked?.tag as? String) ?: "wrong"
                val suggestedC = suggestCivilian.text?.toString().orEmpty()
                val suggestedS = suggestSpy.text?.toString().orEmpty()
                if (type == "suggest_edit" && suggestedC.isEmpty() && suggestedS.isEmpty()) {
                    Toast.makeText(
                        requireContext(),
                        R.string.report_suggest_required,
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    try {
                        auth.reportPair(
                            pair.civilianWord,
                            pair.spyWord,
                            type,
                            messageInput.text?.toString().orEmpty(),
                            suggestedC,
                            suggestedS,
                        )
                        Toast.makeText(requireContext(), R.string.report_ok, Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    } catch (e: Exception) {
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.report_fail, e.message ?: ""),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
        dialog.show()
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
        dismissSyncProgress()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance() = WordListFragment()
    }
}