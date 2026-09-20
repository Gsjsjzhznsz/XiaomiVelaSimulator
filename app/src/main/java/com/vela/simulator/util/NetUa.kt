package com.vela.simulator.util

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 统一网络 UA 工具：
 * okhttp 默认 UA（okhttp/x.y）会触发部分镜像站 / CDN 的 WAF 拦截（HTTP 403，
 * 用户真机在清华 TUNA 镜像实测复现），统一伪装为浏览器 UA 规避。
 * 所有下载链路（Termux 软件源 / GitHub Release 镜像资产）均应挂载本拦截器。
 */
object NetUa {

    /** 主 UA：现代 Chrome 桌面版（镜像站白名单覆盖率最高） */
    const val USER_AGENT: String =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"

    /** 给 OkHttpClient 挂载的 UA 注入拦截器（请求未显式设置 UA 时生效） */
    val interceptor = Interceptor { chain ->
        val req = chain.request().newBuilder()
            .header("User-Agent", USER_AGENT)
            .build()
        chain.proceed(req)
    }
}
