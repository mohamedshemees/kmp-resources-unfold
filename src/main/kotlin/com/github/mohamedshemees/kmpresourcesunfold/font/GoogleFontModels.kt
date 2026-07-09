package com.github.mohamedshemees.kmpresourcesunfold.font

data class GoogleWebfontList(
    val kind: String?,
    val items: List<GoogleWebfontItem>
)

data class GoogleWebfontItem(
    val family: String,
    val variants: List<String>,
    val subsets: List<String>?,
    val version: String?,
    val lastModified: String?,
    val files: Map<String, String>?,
    val category: String?,
    val kind: String?,
    val menu: String?,
    val axes: List<GoogleWebfontAxis>?
)

data class GoogleWebfontAxis(
    val tag: String,
    val start: Double,
    val end: Double
)
