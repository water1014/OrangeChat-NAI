/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import kotlin.io.encoding.Base64

class AIRequestInterceptor(
    /** OpenCode Zen 要求每个请求带 x-opencode-session，用于把同一对话路由到同一上游、保住 prompt 缓存 */
    private val openCodeSessionId: () -> String = { "" },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val host = request.url.host

        if (host.endsWith("opencode.ai")) {
            request = decorateOpenCode(request)
        }

//        if (host == "api.siliconflow.cn") {
//            request = processSiliconCloudRequest(request)
//        }

        return chain.proceed(request)
    }

    private fun decorateOpenCode(request: Request): Request {
        val builder = request.newBuilder()
        if (request.header("x-opencode-session") == null) {
            val session = openCodeSessionId()
            if (session.isNotBlank()) {
                builder.header("x-opencode-session", session)
            }
        }
        if (request.header("x-opencode-client") == null) {
            builder.header("x-opencode-client", "orangechat")
        }
        return builder.build()
    }

    // 处理硅基流动的请求
//    private fun processSiliconCloudRequest(request: Request): Request {
//        val authHeader = request.header("Authorization")
//        val path = request.url.encodedPath
//
//        // 如果没有设置api token, 填入免费api key
//        if ((authHeader?.trim() == "Bearer" || authHeader?.trim() == "Bearer sk-") && path in listOf(
//                "/v1/chat/completions",
//                "/v1/models"
//            )
//        ) {
//            val bodyJson = request.readBodyAsJson()
//            val model = bodyJson?.jsonObject["model"]?.jsonPrimitiveOrNull?.content
//            val freeModels = remoteConfig.getString("silicon_cloud_free_models").split(",")
//            if (model.isNullOrEmpty() || model in freeModels) {
//                val apiKey = String(Base64.decode(remoteConfig.getString("silicon_cloud_api_key")))
//                return request.newBuilder()
//                    .header("Authorization", "Bearer $apiKey")
//                    .build()
//            }
//        }
//
//        return request
//    }
}

//private fun Request.readBodyAsJson(): JsonElement? {
//    val contentType = body?.contentType()
//    if (contentType?.type == "application" && contentType.subtype == "json") {
//        val buffer = okio.Buffer()
//        buffer.use {
//            body?.writeTo(it)
//            return JsonInstant.parseToJsonElement(buffer.readUtf8())
//        }
//    }
//    return null
//}
