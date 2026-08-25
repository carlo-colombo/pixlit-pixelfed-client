package ovh.litapp.pixlit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DevBanner() {
    Text(
        text = "DEV BUILD",
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.onErrorContainer,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
    )
}
