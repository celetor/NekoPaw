package com.antecer.nekopaw.api

import de.prosiebensat1digital.oasisjsbridge.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.*
import kotlin.collections.ArrayList

/**
 * 连接 OkHttp 和 QuickJS(JsBridge)
 */
@Suppress("unused")
class OkHttpToJS private constructor() {
    companion object {
        val instance: OkHttpToJS by lazy(mode = LazyThreadSafetyMode.SYNCHRONIZED) {
            OkHttpToJS()
        }
    }

    // 创建 OkHttp 对象
    val client: OkHttpClient = OkHttpClient()

    /**
     * 包装 okHttp 方法
     */
    private val okHttpKtApi = object : JsToNativeInterface {
        // 模拟 fetch 请求
        fun fetch(url: String, params: JsonObjectWrapper?): String {
            var finalUrl = url
            var status = ""
            var statusText = ""
            var error = ""
            var success = ""
            val failList = ArrayList<String>()  // 保存请求失败的 url
            val textList = ArrayList<String>()  // 保存请求成功的 response.body().string()
            try {
                // 取得参数
                val paramMap = params?.toPayloadObject()
                // 设置请求模式
                var method = "GET"
                // 设置默认 User-Agent
                val defAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.141 Safari/537.36"
                // 创建请求头
                val headerBuilder = Headers.Builder().add("user-agent", defAgent)
                // 创建请求类型
                var mediaType = "application/x-www-form-urlencoded"
                // 创建请求数据容器
                val bodyList = ArrayList<String>()

                if (paramMap != null) {
                    for (key in paramMap.keys) {
                        when (key.toLowerCase(Locale.ROOT)) {
                            "method" -> paramMap.getString(key)?.let { method = it.toUpperCase(Locale.ROOT) }
                            "body" -> paramMap.getString(key)?.let { bodyList.add(it) }
                            "bodys" -> {
                                paramMap.getArray(key)?.let { bodyArr ->
                                    for (i in 0 until bodyArr.count) bodyArr.getString(i)?.let { bodyList.add(it) }
                                }
                            }
                            "headers" -> {
                                paramMap.getObject(key)?.let { headers ->
                                    for (head in headers.keys) {
                                        if (head.toLowerCase(Locale.ROOT) == "content-type") {
                                            headers.getString(head)?.let { mediaType = it }
                                            continue
                                        }
                                        if (head.toLowerCase(Locale.ROOT) == "user-agent") headerBuilder.removeAll(head)
                                        headers.getString(head)?.let { headerBuilder.add(head.toLowerCase(Locale.ROOT), it) }
                                    }
                                }
                            }
                            else -> paramMap.getString(key)?.let { headerBuilder.add(key.toLowerCase(Locale.ROOT), it) }
                        }
                    }
                }

                if (bodyList.size == 0) bodyList.add("")
                for (body in bodyList) {
                    val requestBuilder = if (method == "GET") {
                        Request.Builder().url("$url?${body}").get()
                    } else {
                        Request.Builder().url(url).post(RequestBody.create(MediaType.parse(mediaType), body))
                    }
                    val response = client.newCall(requestBuilder.headers(headerBuilder.build()).build()).execute()
                    if (response.code() == 200) {
                        textList.add(response.body()!!.string())
                    } else {
                        failList.add("$method:$url?$body")
                        Timber.tag("OkHttp").d("[Failed] $method: $url?$body")
                        Timber.tag("OkHttp").d("Code: ${response.code()} | Msg: ${response.message()}")
                    }
                    finalUrl = response.request().url().toString()
                    status = response.code().toString()
                    statusText = response.message()
                }
                success = "ok"
            } catch (e: SocketTimeoutException) {
                Timber.tag("OkHttp").w("[TimeOut] ($url): $e")
                error = "timeout"
            } catch (t: Throwable) {
                t.printStackTrace()
                Timber.tag("OkHttp").e("[ERROR] ($url): $t")
                error = t.message ?: "Fetch出现未知错误"
            }
            val json = JSONObject()
            json.put("finalUrl", finalUrl)
            json.put("status", status)
            json.put("statusText", statusText)
            json.put("error", error)
            json.put("success", success)
            val textArr = JSONArray()
            textList.forEach { T -> textArr.put(T) }
            val failArr = JSONArray()
            failList.forEach { T -> failArr.put(T) }
            json.put("textArr", textArr)
            json.put("failArr", failArr)
            return json.toString()
        }

        // 并发网络请求 (待编写)
        fun fetchAll(actions: JsonObjectWrapper): String {
            var status = ""
            var statusText = ""
            var error = ""
            var success = ""
            val failList = ArrayList<String>()  // 保存请求失败的 url
            val textList = ArrayList<String>()  // 保存请求成功的 response.body().string()
            try {
                val actionArr = JSONArray(actions.jsonString)
                for (i in 0 until actionArr.length()) {
                    val action = actionArr.getJSONArray(i)
                    val url = action.getString(0)
                    val params = action.getJSONObject(1)
                }


                client.newCall(Request.Builder().url("https://www.baidu.com").get().build()).enqueue(object : Callback {
                    override fun onResponse(call: Call, response: Response) {}
                    override fun onFailure(call: Call, e: IOException) {}
                })


            } catch (t: Throwable) {
                t.printStackTrace()
                Timber.tag("OkHttp").e("[ERROR] $t")
            }
            return ""
        }
    }

    /**
     * 绑定到 JsBridge 对象
     * @param jsBridge 目标对象名称
     * @param name 注入到js内的名称
     */
    fun binding(jsBridge: JsBridge, apiName: String = "fetch") {
        // 修改 okHttp 并发数
        client.dispatcher().maxRequestsPerHost = 10
        // 注入 okHttp
        JsValue.fromNativeObject(jsBridge, okHttpKtApi).assignToGlobal("GlobalOkHttp")
        // 包装 js 方法
        val jsAPI = """
             class GlobalFetch {
                constructor(url, params) {
                    this.returnArr = params&&params.bodys ? true : false;
                    let FetchRes = JSON.parse(GlobalOkHttp.fetch(url, params));
                    this.finalUrl = FetchRes.finalUrl;
                    this.status = FetchRes.status;
                    this.statusText = FetchRes.statusText;
                    this.error = FetchRes.error;
                    this.success = FetchRes.success;
                    this.textArr = FetchRes.textArr;
                    this.failArr = FetchRes.failArr;
                }
                text() { return this.returnArr ? this.textArr : this.textArr[0]; }
                json() { return this.returnArr ? this.textArr.map(t=>JSON.parse(t)) : JSON.parse(this.textArr[0]); }
            }
            const $apiName = (url, params) => new GlobalFetch(url, params || null);
            console.debug('OkHttp 方法已注入为 $apiName');
        """.trimIndent()
        // 注入 js 包装的方法
        jsBridge.evaluateBlocking<Any>(jsAPI)
    }
}