package com.example.cursovaya.ui.search

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.cursovaya.AppNavigator
import com.example.cursovaya.databinding.FragmentSearchBinding
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class SearchFragment : Fragment() {
    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SearchViewModel by viewModels()
    private val resultsAdapter = ResultsAdapter { viewModel.onResultClicked(it) }
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
        binding.recyclerResults.adapter = resultsAdapter
        binding.recyclerHistory.adapter = historyAdapter
        binding.editSearch.doOnTextChanged { text, _, _, _ ->
            viewModel.onQueryChanged(text?.toString().orEmpty())
        }
        binding.editSearch.setOnFocusChangeListener { _, hasFocus -> viewModel.onFieldFocusChanged(hasFocus) }

        binding.buttonSearch.setOnClickListener {
            hideKeyboard(binding.editSearch)
            viewModel.search()
        }
        binding.buttonClear.setOnClickListener {
            binding.editSearch.setText("")
            hideKeyboard(binding.editSearch)
            viewModel.clearSearch()
        }
        binding.buttonRefresh.setOnClickListener { viewModel.refresh() }
        binding.buttonClearHistory.setOnClickListener { viewModel.clearHistory() }
        binding.switchTheme.setOnCheckedChangeListener { _, isChecked -> viewModel.toggleTheme(isChecked) }
        binding.buttonLogout.setOnClickListener {
            viewModel.logout()
            navigator?.openLogin()
        }
        binding.buttonOpenChat.setOnClickListener { navigator?.openChat() }
        binding.root.findViewById<Button>(com.example.cursovaya.R.id.buttonRefresh).setOnClickListener { viewModel.refresh() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    binding.buttonClear.visibility = if (state.query.isNotBlank()) View.VISIBLE else View.GONE
                    binding.emptyState.visibility = if (state.showEmptyState) View.VISIBLE else View.GONE
                    binding.errorState.visibility = if (state.showErrorState) View.VISIBLE else View.GONE
                    binding.historyHeader.visibility = if (state.history.isNotEmpty()) View.VISIBLE else View.GONE
                    binding.recyclerHistory.visibility = if (state.showHistory) View.VISIBLE else View.GONE
                    binding.recyclerResults.visibility = if (state.results.isNotEmpty()) View.VISIBLE else View.GONE
                    binding.textWelcome.text = "Здравствуйте, ${state.displayName}!"
                    binding.switchTheme.isChecked = state.darkTheme
                    if (binding.editSearch.text?.toString() != state.query) {
                        binding.editSearch.setText(state.query)
                        binding.editSearch.setSelection(state.query.length)
                    }
                    resultsAdapter.submitList(state.results)
                    historyAdapter.submitList(state.history)
                    binding.root.findViewById<TextView>(com.example.cursovaya.R.id.textErrorMessage).text = state.errorMessage ?: "Не удалось выполнить поиск"
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
