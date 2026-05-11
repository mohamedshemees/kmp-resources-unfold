package com.github.mohamedshemees.kmpresourcesunfold.bridge

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class FigmaBridgeService(project: Project) : Disposable {
    private val server = FigmaBridgeServer(project)
    val activePort: Int?
        get() = server.activePort

    fun startServer() {
        server.start()
    }

    override fun dispose() {
        server.stop()
    }

    companion object {
        fun getInstance(project: Project): FigmaBridgeService = project.service()
    }
}
