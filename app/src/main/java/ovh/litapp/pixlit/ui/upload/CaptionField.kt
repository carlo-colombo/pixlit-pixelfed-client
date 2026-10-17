package ovh.litapp.pixlit.ui.upload

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import ovh.litapp.pixlit.R
import ovh.litapp.pixlit.data.repository.TagCount
import kotlin.math.roundToInt

/**
 * The popup's horizontal anchor, in pixels, relative to the anchor bounds' leading edge. [cursorX]
 * is a measured caret position (see [CaptionField]), not a per-character estimate (UX-14).
 */
private class CaptionSuggestionPositionProvider(
    private val cursorX: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val requestedX = if (layoutDirection == LayoutDirection.Ltr) {
            anchorBounds.left
        } else {
            anchorBounds.right - popupContentSize.width
        }
        val x = (requestedX + cursorX + 24).coerceIn(
            0,
            (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        )
        return IntOffset(x, 8)
    }
}

/**
 * The caption input plus its hashtag-suggestion popup (UX-14).
 *
 * Material3's pinned `OutlinedTextField(TextFieldValue, …)` overload exposes no `onTextLayout`, so
 * the popup used to be pinned to the field's leading edge. This field is the same
 * `BasicTextField(TextFieldValue, …)` + `OutlinedTextFieldDefaults.DecorationBox` composition that
 * Material3's own `TextFieldValue` overload uses, with one addition: `onTextLayout` captures the
 * [TextLayoutResult] so the popup anchor is the caret's measured x. The caption's state type stays
 * `TextFieldValue`, so the ViewModel and tag-insertion logic are unchanged.
 */
@Composable
fun CaptionField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onInsertHash: () -> Unit,
    remoteTagSuggestions: List<String>,
    topTags: List<TagCount>,
    onTagSearchQueryChanged: (String) -> Unit,
    onTagSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    var showTagPopup by remember { mutableStateOf(false) }
    var tagSearchQuery by rememberSaveable { mutableStateOf("") }
    val tagSearchFocusRequester = remember { FocusRequester() }

    val hashtagContext = findHashtagContext(value)
    LaunchedEffect(hashtagContext?.start, hashtagContext?.end, hashtagContext?.query) {
        // Only sync from the caption; a null context (e.g. after rotation) must not clobber the
        // saveable query.
        if (hashtagContext != null) tagSearchQuery = hashtagContext.query
    }
    LaunchedEffect(tagSearchQuery) {
        if (hashtagContext != null) onTagSearchQueryChanged(tagSearchQuery)
    }
    LaunchedEffect(showTagPopup) {
        if (showTagPopup) {
            delay(50)
            tagSearchFocusRequester.requestFocus()
        }
    }

    val tagSuggestions = (remoteTagSuggestions + topTags.map { it.name })
        .filter { it.startsWith(tagSearchQuery, ignoreCase = true) }
        .distinctBy { it.lowercase() }
        .take(30)

    // Captured in the layout phase; read only while the popup is shown so the field is not
    // recomposed on every layout pass.
    val textLayoutResult = remember { mutableStateOf<TextLayoutResult?>(null) }

    val textFieldColors = OutlinedTextFieldDefaults.colors()
    val baseTextStyle = LocalTextStyle.current
    // Mirrors OutlinedTextFieldDefaults' default (unfocused) input and caret token colors; the
    // per-state accessors are internal to Material3.
    val textColor = baseTextStyle.color.takeOrElse { MaterialTheme.colorScheme.onSurface }
    val mergedTextStyle = baseTextStyle.merge(TextStyle(color = textColor))

    // The outlined decoration reserves this much room above the inner field so the floating label
    // does not overlap it (mirrors Material3's internal minimizedLabelHalfHeight()).
    val labelLineHeight = MaterialTheme.typography.bodySmall.lineHeight.takeOrElse { 16.sp }
    val labelHalfHeight = with(LocalDensity.current) { labelLineHeight.toDp() / 2 }
    val layoutDirection = LocalLayoutDirection.current
    val contentPaddingStartPx = with(LocalDensity.current) {
        OutlinedTextFieldDefaults.contentPadding().calculateStartPadding(layoutDirection).toPx()
    }

    Box(modifier = modifier) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .semantics(mergeDescendants = true) {}
                .padding(top = labelHalfHeight)
                .defaultMinSize(
                    minWidth = OutlinedTextFieldDefaults.MinWidth,
                    minHeight = OutlinedTextFieldDefaults.MinHeight
                ),
            enabled = true,
            readOnly = false,
            textStyle = mergedTextStyle,
            keyboardOptions = KeyboardOptions.Default,
            keyboardActions = KeyboardActions.Default,
            singleLine = false,
            maxLines = 10,
            minLines = 1,
            visualTransformation = VisualTransformation.None,
            onTextLayout = { textLayoutResult.value = it },
            interactionSource = interactionSource,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { innerTextField ->
                OutlinedTextFieldDefaults.DecorationBox(
                    value = value.text,
                    visualTransformation = VisualTransformation.None,
                    innerTextField = innerTextField,
                    placeholder = null,
                    label = { Text(stringResource(R.string.upload_caption_label)) },
                    trailingIcon = {
                        TextButton(
                            onClick = {
                                onInsertHash()
                                showTagPopup = true
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("#")
                        }
                    },
                    singleLine = false,
                    enabled = true,
                    isError = false,
                    interactionSource = interactionSource,
                    colors = textFieldColors,
                    container = {
                        OutlinedTextFieldDefaults.Container(
                            enabled = true,
                            isError = false,
                            interactionSource = interactionSource,
                            colors = textFieldColors,
                            shape = OutlinedTextFieldDefaults.shape,
                        )
                    },
                )
            }
        )

        if (showTagPopup && hashtagContext != null) {
            // Measured caret anchor; before the first layout the result is null and the popup falls
            // back to the field's leading edge (cursorX = 0).
            val layout = textLayoutResult.value
            val caretOffset = value.selection.max
            val cursorX = if (layout != null && caretOffset in 0..layout.layoutInput.text.length) {
                (contentPaddingStartPx + layout.getHorizontalPosition(caretOffset, false)).roundToInt()
            } else {
                0
            }
            Popup(
                onDismissRequest = { showTagPopup = false },
                popupPositionProvider = CaptionSuggestionPositionProvider(cursorX = cursorX),
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(min = 220.dp, max = 320.dp)
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shadowElevation = 8.dp,
                    tonalElevation = 4.dp,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        OutlinedTextField(
                            value = tagSearchQuery,
                            onValueChange = { tagSearchQuery = it.removePrefix("#") },
                            placeholder = { Text(stringResource(R.string.upload_caption_search_tags)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(tagSearchFocusRequester)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        if (tagSuggestions.isEmpty()) {
                            Text(
                                text = stringResource(R.string.upload_no_matching_tags),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(8.dp)
                            )
                        } else {
                            tagSuggestions.forEach { tag ->
                                Text(
                                    text = "#$tag",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showTagPopup = false
                                            onTagSuggestionClick(tag)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
