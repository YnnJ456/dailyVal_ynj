package com.example.dailyval

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

object RiotApiService {

    private val client = OkHttpClient()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    // 這是遊戲版本號，預設值
    var clientVersion = "release-11.10-12-4002057"

    private const val CLIENT_PLATFORM = "ew0KCSJwbGF0Zm9ybVR5cGUiOiAiUEMiLA0KCSJwbGF0Zm9ybU9TIjogIldpbmRvd3MiLA0KCSJwbGF0Zm9ybU9TVmVyc2lvbiI6ICIxMC4wLjE5MDQyLjEuMjU2LjY0Yml0IiwNCgkicGxhdGZvcm1DaGlwc2V0IjogIlVua25vd24iDQp9"
    // 這是目前的區域 (Shard)，預設 ap，我們會自動更新它
    var currentRegion = "ap"

    interface ApiCallback {
        fun onSuccess(result: String)
        fun onError(error: String)
    }

    // 0. 取得版本號 (原本的功能)
    fun fetchClientVersion(callback: ApiCallback) {
        val request = Request.Builder()
            .url("https://valorant-api.com/v1/version")
            .get()
            .build()
        sendRequest(request, "RiotVersion", callback)
    }

    // ▼▼▼ 新增功能：取得玩家區域 (Region/Shard) ▼▼▼
    fun fetchRegion(accessToken: String, idToken: String, callback: ApiCallback) {
        // 這裡需要把 id_token 包在 JSON 裡傳出去
        val jsonBody = JSONObject().apply {
            put("id_token", idToken)
        }
        val body = jsonBody.toString().toRequestBody(JSON)

        val request = Request.Builder()
            .url("https://riot-geo.pas.si.riotgames.com/pas/v1/product/valorant") // Riot Geo API
            .put(body) // 注意：這是 PUT 方法
            .addHeader("Authorization", "Bearer $accessToken")
            .build()

        sendRequest(request, "RiotRegion", object : ApiCallback {
            override fun onSuccess(result: String) {
                try {
                    // 解析回傳的區域
                    val json = JSONObject(result)
                    val affinities = json.getJSONObject("affinities")
                    val liveRegion = affinities.getString("live") // 例如 "ap", "na", "kr", "latam"

                    // 自動轉換 (因為 latam 和 br 通常是用 na 的網址)
                    currentRegion = when (liveRegion) {
                        "latam", "br" -> "na"
                        else -> liveRegion
                    }

                    Log.d("RiotRegion", "偵測到區域: $liveRegion -> 設定為: $currentRegion")
                    callback.onSuccess(result) // 通知外面成功了
                } catch (e: Exception) {
                    callback.onError("解析區域失敗: ${e.message}")
                }
            }

            override fun onError(error: String) {
                callback.onError(error)
            }
        })
    }
    // ▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲▲

    // 1. 取得 User Info
    fun fetchUserInfo(accessToken: String, callback: ApiCallback) {
        val body = "{}".toRequestBody(JSON)
        val request = Request.Builder()
            .url("https://auth.riotgames.com/userinfo")
            .post(body)
            .addHeader("Authorization", "Bearer $accessToken")
            .build()
        sendRequest(request, "RiotUserInfo", callback)
    }

    // 2. 取得 Entitlement Token
    fun fetchEntitlement(accessToken: String, callback: ApiCallback) {
        val body = "{}".toRequestBody(JSON)
        val request = Request.Builder()
            .url("https://entitlements.auth.riotgames.com/api/token/v1")
            .post(body)
            .addHeader("Authorization", "Bearer $accessToken")
            .build()
        sendRequest(request, "RiotEntitlement", callback)
    }

    // 3. 取得商店內容
    fun fetchStorefront(accessToken: String, entitlementToken: String, puuid: String, callback: ApiCallback) {
        // 使用自動偵測到的 currentRegion
        val cleanPuuid = puuid.trim()
        val url = "https://pd.$currentRegion.a.pvp.net/store/v2/storefront/$cleanPuuid"
        Log.d("RiotDebug", "檢查url: $url")
        Log.d("RiotDebug", "正在呼叫商店 API...")
        Log.d("RiotDebug", "使用區域: $currentRegion")
        Log.d("RiotDebug", "使用版本號: $clientVersion")

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Authorization", "Bearer $accessToken")
            .addHeader("X-Riot-Entitlements-JWT", entitlementToken)
            .addHeader("X-Riot-ClientPlatform", CLIENT_PLATFORM)
            .addHeader("X-Riot-ClientVersion", clientVersion)
            .build()
        sendRequest(request, "RiotStore", callback)
    }

    private fun sendRequest(request: Request, tag: String, callback: ApiCallback) {
        Log.d(tag, "開始發送請求...")
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val errorMsg = "連線失敗: ${e.message}"
                Log.e(tag, errorMsg)
                callback.onError(errorMsg)
            }

            override fun onResponse(call: Call, response: Response) {
                val responseData = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    Log.d(tag, "成功: $responseData")
                    callback.onSuccess(responseData)
                } else {
                    val errorMsg = "失敗 (Code ${response.code}): $responseData"
                    Log.e(tag, errorMsg)
                    callback.onError(errorMsg)
                }
            }
        })
    }
}