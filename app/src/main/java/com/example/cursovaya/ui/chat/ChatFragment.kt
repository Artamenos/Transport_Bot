package com.example.cursovaya.ui.chat

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.cursovaya.AppNavigator
import com.example.cursovaya.databinding.FragmentChatBinding
import kotlinx.coroutines.launch

class ChatFragment : Fragment() {
    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ChatViewModel by viewModels()
    private val adapter = ChatAdapter()
    private var navigator: AppNavigator? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        navigator = context as? AppNavigator
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.recyclerChat.adapter = adapter
        binding.buttonBack.setOnClickListener { navigator?.openSearch() }
        // Main topic buttons
        binding.buttonTopicRoute.setOnClickListener {
            // send route topic, show "Другой вопрос" and wait for number or user input
            viewModel.sendTopicRoute()
            showOtherQuestionButton()
        }
        binding.buttonTopicSchedule.setOnClickListener {
            viewModel.sendTopicSchedule()
            showOtherQuestionButton()
        }
        binding.buttonTopicQuestion.setOnClickListener {
            viewModel.sendTopicQuestion()
            showOtherQuestionButton()
        }

        binding.buttonOtherQuestion.setOnClickListener {
            // end current dialog and show main topics again
            sendRaw("Другой вопрос")
            showMainTopics()
        }

        // Route action buttons (time/date/route)
        binding.buttonChangeTime.setOnClickListener {
            // trigger change time flow on server
            sendRaw("изменить время")
        }
        binding.buttonChangeDate.setOnClickListener {
            sendRaw("изменить дату")
        }
        binding.buttonChangeRoute.setOnClickListener {
            sendRaw("изменить маршрут")
        }

        binding.buttonSend.setOnClickListener {
            hideKeyboard()
            viewModel.sendMessage()
        }
        binding.editChatInput.doOnTextChanged { text, _, _, _ ->
            viewModel.onInputChanged(text?.toString().orEmpty())
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    binding.progressChat.visibility = if (state.isLoading) View.VISIBLE else View.GONE
                    binding.textChatError.visibility = if (state.errorMessage.isNullOrBlank()) View.GONE else View.VISIBLE
                    binding.textChatError.text = state.errorMessage
                    if (binding.editChatInput.text?.toString() != state.input) {
                        binding.editChatInput.setText(state.input)
                        binding.editChatInput.setSelection(state.input.length)
                    }
                    adapter.submitList(state.messages) {
                        if (state.messages.isNotEmpty()) {
                            binding.recyclerChat.scrollToPosition(state.messages.size - 1)
                            // inspect last bot message for markers
                            val last = state.messages.lastOrNull()
                            if (last != null && !last.isUser) {
                                val text = last.text
                                if (text.contains("[MAIN_TOPICS]")) {
                                    showMainTopics()
                                } else if (text.contains("[AFTER_ROUTE]")) {
                                    // show route action buttons and change header prompt if needed
                                    showRouteActions()
                                }
                            }
                        }
                    }
                }
            }
        }

        viewModel.loadHistory()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.editChatInput.windowToken, 0)
    }

    private fun showMainTopics() {
        binding.mainTopicButtons.visibility = View.VISIBLE
        binding.buttonTopicQuestion.visibility = View.VISIBLE
        binding.buttonOtherQuestion.visibility = View.GONE
        binding.routeActionButtons.visibility = View.GONE
    }

    private fun showOtherQuestionButton() {
        binding.mainTopicButtons.visibility = View.GONE
        binding.buttonTopicQuestion.visibility = View.GONE
        binding.buttonOtherQuestion.visibility = View.VISIBLE
        binding.routeActionButtons.visibility = View.GONE
    }

    private fun showRouteActions() {
        binding.mainTopicButtons.visibility = View.GONE
        binding.buttonTopicQuestion.visibility = View.GONE
        binding.buttonOtherQuestion.visibility = View.VISIBLE
        binding.routeActionButtons.visibility = View.VISIBLE
    }

    private fun sendRaw(text: String) {
        // Этот метод отправляет текст напрямую через репозиторий (использует viewModel)
        binding.editChatInput.setText(text)
        viewModel.onInputChanged(text)
        viewModel.sendMessage()
    }
}
