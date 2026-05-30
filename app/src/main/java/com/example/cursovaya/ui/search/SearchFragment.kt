package com.example.cursovaya.ui.search

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.widget.doOnTextChanged
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.cursovaya.AppNavigator
import com.example.cursovaya.R
import com.example.cursovaya.databinding.FragmentSearchBinding
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import android.view.inputmethod.EditorInfo

class SearchFragment : Fragment() {
    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SearchViewModel by viewModels()
    private val resultsAdapter = ResultsAdapter(onClick = { viewModel.onResultClicked(it) })
    private val historyAdapter = HistoryAdapter { viewModel.onHistoryClicked(it) }
    private var navigator: AppNavigator? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        navigator = context as? AppNavigator
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.recyclerResults.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerResults.adapter = resultsAdapter
        binding.recyclerHistory.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerHistory.adapter = historyAdapter
        binding.editSearch.doOnTextChanged { text, _, _, _ ->
            viewModel.onQueryChanged(text?.toString().orEmpty())
        }
        binding.editSearch.setOnFocusChangeListener { _, hasFocus ->
            viewModel.onFieldFocusChanged(hasFocus)
        }
        binding.editSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(binding.editSearch)
                viewModel.search()
                true
            } else {
                false
            }
        }

        binding.buttonSearch.setOnClickListener {
            hideKeyboard(binding.editSearch)
            viewModel.search()
        }
        binding.buttonClearHistory.setOnClickListener { viewModel.clearHistory() }
        binding.buttonNavChat.setOnClickListener { navigator?.openChat() }
        binding.buttonNavProfile.setOnClickListener { navigator?.openProfile() }
        binding.buttonNavLogout.setOnClickListener {
            viewModel.logout()
            navigator?.openLogin()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    binding.emptyState.isVisible = state.showEmptyState && !state.isLoading
                    binding.errorState.isVisible = state.showErrorState && !state.isLoading
                    binding.recyclerResults.isVisible = !state.showErrorState && !state.showEmptyState
                    binding.historyContainer.isVisible = state.showHistory
                    binding.recyclerHistory.isVisible = state.showHistory
                    binding.textStatusMessage.isVisible = state.isLoading || state.showEmptyState || state.showErrorState
                    binding.textRoutesHeader.text = getString(
                        if (state.showPopularRoutes) R.string.search_popular_routes else R.string.search_results
                    )
                    binding.textStatusMessage.text = when {
                        state.isLoading -> "Поиск маршрутов..."
                        state.showErrorState -> state.errorMessage ?: "Не удалось выполнить поиск"
                        state.showEmptyState -> "По этому запросу маршруты не найдены"
                        else -> ""
                    }
                    if (binding.editSearch.text?.toString() != state.query) {
                        binding.editSearch.setText(state.query)
                        binding.editSearch.setSelection(state.query.length)
                    }
                    resultsAdapter.submitList(state.results)
                    historyAdapter.submitList(state.history)
                    binding.textErrorMessage.text = state.errorMessage ?: "Не удалось выполнить поиск"
                }
            }
        }

        viewModel.initializeHistory()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun hideKeyboard(editText: TextInputEditText) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editText.windowToken, 0)
    }
}
