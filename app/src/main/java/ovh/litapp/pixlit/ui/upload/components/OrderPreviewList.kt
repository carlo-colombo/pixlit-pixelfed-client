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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import ovh.litapp.pixlit.ui.theme.PixlitTheme

@Composable
fun OrderPreviewList(
    selectedImageUris: List<Uri>,
    currentPage: Int,
    maxPhotos: Int,
    onImageClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val thumbnailSize = screenWidthDp * 0.15f

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Upload Order Preview (${selectedImageUris.size}/$maxPhotos)",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(selectedImageUris) { index, uri ->
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
                        contentDescription = "Thumbnail ${index + 1}",
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
