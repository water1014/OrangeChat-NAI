/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.imggen

import android.content.Context
import java.io.File

/**
 * Vibe 参考图的落盘位置。
 *
 * 早期版本存在 appTempFolder（系统可随时清掉），导致「vibe 明明加过却突然没了」。
 * 现在统一放到应用私有 filesDir 下，卸载才清理。
 */
object VibeFiles {
    fun dir(context: Context): File =
        File(context.filesDir, "imggen_refs").apply { mkdirs() }

    fun save(context: Context, bytes: ByteArray, prefix: String = "vibe"): File {
        val file = File(dir(context), "${prefix}_${System.currentTimeMillis()}_${(1000..9999).random()}.png")
        file.writeBytes(bytes)
        return file
    }

    /** 把旧路径（temp）里的图迁到私有目录，返回新路径；失败则原样返回 */
    fun ensureLocal(context: Context, path: String): String {
        val targetDir = dir(context)
        val file = File(path)
        if (file.parentFile?.absolutePath == targetDir.absolutePath) return path
        if (!file.exists() || file.length() == 0L) return path
        return runCatching {
            val target = File(targetDir, file.name)
            if (!target.exists()) file.copyTo(target, overwrite = true)
            target.absolutePath
        }.getOrDefault(path)
    }

    fun isValid(path: String): Boolean = runCatching {
        val file = File(path)
        file.exists() && file.length() > 0
    }.getOrDefault(false)
}
