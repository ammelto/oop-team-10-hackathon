package com.example.oop.chat.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.example.oop.R

@Composable
fun TokenDialog(
    token: String,
    onTokenChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    AlertDialog(
        modifier = modifier,
        onDismissRequest = {},
        title = {
            Text(text = stringResource(R.string.dialog_token_title))
        },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(text = stringResource(R.string.dialog_token_message))
                OutlinedTextField(
                    value = token,
                    onValueChange = onTokenChanged,
                    singleLine = true,
                    label = {
                        Text(text = stringResource(R.string.dialog_token_hint))
                    },
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSubmit,
                enabled = token.isNotBlank(),
            ) {
                Text(text = stringResource(R.string.action_save_download))
            }
        },
    )
}
