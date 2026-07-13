package com.github.mohamedshemees.kmpresourcesunfold.toolWindow

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class FontPreviewProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        val ext = file.extension?.lowercase()
        return ext == "ttf" || ext == "otf" || ext == "woff" || ext == "woff2"
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return FontPreviewEditor(file)
    }

    override fun getEditorTypeId(): String = "font-renderer"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
}
