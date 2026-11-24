package com.noop.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.sync.CoachSyncClient
import com.noop.sync.CoachSyncPrefs

/**
 * Self-contained per-screen Coach nudge slot for Today (`"today"`), Live (`"live"`),
 * and Workouts (`"workouts"`). Automatically triggers an in-memory 10-min throttled fetch
 * when Cloud Coach Sync & Screen Nudges are enabled, and hides when disabled, offline, or dismissed.
 */
@Composable
fun CoachNudgeSection(
    vm: AppViewModel,
    screen: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cleanScreen = screen.trim().lowercase()
    val enabled = CoachSyncPrefs.shouldShowScreenNudges(
        syncEnabled = CoachSyncPrefs.isEnabled(context),
        showScreenNudgesPref = CoachSyncPrefs.showScreenNudges(context),
    )
    val hasConfig = CoachSyncPrefs.url(context).isNotEmpty() &&
        CoachSyncPrefs.token(context).isNotEmpty()

    val nudges by vm.coachNudges.collectAsStateWithLifecycle()
    val dismissed by vm.dismissedNudges.collectAsStateWithLifecycle()
    val refreshingSet by vm.coachNudgeRefreshing.collectAsStateWithLifecycle()

    LaunchedEffect(cleanScreen, enabled, hasConfig) {
        if (enabled && hasConfig) {
            vm.requestCoachNudge(cleanScreen, force = false)
        }
    }

    if (!enabled || !hasConfig) return
    if (cleanScreen in dismissed) return
    val nudge = nudges[cleanScreen] ?: return
    val isRefreshing = cleanScreen in refreshingSet

    CoachNudgeCard(
        nudge = nudge,
        refreshing = isRefreshing,
        onRefresh = { vm.requestCoachNudge(cleanScreen, force = true) },
        onDismiss = { vm.dismissCoachNudge(cleanScreen) },
        onOpenPump = { openPumpCoach(context) },
        modifier = modifier,
    )
}

/**
 * Launch Pump's Coach screen via deep link (`pump://coach`), falling back to the
 * `com.pankaj.pump` package launch intent and catching [ActivityNotFoundException] safely.
 */
internal fun openPumpCoach(context: Context) {
    val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse("pump://coach")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(deepLink)
        return
    } catch (_: ActivityNotFoundException) {
    } catch (_: Exception) {
    }

    val launchIntent = runCatching {
        context.packageManager.getLaunchIntentForPackage("com.pankaj.pump")
    }.getOrNull()
    if (launchIntent != null) {
        try {
            context.startActivity(launchIntent)
        } catch (_: ActivityNotFoundException) {
        } catch (_: Exception) {
        }
    }
}

/**
 * Style B — WHOOP Coach Card (dark-OLED):
 * - Dark gradient surface (`#141821` -> `#0E1016`) with subtle border + 3dp left accent rail colored by tone
 * - Top row: tinted `'COACH · <SCREEN>'` pill badge + subtle Refresh (`↻`) and Dismiss (`×`) icon buttons
 * - Bold headline + 1–2 line body message
 * - Footer row: `'Mistral Coach'` subtle label + compact `'Open in Pump →'` pill button
 */
@Composable
fun CoachNudgeCard(
    nudge: CoachSyncClient.CoachNudge,
    refreshing: Boolean = false,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onOpenPump: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val accentColor = when (CoachSyncClient.normalizeTone(nudge.tone)) {
        "green" -> Color(0xFF22C55E)
        "yellow" -> Color(0xFFEAB308)
        "red" -> Color(0xFFEF4444)
        else -> Color(0xFF38BDF8)
    }
    val screenLabel = nudge.screen.trim().uppercase().ifEmpty { "TODAY" }
    val shape = RoundedCornerShape(14.dp)
    val cardGradient = Brush.linearGradient(
        colors = listOf(Color(0xFF141821), Color(0xFF0E1016)),
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(cardGradient)
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 3dp left accent rail colored by tone
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accentColor),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Top row: tinted 'COACH · <SCREEN>' pill badge + Refresh (↻) and Dismiss (×) buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(accentColor.copy(alpha = 0.16f))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "COACH · $screenLabel",
                        style = NoopType.overline.copy(
                            fontSize = 10.sp,
                            letterSpacing = 1.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = accentColor,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(
                                enabled = !refreshing,
                                role = Role.Button,
                                onClick = onRefresh,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh Coach insight",
                            tint = Color(0xFF8B93A1),
                            modifier = Modifier
                                .size(15.dp)
                                .alpha(if (refreshing) 0.35f else 0.75f),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(
                                role = Role.Button,
                                onClick = onDismiss,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Dismiss Coach insight",
                            tint = Color(0xFF8B93A1),
                            modifier = Modifier
                                .size(15.dp)
                                .alpha(0.75f),
                        )
                    }
                }
            }

            // Bold headline
            Text(
                text = nudge.headline,
                style = NoopType.subhead.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = Color(0xFFF3F5F8),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // 1–2 line body message
            Text(
                text = nudge.message,
                style = NoopType.footnote.copy(
                    fontSize = 12.5.sp,
                    lineHeight = 17.5.sp,
                ),
                color = Color(0xFFC5CCD8),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // Footer row: 'Mistral Coach' subtle label + compact 'Open in Pump →' pill button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Mistral Coach",
                    style = NoopType.caption.copy(fontSize = 11.sp),
                    color = Color(0xFF8B93A1),
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable(
                            role = Role.Button,
                            onClick = onOpenPump,
                        )
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "Open in Pump →",
                        style = NoopType.caption.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = Color(0xFFF3F5F8),
                    )
                }
            }
        }
    }
}
