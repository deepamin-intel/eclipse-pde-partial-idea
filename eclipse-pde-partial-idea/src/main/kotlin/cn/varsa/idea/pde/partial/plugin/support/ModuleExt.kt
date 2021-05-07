package cn.varsa.idea.pde.partial.plugin.support

import cn.varsa.idea.pde.partial.common.domain.*
import cn.varsa.idea.pde.partial.plugin.cache.*
import com.intellij.openapi.module.*
import org.jetbrains.kotlin.idea.util.*
import org.jetbrains.kotlin.idea.util.projectStructure.*

fun Module.isBundleRequiredOrFromReExport(symbolName: String): Boolean {
    val cacheService = BundleManifestCacheService.getInstance(project)

    val manifest = cacheService.getManifest(this) ?: return false
    val requiredBundle = manifest.requireBundle?.keys ?: return false

    // Bundle required directly
    requiredBundle.contains(symbolName).ifTrue { return true }

    val allRequiredFromReExport = requiredBundle.flatMap(cacheService::getReExportRequiredBundleBySymbolName).toSet()

    // Re-export dependency tree can resolve bundle
    allRequiredFromReExport.contains(symbolName).ifTrue { return true }

    // Re-export bundle contain module, it need calc again
    val modulesManifest = project.allModules().asSequence().filter { it.isLoaded }.filterNot { it == this }
        .mapNotNull(cacheService::getManifest).toHashSet()

    modulesManifest.filter { allRequiredFromReExport.contains(it.bundleSymbolicName?.key) }
        .any { isBundleFromReExportOnly(it, symbolName, cacheService, modulesManifest) }.ifTrue { return true }

    return false
}

private fun isBundleFromReExportOnly(
    manifest: BundleManifest,
    symbolName: String,
    cacheService: BundleManifestCacheService,
    modulesManifest: HashSet<BundleManifest>
): Boolean {
    // Re-export directly
    manifest.reExportRequiredBundleSymbolNames.contains(symbolName).ifTrue { return true }

    val allReExport =
        manifest.reExportRequiredBundleSymbolNames.flatMap { cacheService.getReExportRequiredBundleBySymbolName(it) }
            .toSet()

    // Re-export dependency tree can resolve bundle
    allReExport.contains(symbolName).ifTrue { return true }

    // Dependency tree contains module, it need calc again, and remove it from module set to not calc again and again and again
    return modulesManifest.filter { allReExport.contains(it.bundleSymbolicName?.key) }.also { modulesManifest -= it }
        .any { isBundleFromReExportOnly(it, symbolName, cacheService, modulesManifest) }
}
