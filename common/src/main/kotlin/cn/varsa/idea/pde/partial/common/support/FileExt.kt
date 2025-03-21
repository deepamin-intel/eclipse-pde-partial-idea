package cn.varsa.idea.pde.partial.common.support

import java.io.*

val File.protocolUrl: String get() = toURI().toURL().toString()

fun String.toFile(): File = File(this)
fun String.toFile(child: String): File = File(this, child)

fun File.touchFile(): File = this.apply {
    parentFile.makeDirs()
    if (!exists()) createNewFile()
}

fun File.makeDirs(): File = this.apply { if (!exists()) mkdirs() }
