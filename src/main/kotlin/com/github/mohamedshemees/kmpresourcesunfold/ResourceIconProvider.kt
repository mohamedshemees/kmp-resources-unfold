package com.github.mohamedshemees.kmpresourcesunfold

import com.android.ide.common.vectordrawable.VdPreview
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ImageLoader
import com.intellij.util.ui.ImageUtil
import java.awt.Image
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.UIManager

object ResourceIconProvider {
    private val iconCache = mutableMapOf<String, Icon>()
    private const val VECTOR_HARD_SIZE = 64

    fun clearCache() {
        iconCache.clear()
    }

    fun getIcon(file: VirtualFile, size: Int = VECTOR_HARD_SIZE): Icon {
        val cacheKey = "${file.path}_$size"
        return iconCache.getOrPut(cacheKey) {
            try {
                createIcon(file, size) ?: UIManager.getIcon("FileView.fileIcon")
            } catch (e: Throwable) {
                UIManager.getIcon("FileView.fileIcon")
            }
        }
    }

    private fun createIcon(file: VirtualFile, size: Int): Icon? {
        val ext = ResourceExtension.fromExtension(file.extension) ?: return null
        
        return when (ext) {
            ResourceExtension.PNG, ResourceExtension.JPG, ResourceExtension.JPEG, ResourceExtension.WEBP -> {
                file.inputStream.use { stream ->
                    val img = ImageIO.read(stream)
                    if (img != null) {
                        val maxDim = maxOf(img.width, img.height).toDouble()
                        val scale = if (maxDim > size) size / maxDim else 1.0
                        val scaled = img.getScaledInstance(
                            (img.width * scale).toInt(),
                            (img.height * scale).toInt(),
                            Image.SCALE_SMOOTH
                        )
                        ImageIcon(scaled)
                    } else null
                }
            }

            ResourceExtension.SVG -> {
                val xmlContent = SvgToXmlConverter.convertToXml(file.inputStream)
                renderVector(xmlContent, size)
            }

            ResourceExtension.XML -> {
                val xmlContent = String(file.contentsToByteArray(), Charsets.UTF_8)
                if (xmlContent.contains(ResourceConstants.VECTOR_TAG)) {
                    renderVector(xmlContent, size)
                } else null
            }
        }
    }

    private fun renderVector(xmlContent: String, size: Int): Icon? {
        val targetSize = VdPreview.TargetSize.createFromMaxDimension(size)
        val img = VdPreview.getPreviewFromVectorXml(targetSize, xmlContent, StringBuilder())
        return img?.let { ImageIcon(it) }
    }
}
