package com.kanso.spending.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.kanso.spending.data.local.entity.CategoryEntity
import com.kanso.spending.domain.CurrencyFormatter
import com.kanso.spending.ui.SpendingDashboardUiState
import com.kanso.spending.ui.theme.LocalKansoColors

/** Editorial palette offered for custom-category colors. */
private val CATEGORY_COLOR_CHOICES = listOf(
    0xFF161614L, 0xFF1E4D3BL, 0xFFC84B31L, 0xFF2B5876L, 0xFFD9822BL,
    0xFFE56B51L, 0xFF5A4E7CL, 0xFF6B5344L, 0xFF2D6A4FL, 0xFF8C3B4BL
)

/**
 * Manage Categories sheet: add custom categories, rename existing ones (ids
 * stay stable so history is never broken), and delete custom categories with a
 * safe reassignment prompt for the linked transactions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryManagerSheet(
    uiState: SpendingDashboardUiState,
    onCreateCategory: (name: String, iconKey: String, colorHex: Long) -> Unit,
    onRenameCategory: (categoryId: String, newName: String) -> Unit,
    onDeleteCategory: (categoryId: String, reassignToId: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalKansoColors.current

    var addName by remember { mutableStateOf("") }
    var addIconKey by remember { mutableStateOf(CategoryEntity.ICON_OTHER) }
    var addColorHex by remember { mutableStateOf(CATEGORY_COLOR_CHOICES.first()) }

    var renameTarget by remember { mutableStateOf<CategoryEntity?>(null) }
    var renameText by remember { mutableStateOf("") }

    var deleteTarget by remember { mutableStateOf<CategoryEntity?>(null) }
    var deleteReassignTo by remember { mutableStateOf<String?>(null) }

    val currencySymbol = uiState.currencySymbol

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface, contentColor = colors.inkPrimary) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text("CATEGORY DIRECTORY", style = MaterialTheme.typography.labelSmall, color = colors.inkSecondary)
            Spacer(Modifier.height(4.dp))
            Text("Manage Categories", style = MaterialTheme.typography.headlineMedium, color = colors.inkPrimary)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Renaming keeps the same category id, so historical transactions stay intact. Deleting a custom category asks you to reassign its transactions.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkMuted
            )
            Spacer(Modifier.height(14.dp))

            // ------------------------------------------------ category directory
            uiState.categoryList.forEach { category ->
                val usage = uiState.categoryUsage[category.id]
                val count = usage?.first ?: 0
                val total = usage?.second ?: 0L
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(Color(category.colorHex)))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(category.displayName, style = MaterialTheme.typography.bodyLarge, color = colors.inkPrimary)
                            Text(
                                text = "$count transaction(s) • ${CurrencyFormatter.formatCentsCompact(total, currencySymbol)} all-time",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.inkMuted
                            )
                        }
                    }
                    // Defaults cannot be deleted (data integrity); they can be renamed.
                    IconButton(onClick = {
                        renameTarget = category
                        renameText = category.displayName
                    }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Rename", tint = colors.inkMuted, modifier = Modifier.size(16.dp))
                    }
                    if (category.isCustom) {
                        IconButton(onClick = {
                            deleteTarget = category
                            deleteReassignTo = null
                        }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = colors.alertAccent, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                HorizontalDivider(color = colors.hairlineBorder.copy(alpha = 0.5f), thickness = 0.75.dp)
            }

            Spacer(Modifier.height(18.dp))

            // ------------------------------------------------ add new category
            Text("ADD CUSTOM CATEGORY", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = addName,
                onValueChange = { addName = it },
                label = { Text("Category name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text("Visual identifier", style = MaterialTheme.typography.labelSmall, color = colors.inkMuted)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                com.kanso.spending.data.local.entity.CategoryEntity.AVAILABLE_ICON_KEYS.forEach { key ->
                    val active = addIconKey == key
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) colors.inkPrimary else colors.elevatedSurface)
                            .border(1.dp, colors.hairlineBorder, RoundedCornerShape(8.dp))
                            .clickable { addIconKey = key }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(key.uppercase(), style = MaterialTheme.typography.labelSmall, color = if (active) colors.surface else colors.inkSecondary)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CATEGORY_COLOR_CHOICES.forEach { hex ->
                    val active = addColorHex == hex
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color(hex))
                            .border(if (active) 3.dp else 1.dp, if (active) colors.inkPrimary else colors.hairlineBorder, CircleShape)
                            .clickable { addColorHex = hex }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    onCreateCategory(addName, addIconKey, addColorHex)
                    addName = ""
                },
                enabled = addName.trim().isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.inkPrimary, contentColor = colors.surface)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("CREATE CATEGORY", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ------------------------------------------------------ rename dialog
    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = colors.surface,
            titleContentColor = colors.inkPrimary,
            textContentColor = colors.inkSecondary,
            title = { Text("Rename Category") },
            text = {
                Column {
                    Text("Renaming keeps all historical transactions linked to this category — no data is lost.")
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("New name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (renameText.trim().isNotEmpty()) {
                            onRenameCategory(renameTarget!!.id, renameText)
                        }
                        renameTarget = null
                    },
                    enabled = renameText.trim().isNotEmpty()
                ) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }

    // ------------------------------------------------------ delete dialog
    val target = deleteTarget
    if (target != null) {
        val usage = uiState.categoryUsage[target.id]
        val linkedCount = usage?.first ?: 0
        val destinations = uiState.categoryList.filter { it.id != target.id && !it.isHidden }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = colors.surface,
            titleContentColor = colors.inkPrimary,
            textContentColor = colors.inkSecondary,
            title = { Text("Delete “${target.displayName}”?") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (linkedCount > 0) {
                        Text(
                            "This category has $linkedCount transaction(s) worth ${CurrencyFormatter.formatCents(usage?.second ?: 0L, currencySymbol)}. No history will be lost — choose where those transactions go:"
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, if (deleteReassignTo == null) colors.inkPrimary else colors.hairlineBorder, RoundedCornerShape(10.dp))
                                .clickable { deleteReassignTo = null }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = deleteReassignTo == null,
                                onClick = { deleteReassignTo = null }
                            )
                            Text("Keep under “Other” (uncategorized archive)", style = MaterialTheme.typography.bodyMedium)
                        }
                        destinations.forEach { dest ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(1.dp, if (deleteReassignTo == dest.id) colors.inkPrimary else colors.hairlineBorder, RoundedCornerShape(10.dp))
                                    .clickable { deleteReassignTo = dest.id }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = deleteReassignTo == dest.id, onClick = { deleteReassignTo = dest.id })
                                Text("Reassign to “${dest.displayName}”", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    } else {
                        Text("No transactions use this category, so deleting it changes nothing else.")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteCategory(target.id, deleteReassignTo)
                    deleteTarget = null
                }) { Text("Delete", color = colors.alertAccent) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}
