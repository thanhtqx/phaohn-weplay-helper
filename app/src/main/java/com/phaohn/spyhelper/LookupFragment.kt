package com.phaohn.spyhelper

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.phaohn.spyhelper.databinding.FragmentLookupBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LookupFragment : Fragment() {

    private var _binding: FragmentLookupBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: WordRepository
    private var lookupJob: Job? = null
    private var suppressAutoLookup = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLookupBinding.inflate(inflater, container, false)
        repository = PhaoHNApp.repo(requireActivity().application)
        binding.btnLookup.setOnClickListener { runLookup(recordHistory = true) }
        binding.lookupInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runLookup(recordHistory = true)
                true
            } else {
                false
            }
        }
        binding.lookupInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (suppressAutoLookup) return
                lookupJob?.cancel()
                val text = s?.toString().orEmpty()
                lookupJob = lifecycleScope.launch {
                    delay(300)
                    if (text.isEmpty()) {
                        clearResults()
                    } else {
                        performLookup(text, recordHistory = false)
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        val current = binding.lookupInput.text?.toString().orEmpty()
        if (current.isNotEmpty()) {
            lifecycleScope.launch { performLookup(current, recordHistory = false) }
        }
    }

    private fun runLookup(word: String? = null, recordHistory: Boolean = false) {
        val query = word ?: binding.lookupInput.text?.toString().orEmpty()
        if (query.isEmpty()) {
            clearResults()
            return
        }
        if (word != null) {
            suppressAutoLookup = true
            binding.lookupInput.setText(query)
            binding.lookupInput.setSelection(query.length)
            suppressAutoLookup = false
        }
        lifecycleScope.launch {
            performLookup(query, recordHistory)
        }
    }

    private suspend fun performLookup(query: String, recordHistory: Boolean) {
        showSearching(query)
        when (val result = repository.lookupOthers(query)) {
            is LookupResult.Found -> {
                showResult(result.myWord, result.myRole, result.otherWords)
                if (recordHistory) {
                    repository.recordLookup(query, result.otherWordStrings)
                }
            }
            is LookupResult.NotFound -> {
                showNotFound(query)
                if (recordHistory) {
                    repository.recordLookup(query, emptyList())
                }
            }
            LookupResult.NotInGame -> Unit
        }
    }

    private fun clearResults() {
        binding.resultCard.isVisible = false
        binding.lookupEmpty.isVisible = false
    }

    private fun showSearching(query: String) {
        val ctx = requireContext()
        WordLookupUi.bindMyWord(
            binding.lookupMyWord,
            query.ifBlank { getString(R.string.word_placeholder) },
            ctx,
        )
        WordLookupUi.bindSearchWords(
            binding.lookupResultsScroll,
            binding.lookupResults,
            getString(R.string.word_searching),
            ctx,
        )
        binding.resultCard.isVisible = true
        binding.lookupEmpty.isVisible = false
    }

    private fun showResult(myWord: String, myRole: WordRole, others: List<LabeledWord>) {
        val ctx = requireContext()
        WordLookupUi.bindMyWord(
            binding.lookupMyWord,
            RoleTextFormatter.coloredWordApp(ctx, myWord, myRole),
            ctx,
        )
        WordLookupUi.bindSearchWords(
            binding.lookupResultsScroll,
            binding.lookupResults,
            if (others.isEmpty()) {
                getString(R.string.word_placeholder)
            } else {
                RoleTextFormatter.formatOthersApp(ctx, others)
            },
            ctx,
        )
        binding.resultCard.isVisible = true
        binding.lookupEmpty.isVisible = false
    }

    private fun showNotFound(myWord: String) {
        val ctx = requireContext()
        WordLookupUi.bindMyWord(binding.lookupMyWord, myWord, ctx)
        WordLookupUi.bindSearchWords(
            binding.lookupResultsScroll,
            binding.lookupResults,
            getString(R.string.word_not_in_list),
            ctx,
        )
        binding.resultCard.isVisible = true
        binding.lookupEmpty.isVisible = false
    }

    override fun onDestroyView() {
        lookupJob?.cancel()
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance() = LookupFragment()
    }
}