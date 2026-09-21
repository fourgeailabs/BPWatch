package com.fourgeailabs.bpwatch.mobile.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fourgeailabs.bpwatch.BuildConfig

private const val CREATOR_URL = "https://github.com/fourgeailabs"
private const val REPO_URL = "https://github.com/fourgeailabs/BPWatch"

/** Opens a URL in the browser. A missing browser must never crash the app. */
private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
        // No browser installed; nothing to do.
    }
}

/**
 * v2.3 About screen: what BPWatch is, the app version, creator credit and
 * repo links. Opened from the About row in Settings; MainActivity supplies
 * the back top bar.
 */
@Composable
fun AboutScreen() {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "What is BPWatch?",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    text = "BPWatch estimates blood pressure from your watch's heart " +
                        "rate data, calibrated against your own cuff readings on the " +
                        "phone. It is a personal wellness experiment, not a medical " +
                        "device, and never a substitute for a cuff or a clinician.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp)) {
                ListItem(
                    headlineContent = {
                        Text(
                            "Version",
                            style = MaterialTheme.typography.titleSmall,
                        )
                    },
                    supportingContent = {
                        Text(
                            "BPWatch ${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                LinkRow(
                    headline = "FourgeAI LABS",
                    supporting = "The creators",
                    onClick = { openUrl(context, CREATOR_URL) },
                )
                LinkRow(
                    headline = "Source code",
                    supporting = "github.com/fourgeailabs/BPWatch",
                    onClick = { openUrl(context, REPO_URL) },
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = "Made with care in the West Valley.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // v2.4.0: moved here from Settings — the disclaimer belongs with About.
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            ),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Important",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = "BPWatch is a personal wellness tool, not a medical device. " +
                        "Blood pressure here is estimated from heart rate using your own " +
                        "cuff calibration — it is not a measurement. Never use it to " +
                        "diagnose, treat, or adjust medication. When in doubt, use a cuff.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun LinkRow(
    headline: String,
    supporting: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(headline, style = MaterialTheme.typography.titleSmall)
        },
        supportingContent = {
            Text(
                supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        leadingContent = {
            Icon(
                Icons.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    )
}
