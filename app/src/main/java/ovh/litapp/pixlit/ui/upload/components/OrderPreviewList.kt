package ovh.litapp.pixlit.ui.upload.components

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import ovh.litapp.pixlit.R
import ovh.litapp.pixlit.ui.theme.PixlitTheme

/**
 * Stable LazyRow key for one selected image.
 *
 * The same photo can be picked more than once, so keying on the [Uri] alone throws
 * "Key was already used". Appending the occurrence ordinal keeps distinct URIs keyed by their
 * identity (stable across reordering) while still giving duplicates distinct keys.
 */
private fun thumbnailKey(uris: List<Uri>, index: Int, uri: Uri): String =
    "${uri}#${uris.subList(0, index).count { it == uri }}"

@Composable
fun OrderPreviewList(
    selectedImageUris: List<Uri>,
    currentPage: Int,
    maxPhotos: Int,
    onImageClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    // Keep the proportional thumbnail but never shrink the touch target below
    // the 56 dp minimum (0.15 * 360 dp was only ~54 dp).
    val thumbnailSize = (screenWidthDp * 0.15f).coerceAtLeast(56.dp)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.upload_order_preview, selectedImageUris.size, maxPhotos),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(
                items = selectedImageUris,
                key = { index, uri -> thumbnailKey(selectedImageUris, index, uri) }
            ) { index, uri ->
                val isFocused = index == currentPage
                Box(
                    modifier = Modifier
                        .size(thumbnailSize)
                        .clip(RoundedCornerShape(8.dp))
                        .border(
                            width = if (isFocused) 3.dp else 1.dp,
                            color = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onImageClick(index) }
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(uri),
                        contentDescription = stringResource(R.string.upload_thumbnail_cd, index + 1),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(bottomEnd = 6.dp))
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun OrderPreviewListPreview() {
    PixlitTheme {
        OrderPreviewList(
            selectedImageUris = listOf(Uri.EMPTY, Uri.EMPTY, Uri.EMPTY),
            currentPage = 1,
            maxPhotos = 6,
            onImageClick = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}
