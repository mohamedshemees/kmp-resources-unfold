package com.github.mohamedshemees.kmpresourcesunfold

import com.intellij.ide.IconProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import javax.swing.Icon

class ResourceFileIconProvider : IconProvider(), DumbAware {
    override fun getIcon(element: PsiElement, flags: Int): Icon? {
        if (element is PsiFileSystemItem && !element.isDirectory) {
            val virtualFile = element.virtualFile ?: return null
            val ext = virtualFile.extension?.lowercase()
            
            if (ResourceExtension.allExtensions.contains(ext)) {
                // Return a small 16x16 preview for the file tree
                return ResourceIconProvider.getIcon(virtualFile, 16)
            }
        }
        return null
    }
}
