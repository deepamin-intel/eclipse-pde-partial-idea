package cn.varsa.idea.pde.partial.plugin.listener

import cn.varsa.idea.pde.partial.common.*
import cn.varsa.idea.pde.partial.plugin.facet.*
import cn.varsa.idea.pde.partial.plugin.helper.*
import com.intellij.openapi.application.*
import com.intellij.openapi.module.*
import com.intellij.openapi.project.*
import com.intellij.packaging.artifacts.*
import com.intellij.util.Function

class ModuleChangedListener : ModuleListener {
    override fun moduleRemoved(project: Project, module: Module) {
        PDEFacet.getInstance(module) ?: return

        val model = ReadAction.compute<ModifiableArtifactModel, Exception> {
            ArtifactManager.getInstance(project).createModifiableModel()
        }
        try {
            model.findArtifact("$ArtifactPrefix${module.name}")?.also { model.removeArtifact(it) }

            ApplicationManager.getApplication().invokeAndWait {
                if (!project.isDisposed) WriteAction.run<Exception>(model::commit)
            }
        } finally {
            model.dispose()
        }
    }

    override fun modulesRenamed(
        project: Project, modules: MutableList<out Module>, oldNameProvider: Function<in Module, String>
    ) {
        modules.filter { PDEFacet.getInstance(it) != null }.forEach { module ->
            val model = ReadAction.compute<ModifiableArtifactModel, Exception> {
                ArtifactManager.getInstance(project).createModifiableModel()
            }
            try {
                model.findArtifact("$ArtifactPrefix${oldNameProvider.`fun`(module)}")?.also { model.removeArtifact(it) }

                ApplicationManager.getApplication().invokeAndWait {
                    if (!project.isDisposed) WriteAction.run<Exception>(model::commit)
                }
            } finally {
                model.dispose()
            }

            ModuleHelper.resetCompileArtifact(module)
        }
    }
}
