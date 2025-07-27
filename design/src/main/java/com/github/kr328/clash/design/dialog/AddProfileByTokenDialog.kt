package com.github.kr328.clash.design.dialog

import android.app.Dialog
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.design.databinding.DialogAddProfileByTokenBinding
import com.github.kr328.clash.service.api.TokenApiService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import java.util.regex.Pattern

class AddProfileByTokenDialog(
    private val context: Context,
    private val onSuccess: (profileUrl: String, token: String) -> Unit,
    private val onError: (error: String) -> Unit
) {
    private val binding = DialogAddProfileByTokenBinding.inflate(LayoutInflater.from(context))
    private val dialog: AlertDialog
    private val tokenApiService = TokenApiService()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    companion object {
        private val TOKEN_PATTERN = Pattern.compile("^[a-zA-Z0-9]{8,}$")
    }

    init {
        binding.self = this
        
        dialog = MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .setCancelable(true)
            .create()

        setupTokenInput()
        updateConfirmButton()
    }

    private fun setupTokenInput() {
        binding.tokenInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateConfirmButton()
                clearError()
            }
        })
    }

    private fun updateConfirmButton() {
        val token = binding.tokenInput.text?.toString() ?: ""
        binding.confirmButton.isEnabled = isValidToken(token)
    }

    private fun isValidToken(token: String): Boolean {
        return token.isNotBlank() && token.length >= 8 && TOKEN_PATTERN.matcher(token).matches()
    }

    private fun clearError() {
        binding.tokenInput.parent.parent.let { layout ->
            if (layout is com.google.android.material.textfield.TextInputLayout) {
                layout.error = null
            }
        }
    }

    private fun showError(message: String) {
        binding.tokenInput.parent.parent.let { layout ->
            if (layout is com.google.android.material.textfield.TextInputLayout) {
                layout.error = message
            }
        }
    }

    fun show() {
        dialog.show()
    }

    fun cancel() {
        scope.cancel()
        dialog.dismiss()
    }

    fun confirm() {
        val token = binding.tokenInput.text?.toString()?.trim() ?: ""
        
        if (!isValidToken(token)) {
            showError("Token không hợp lệ. Token phải có ít nhất 8 ký tự và chỉ chứa chữ cái, số.")
            return
        }

        // Disable button and show loading
        binding.confirmButton.isEnabled = false
        binding.confirmButton.text = "Đang xử lý..."
        clearError()

        scope.launch {
            try {
                val result = tokenApiService.getProfileUrl(token)
                
                result.fold(
                    onSuccess = { profileUrl ->
                        dialog.dismiss()
                        onSuccess(profileUrl, token)
                    },
                    onFailure = { error ->
                        showError(error.message ?: "Lỗi không xác định")
                        resetButton()
                    }
                )
            } catch (e: Exception) {
                showError("Lỗi kết nối: ${e.message}")
                resetButton()
            }
        }
    }

    private fun resetButton() {
        binding.confirmButton.isEnabled = true
        binding.confirmButton.text = "Thêm Profile"
    }
}
