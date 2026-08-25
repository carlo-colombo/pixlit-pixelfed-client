package ovh.litapp.pixlit.ui.upload.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ovh.litapp.pixlit.ui.theme.PixlitTheme
import ovh.litapp.pixlit.utils.ImageMetadata
import ovh.litapp.pixlit.utils.ImageUtils

@Composable
fun MetadataDisplay(
    currentPage: Int,
    totalImages: Int,
    originalMetadata: ImageMetadata?,
    resizedMetadata: ImageMetadata?,
    isCalculatingResized: Boolean,
    resizeTo8Mb: Boolean,
    modifier: Modifier = Modifier
) {
    if (totalImages > 0 && originalMetadata != null) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Photo ${currentPage + 1} of $totalImages",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (resizeTo8Mb && originalMetadata.sizeBytes > ImageUtils.MAX_BYTES_8MB) {
                    Text(
                        text = "Original: ${originalMetadata.formatFileSize()} (${originalMetadata.formatDimensions()})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isCalculatingResized) {
                        Text(
                            text = "Resized: Calculating...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (resizedMetadata != null) {
                        Text(
                            text = "Resized: ${resizedMetadata.formatFileSize()} (${resizedMetadata.formatDimensions()})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    Text(
                        text = "Size: ${originalMetadata.formatFileSize()} (${originalMetadata.formatDimensions()})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MetadataDisplayPreview() {
    PixlitTheme {
        MetadataDisplay(
            currentPage = 0,
            totalImages = 1,
            originalMetadata = ImageMetadata(
                sizeBytes = 1024 * 1024 * 5, // 5MB
                width = 3000,
                height = 2000
            ),
            resizedMetadata = null,
            isCalculatingResized = false,
            resizeTo8Mb = false,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun MetadataDisplayResizedPreview() {
    PixlitTheme {
        MetadataDisplay(
            currentPage = 0,
            totalImages = 3,
            originalMetadata = ImageMetadata(
                sizeBytes = 1024 * 1024 * 12, // 12MB
                width = 5000,
                height = 4000
            ),
            resizedMetadata = ImageMetadata(
                sizeBytes = 1024 * 1024 * 7, // 7MB
                width = 3500,
                height = 2800
            ),
            isCalculatingResized = false,
            resizeTo8Mb = true,
            modifier = Modifier.padding(16.dp)
        )
    }
}
