package com.github.mohamedshemees.kmpresourcesunfold.toolWindow

import com.github.mohamedshemees.kmpresourcesunfold.MyBundle
import com.github.mohamedshemees.kmpresourcesunfold.ResourceConstants
import com.github.mohamedshemees.kmpresourcesunfold.ResourceExtension
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class VectorPreviewProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        val ext = file.extension?.lowercase()
        if (file.name == ResourceConstants.STRINGS_FILE) return false
        
        return ext == ResourceExtension.XML.extension && 
                (file.path.contains(ResourceConstants.DRAWABLE_DIR) || 
                 file.path.contains(ResourceConstants.COMPOSE_RESOURCES_DIR))
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val textEditor = TextEditorProvider.getInstance().createEditor(project, file)
        val previewEditor = VectorPreviewEditor(file)

        return TextEditorWithPreview(
            textEditor as com.intellij.openapi.fileEditor.TextEditor,
            previewEditor,
            MyBundle.message("editor.design"),
            TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW
        )
    }

    override fun getEditorTypeId(): String = "vector-renderer"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
