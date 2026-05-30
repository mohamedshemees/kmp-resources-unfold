package com.github.mohamedshemees.kmpresourcesunfold.bridge

import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.testFramework.BinaryLightVirtualFile
import java.io.ByteArrayInputStream
import java.io.InputStream

class FigmaInMemoryFile(
    fileName: String,
    private val content: ByteArray
) : BinaryLightVirtualFile(
    fileName,
    FileTypeRegistry.getInstance().getFileTypeByFileName(fileName),
    content
) {
    override fun getInputStream(): InputStream = ByteArrayInputStream(content)
    override fun contentsToByteArray(): ByteArray = content
    override fun getLength(): Long = content.size.toLong()
}
