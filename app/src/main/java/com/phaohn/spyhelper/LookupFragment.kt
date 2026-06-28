package com.phaohn.spyhelper

import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
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
        loadTopLookups()
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
        when (val result = repository.lookupOthers(query)) {
                is LookupResult.Found -> {
                    showResult(result.myWord, result.myRole, result.otherWords)
                    if (recordHistory) {
                        repository.recordLookup(query, result.otherWordStrings)
                        loadTopLookups()
                    }
                }
            is LookupResult.NotFound -> {
                showNotFound(query)
                if (recordHistory) {
                    repository.recordLookup(query, emptyList())
                    loadTopLookups()
                }
            }
            LookupResult.NotInGame -> Unit
        }
    }

    private fun clearResults() {
        binding.resultCard.isVisible = false
        binding.lookupEmpty.isVisible = false
    }

    private fun loadTopLookups() {
        if (_binding == null) return
        lifecycleScope.launch {
            val top = repository.topLookupWords(limit = 20)
            binding.topLookupChips.removeAllViews()
            val hasTop = top.isNotEmpty()
            binding.topLookupChips.isVisible = hasTop
            binding.topLookupEmpty.isVisible = !hasTop
            binding.topLookupStats.isVisible = hasTop
            if (hasTop) {
                binding.topLookupStats.text = getString(R.string.lookup_top_count, top.sumOf { it.count })
                val ctx = requireContext()
                val chipBg = ContextCompat.getColorStateList(ctx, R.color.chip_lookup_bg)
                val chipStroke = ContextCompat.getColorStateList(ctx, R.color.chip_lookup_stroke)
                val chipText = ContextCompat.getColorStateList(ctx, R.color.chip_lookup_text)
                top.forEach { item ->
                    val chip = Chip(ctx).apply {
                        text = getString(R.string.lookup_top_chip, item.myWord, item.count)
                        isCheckable = false
                        isClickable = true
                        chipBackgroundColor = chipBg
                        chipStrokeColor = chipStroke
                        chipStrokeWidth = resources.displayMetrics.density
                        setTextColor(chipText)
                        textSize = 11f
                        setOnClickListener { runLookup(item.myWord, recordHistory = true) }
                    }
                    binding.topLookupChips.addView(chip)
                }
            }
        }
    }

    private fun showResult(myWord: String, myRole: WordRole, others: List<LabeledWord>) {
        val ctx = requireContext()
        binding.lookupMyWord.text = SpannableStringBuilder().apply {
            append(getString(R.string.my_word))
            append(": ")
            val start = length
            append(myWord)
            setSpan(
                ForegroundColorSpan(RoleTextFormatter.colorForRole(ctx, myRole)),
                start,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        binding.lookupResults.text = RoleTextFormatter.formatOthersApp(ctx, others)
        binding.resultCard.isVisible = true
        binding.lookupEmpty.isVisible = false
    }

    private fun showNotFound(myWord: String) {
        binding.lookupMyWord.text = getString(R.string.lookup_my_word, myWord)
        binding.lookupResults.text = getString(R.string.not_in_db)
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