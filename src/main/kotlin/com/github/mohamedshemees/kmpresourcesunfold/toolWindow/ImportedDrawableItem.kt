package com.github.mohamedshemees.kmpresourcesunfold.toolWindow

import com.github.mohamedshemees.kmpresourcesunfold.ResourceExtension
import com.intellij.openapi.vfs.VirtualFile

data class ImportedDrawableItem(
    val file: VirtualFile,
    var name: String = file.nameWithoutExtension,
    var doNotImport: Boolean = false,
    var convertSvg: Boolean = file.extension?.lowercase() == ResourceExtension.SVG.extension
) {
    val extension: String
        get() = if (convertSvg) "xml" else file.extension ?: ""
}
