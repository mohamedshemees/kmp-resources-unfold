package com.github.mohamedshemees.kmpresourcesunfold.toolWindow

import com.github.mohamedshemees.kmpresourcesunfold.ResourceExtension
import com.intellij.openapi.vfs.VirtualFile

enum class Density(val directoryQualifier: String, val displayName: String) {
    MDPI("", "mdpi"),
    HDPI("-hdpi", "hdpi"),
    XHDPI("-xhdpi", "xhdpi"),
    XXHDPI("-xxhdpi", "xxhdpi"),
    XXXHDPI("-xxxhdpi", "xxxhdpi"),
    DEFAULT("", "mdpi");

    companion object {
        private val DENSITY_REGEX = Regex("(@[xX]?(\\d+(?:\\.\\d+)?)[xX]?)$")

        val ALL_DENSITIES = listOf(MDPI, HDPI, XHDPI, XXHDPI, XXXHDPI)

        fun fromFileName(name: String): Pair<Density, String> {
            val match = DENSITY_REGEX.find(name) ?: return DEFAULT to name
            val suffix = match.groupValues[1]
            val scale = match.groupValues[2].toDoubleOrNull() ?: return DEFAULT to name
            
            val density = when {
                scale <= 1.05 -> MDPI
                scale <= 1.6 -> HDPI
                scale <= 2.1 -> XHDPI
                scale <= 3.1 -> XXHDPI
                scale >= 3.9 -> XXXHDPI
                else -> DEFAULT
            }
            
            val baseName = name.substring(0, match.range.first)
            return density to baseName
        }
    }

    override fun toString(): String = displayName
}

data class ImportedDrawableItem(
    val file: VirtualFile,
    private val originalName: String = file.nameWithoutExtension,
    var doNotImport: Boolean = false,
    var convertSvg: Boolean = file.extension?.lowercase() == ResourceExtension.SVG.extension
) {
    private val densityData = Density.fromFileName(originalName)
    val detectedDensity: Density = densityData.first
    var density: Density = detectedDensity
    val baseName = densityData.second

    var name: String = baseName
    val suffix: String = if (originalName.length > baseName.length) originalName.substring(baseName.length) else ""

    val extension: String
        get() = if (convertSvg) "xml" else file.extension ?: ""

    fun applyPrefix(enabled: Boolean) {
        val prefix = when (file.extension?.lowercase()) {
            ResourceExtension.SVG.extension, ResourceExtension.XML.extension -> "ic_"
            else -> "img_"
        }
        name = if (enabled) {
            if (baseName.startsWith(prefix)) baseName else "${prefix}$baseName"
        } else {
            baseName
        }
    }
}
