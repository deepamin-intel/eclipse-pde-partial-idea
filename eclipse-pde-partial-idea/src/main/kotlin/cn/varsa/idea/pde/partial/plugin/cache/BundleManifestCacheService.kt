package cn.varsa.idea.pde.partial.plugin.cache

import cn.varsa.idea.pde.partial.common.*
import cn.varsa.idea.pde.partial.common.domain.*
import cn.varsa.idea.pde.partial.common.support.*
import cn.varsa.idea.pde.partial.plugin.indexes.*
import cn.varsa.idea.pde.partial.plugin.support.*
import com.intellij.openapi.diagnostic.*
import com.intellij.openapi.module.*
import com.intellij.openapi.project.*
import com.intellij.openapi.roots.*
import com.intellij.openapi.vfs.*
import com.intellij.psi.*
import com.intellij.psi.util.*
import com.jetbrains.rd.util.*
import java.io.*
import java.util.jar.*
import kotlin.io.use

class BundleManifestCacheService(private val project: Project) {
    private val cachedValuesManager by lazy { CachedValuesManager.getManager(project) }

    // Key was manifest file path
    // will maintain key's relation to the same value on CacheValue update
    private val caches = ConcurrentHashMap<String, CachedValue<BundleManifest>>()
    private val lastIndexed = ConcurrentHashMap<String, BundleManifest>()

    companion object {
        fun getInstance(project: Project): BundleManifestCacheService =
            project.getService(BundleManifestCacheService::class.java)

        fun resolveManifest(mfFile: VirtualFile, stream: InputStream): BundleManifest? = try {
            Manifest(stream).let(BundleManifest::parse)
        } catch (e: Exception) {
            thisLogger().warn("$ManifestMf file not valid: $mfFile : $e")
            null
        }
    }

    fun clearCache() {
        caches.clear()
        lastIndexed.clear()
    }

    fun getManifest(psiClass: PsiClass): BundleManifest? = psiClass.containingFile?.let(this::getManifest)

    fun getManifest(item: PsiFileSystemItem): BundleManifest? {
        val file = item.virtualFile
        if (file != null) {
            val index = ProjectFileIndex.getInstance(item.project)
            val list = index.getOrderEntriesForFile(file)
            if (list.size == 1 && list.first() is JdkOrderEntry) return null

            val module = index.getModuleForFile(file)
            if (module != null) return getManifest(module)

            val libRoot = index.getClassRootForFile(file)
            if (libRoot != null) return getManifest(libRoot)
        }

        return null
    }

    fun getManifest(module: Module): BundleManifest? = readCompute { getManifestPsi(module)?.let(this::getManifest0) }
    fun getManifest(root: VirtualFile): BundleManifest? = readCompute { getManifestFile(root)?.let(this::getManifest0) }

    private fun getManifestPsi(module: Module): VirtualFile? =
        module.moduleRootManager.contentRoots.mapNotNull { it.findFileByRelativePath(ManifestPath) }.firstOrNull()

    private fun getManifestFile(root: VirtualFile): VirtualFile? =
        if (root.extension?.toLowerCase() == "jar" && root.fileSystem != JarFileSystem.getInstance()) {
            JarFileSystem.getInstance().getJarRootForLocalFile(root)
        } else {
            root
        }?.findFileByRelativePath(ManifestPath)

    private fun getManifest0(manifestFile: VirtualFile): BundleManifest? =
        DumbService.isDumb(project).runFalse { BundleManifestIndex.readBundleManifest(project, manifestFile) }
            ?.also { lastIndexed[manifestFile.presentableUrl] = it } ?: lastIndexed[manifestFile.presentableUrl]
        ?: caches.computeIfAbsent(manifestFile.presentableUrl) {
            cachedValuesManager.createCachedValue {
                CachedValueProvider.Result.create(readManifest(manifestFile), manifestFile)
            }
        }.value

    private fun readManifest(virtualFile: VirtualFile): BundleManifest? =
        virtualFile.inputStream.use { resolveManifest(virtualFile, it) }
}
