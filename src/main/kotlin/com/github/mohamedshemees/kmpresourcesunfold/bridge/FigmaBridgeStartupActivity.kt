package com.github.mohamedshemees.kmpresourcesunfold.bridge

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class FigmaBridgeStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<FigmaBridgeService>().startServer()
    }
}
