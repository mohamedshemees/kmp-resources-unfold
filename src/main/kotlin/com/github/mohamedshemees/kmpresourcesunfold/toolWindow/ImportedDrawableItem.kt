package com.github.mohamedshemees.kmpresourcesunfold.toolWindow

import com.github.mohamedshemees.kmpresourcesunfold.ResourceExtension
import com.intellij.openapi.vfs.VirtualFile

data class ImportedDrawableItem(
    val file: VirtualFile,
    private val originalName: String = file.nameWithoutExtension,
    var name: String = originalName,
    var doNotImport: Boolean = false,
    var convertSvg: Boolean = file.extension?.lowercase() == ResourceExtension.SVG.extension
) {
    val extension: String
        get() = if (convertSvg) "xml" else file.extension ?: ""

    fun applyPrefix(enabled: Boolean) {
        val prefix = when (file.extension?.lowercase()) {
            ResourceExtension.SVG.extension, ResourceExtension.XML.extension -> "ic_"
            else -> "img_"
        }
        name = if (enabled) {
            if (originalName.startsWith(prefix)) originalName else "${prefix}$originalName"
        } else {
            originalName
        }
    }
}
