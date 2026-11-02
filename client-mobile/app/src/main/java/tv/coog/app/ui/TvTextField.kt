package tv.coog.app.ui

import android.content.Context
import android.graphics.Color as AndroidColor
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import androidx.tv.material3.MaterialTheme
import tv.coog.app.BuildConfig

/**
 * TV text field: D-pad focus only highlights the chrome. Press OK/Enter (or click)
 * to enter edit mode and show the soft keyboard. Back / IME Done / D-pad leave
 * exits edit mode without opening the keyboard on navigate.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    uri: Boolean = false,
    password: Boolean = false,
    exitUp: Boolean = false,
    /** Touch overlay: enter edit mode and show IME as soon as the field mounts. */
    autoEdit: Boolean = false,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface.toArgb()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val onChange = rememberUpdatedState(onValueChange)
    val current = rememberUpdatedState(value)
    val enterRailState = rememberUpdatedState(LocalEnterRail.current)
    val exitUpState = rememberUpdatedState(exitUp)
    var chromeFocused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val editingState = rememberUpdatedState(editing)
    val chromeFocus = remember { FocusRequester() }
    val editTextRef = remember { mutableStateOf<EditText?>(null) }
    val shape = RoundedCornerShape(12.dp)
    val highlighted = chromeFocused || editing

    val enterEdit = remember {
        {
            editing = true
            editTextRef.value?.let { et ->
                et.isFocusable = true
                et.isFocusableInTouchMode = true
                et.isCursorVisible = true
                et.requestFocus()
                et.setSelection(et.text?.length ?: 0)
                et.showIme(forced = true)
            }
            Unit
        }
    }
    val exitEdit = remember {
        { moveUp: Boolean ->
            val et = editTextRef.value
            et?.hideIme()
            et?.clearFocus()
            et?.isCursorVisible = false
            et?.isFocusable = false
            et?.isFocusableInTouchMode = false
            editing = false
            if (moveUp && exitUpState.value) {
                enterRailState.value()
            } else {
                chromeFocus.requestFocus()
            }
            Unit
        }
    }
    val enterEditState = rememberUpdatedState(enterEdit)
    val exitEditState = rememberUpdatedState(exitEdit)

    LaunchedEffect(autoEdit) {
        if (!BuildConfig.TOUCH_CLIENT) return@LaunchedEffect
        if (autoEdit) {
            // Wait a frame so AndroidView factory has attached the EditText.
            delay(80)
            enterEditState.value()
        } else {
            // Overlay stays composed under details — drop IME when search is covered.
            exitEditState.value(false)
        }
    }

    DisposableEffect(Unit) {
        onDispose { editTextRef.value?.hideIme() }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 900.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = if (highlighted) 0.16f else 0.08f))
            .then(if (highlighted) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .focusRequester(chromeFocus)
            .onFocusChanged { chromeFocused = it.hasFocus && !editingState.value }
            .then(
                if (BuildConfig.TOUCH_CLIENT) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            if (!editingState.value) enterEditState.value()
                        },
                    )
                } else {
                    Modifier
                        .focusable()
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when {
                                !editingState.value && (event.key == Key.DirectionCenter || event.key == Key.Enter) -> {
                                    enterEditState.value()
                                    true
                                }
                                !editingState.value && exitUpState.value && event.key == Key.DirectionUp -> {
                                    enterRailState.value()
                                    true
                                }
                                editingState.value && (event.key == Key.Back || event.key == Key.Escape) -> {
                                    exitEditState.value(false)
                                    true
                                }
                                editingState.value && event.key == Key.DirectionUp -> {
                                    exitEditState.value(true)
                                    true
                                }
                                editingState.value && event.key == Key.DirectionDown -> {
                                    exitEditState.value(false)
                                    true
                                }
                                else -> false
                            }
                        }
                },
            ),
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 4.dp)
                .heightIn(min = 52.dp),
            factory = { context ->
                EditText(context).apply {
                    setBackgroundColor(AndroidColor.TRANSPARENT)
                    setTextColor(onSurface)
                    setHintTextColor(muted)
                    textSize = 16f
                    isSingleLine = true
                    // Phone/tablet: normal EditText touch editing. TV: chrome focus until OK.
                    isFocusable = BuildConfig.TOUCH_CLIENT
                    isFocusableInTouchMode = BuildConfig.TOUCH_CLIENT
                    isCursorVisible = BuildConfig.TOUCH_CLIENT
                    showSoftInputOnFocus = true
                    if (BuildConfig.TOUCH_CLIENT) {
                        editing = true
                    }
                    imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    inputType = when {
                        password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        uri -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                        else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    }
                    gravity = Gravity.CENTER_VERTICAL
                    hint = placeholder
                    setText(current.value)
                    setSelection(current.value.length)
                    doAfterTextChanged { text ->
                        onChange.value(text?.toString().orEmpty())
                    }
                    setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_DONE) {
                            exitEditState.value(false)
                            true
                        } else {
                            false
                        }
                    }
                    setOnKeyListener { _, keyCode, event ->
                        if (event.action != AndroidKeyEvent.ACTION_DOWN) return@setOnKeyListener false
                        when (keyCode) {
                            AndroidKeyEvent.KEYCODE_BACK, AndroidKeyEvent.KEYCODE_ESCAPE -> {
                                exitEditState.value(false)
                                true
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                                exitEditState.value(true)
                                true
                            }
                            AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
                                exitEditState.value(false)
                                true
                            }
                            else -> false
                        }
                    }
                    setOnClickListener {
                        if (!editingState.value) enterEditState.value()
                    }
                    editTextRef.value = this
                }
            },
            update = { view ->
                editTextRef.value = view
                val next = current.value
                if (view.text.toString() != next) {
                    val sel = view.selectionEnd.coerceIn(0, next.length)
                    view.setText(next)
                    view.setSelection(sel)
                }
                if (view.hint?.toString().orEmpty() != placeholder) {
                    view.hint = placeholder
                }
                if (!editing) {
                    view.isFocusable = false
                    view.isFocusableInTouchMode = false
                    view.isCursorVisible = false
                }
            },
        )
    }
}

private fun View.showIme(forced: Boolean) {
    requestFocus()
    post {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return@post
        val flags = if (forced) InputMethodManager.SHOW_FORCED else InputMethodManager.SHOW_IMPLICIT
        if (!imm.showSoftInput(this, flags) && forced) {
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
        }
    }
}

private fun View.hideIme() {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
    imm.hideSoftInputFromWindow(windowToken, 0)
}
