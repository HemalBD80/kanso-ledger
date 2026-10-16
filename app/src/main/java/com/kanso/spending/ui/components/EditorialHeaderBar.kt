package com.kanso.spending.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kanso.spending.ui.theme.LocalKansoColors
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val monthFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)

@Composable
fun EditorialHeaderBar(
    selectedMonth: YearMonth,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onOpenBudgetPlanner: () -> Unit,
    onOpenLocalVault: () -> Unit,
    onToggleTheme: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalKansoColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onOpenLocalVault)
                        .padding(vertical = 2.dp)
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(colors.positiveAccent))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "KANSO • ON-DEVICE SQLITE VAULT",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.inkSecondary
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Personal Ledger",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.inkPrimary
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeaderActionButton(
                    icon = if (colors.isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                    tint = colors.inkPrimary,
                    onClick = onToggleTheme
                )
                HeaderActionButton(
                    icon = Icons.Outlined.Tune,
                    tint = colors.inkPrimary,
                    onClick = onOpenBudgetPlanner
                )
                HeaderActionButton(
                    icon = Icons.Outlined.Shield,
                    tint = colors.positiveAccent,
                    onClick = onOpenLocalVault
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.elevatedSurface)
                .border(1.dp, colors.hairlineBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.ChevronLeft,
                contentDescription = "Previous month",
                tint = colors.inkPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onPreviousMonth)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .size(18.dp)
            )
            Text(
                text = selectedMonth.format(monthFormatter).uppercase(Locale.US) + " STATEMENT",
                style = MaterialTheme.typography.labelLarge,
                color = colors.inkPrimary
            )
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = "Next month",
                tint = colors.inkPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onNextMonth)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .size(18.dp)
            )
        }
    }
}

@Composable
private fun HeaderActionButton(
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit
) {
    val colors = LocalKansoColors.current
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface)
            .border(1.dp, colors.hairlineBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}
