package com.kanso.spending.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-defined categorisation rule: when a transaction description contains
 * [keyword], suggest [categoryId]. Rules are evaluated before the bundled
 * keyword table and are fully local.
 */
@Entity(tableName = "category_rules")
data class CategoryRuleEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "keyword")
    val keyword: String,

    @ColumnInfo(name = "category_id")
    val categoryId: String,

    @ColumnInfo(name = "scope", defaultValue = "GLOBAL")
    val scope: String = "GLOBAL",

    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long
)
