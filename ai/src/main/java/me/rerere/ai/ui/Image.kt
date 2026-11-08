/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.ai.ui

import kotlinx.serialization.Serializable

@Serializable
data class ImageGenerationResult(
    val items: List<ImageGenerationItem>, // 一个item代表一个图片
    /** 实际编码成功的参考图数量 */
    val vibeEncoded: Int = 0,
    /** 编码失败的参考图数量（文件损坏 / 接口拒绝） */
    val vibeFailed: Int = 0,
)

@Serializable
data class ImageGenerationItem(
    val data: String,
    val mimeType: String,
    /** NAI 实际使用的种子，便于复现；其它协议为 null */
    val seed: Long? = null,
)

@Serializable
enum class ImageAspectRatio {
    SQUARE,
    LANDSCAPE,
    PORTRAIT
}
