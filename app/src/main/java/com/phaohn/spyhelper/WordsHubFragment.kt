package com.phaohn.spyhelper

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

class WordsHubFragment : Fragment() {

    private var wordListChild: WordListFragment? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_words_hub, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (wordListChild == null) {
            wordListChild = WordListFragment.newInstance()
            childFragmentManager.beginTransaction()
                .replace(R.id.listHost, wordListChild!!)
                .commit()
        }
    }

    fun refreshWordList() {
        wordListChild?.loadPairs()
    }

    override fun onDestroyView() {
        wordListChild = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance() = WordsHubFragment()
    }
}