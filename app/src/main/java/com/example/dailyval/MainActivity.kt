package com.example.dailyval

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ValorantApp()
        }
    }
}

@Composable
fun ValorantApp() {
    var accessToken by remember { mutableStateOf("") }
    // 我們需要多存一個 idToken
    var idToken by remember { mutableStateOf("") }

    var logMessage by remember { mutableStateOf("請點擊下方按鈕登入 Riot 帳號") }
    var showWebView by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

        if (accessToken.isEmpty() && !showWebView) {
            Text(text = "歡迎使用 Valorant 查店小幫手")
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = {
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                showWebView = true
            }) {
                Text("登入 Riot 帳號")
            }
        }

        if (showWebView) {
            LoginWebView(
                // 現在回傳兩個 Token
                onTokenFound = { token, id ->
                    accessToken = token
                    idToken = id // 存起來
                    showWebView = false
                    logMessage = "登入成功！\nToken: ${token.take(5)}...\nID: ${id.take(5)}...\n開始自動執行..."

                    // 把兩個 Token 都傳進去
                    runAutoApi(token, id) { newLog ->
                        logMessage += "\n$newLog"
                    }
                },
                onClose = { showWebView = false }
            )
        }

        if (accessToken.isNotEmpty()) {
            Text(text = "執行紀錄：", modifier = Modifier.padding(bottom = 8.dp))
            val scrollState = rememberScrollState()
            Text(
                text = logMessage,
                modifier = Modifier.fillMaxSize().verticalScroll(scrollState)
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
// 修改 Callback，讓它可以回傳兩個 String (access_token, id_token)
fun LoginWebView(onTokenFound: (String, String) -> Unit, onClose: () -> Unit) {
    val loginUrl = "https://auth.riotgames.com/authorize?redirect_uri=https%3A%2F%2Fplayvalorant.com%2Fopt_in&client_id=play-valorant-web-prod&response_type=token%20id_token&nonce=1&scope=account%20openid"
    val context = LocalContext.current

    val webViewLayout = remember {
        FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            addView(WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true

                webViewClient = object : WebViewClient() {
                    private fun checkUrlForToken(url: String): Boolean {
                        // 同時檢查 access_token 和 id_token
                        if (url.contains("access_token=") && url.contains("id_token=")) {
                            try {
                                // 1. 抓 Access Token
                                val startAcc = url.indexOf("access_token=") + "access_token=".length
                                val endAcc = url.indexOf("&", startAcc)
                                val accessToken = if (endAcc != -1) url.substring(startAcc, endAcc) else url.substring(startAcc)

                                // 2. 抓 ID Token
                                val startId = url.indexOf("id_token=") + "id_token=".length
                                val endId = url.indexOf("&", startId)
                                val idToken = if (endId != -1) url.substring(startId, endId) else url.substring(startId)

                                Log.d("RiotLogin", "🎉 抓到雙重 Token 了！")
                                onTokenFound(accessToken, idToken)
                                return true
                            } catch (e: Exception) {
                                Log.e("RiotLogin", "解析失敗: ${e.message}")
                            }
                        }
                        return false
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        url?.let { checkUrlForToken(it) }
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val url = request?.url?.toString()
                        if (url != null && checkUrlForToken(url)) {
                            return true
                        }
                        return super.shouldOverrideUrlLoading(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        url?.let { checkUrlForToken(it) }
                    }
                }
                loadUrl(loginUrl)
            })
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Button(onClick = onClose) { Text("取消 / 關閉") }

        AndroidView(
            factory = { webViewLayout },
            modifier = Modifier.fillMaxSize()
        )
    }
}

// 這裡也要改，接收兩個 Token
fun runAutoApi(token: String, idToken: String, updateLog: (String) -> Unit) {

    updateLog("步驟 0: 正在取得最新遊戲版本號...")

    RiotApiService.fetchClientVersion(object : RiotApiService.ApiCallback {
        override fun onSuccess(versionResult: String) {
            try {
                val jsonObject = JSONObject(versionResult)
                val newVersion = jsonObject.getJSONObject("data").getString("riotClientVersion")
                RiotApiService.clientVersion = newVersion
//                val fixedVersion = newVersion.replace("-shipping", "")

                RiotApiService.clientVersion = newVersion
//                RiotApiService.clientVersion = fixedVersion
                updateLog("✅ 取得版本號: $newVersion")

                // 版本號OK後，下一步：取得區域 (Region)
                // 我們把 idToken 傳進去
                fetchUserRegion(token, idToken, updateLog)

            } catch (e: Exception) {
                updateLog("⚠️ 版本號解析失敗，嘗試繼續...")
                fetchUserRegion(token, idToken, updateLog)
            }
        }

        override fun onError(error: String) {
            updateLog("⚠️ 取得版本號失敗，嘗試繼續...")
            fetchUserRegion(token, idToken, updateLog)
        }
    })
}

// 新增步驟：取得區域
fun fetchUserRegion(token: String, idToken: String, updateLog: (String) -> Unit) {
    updateLog("步驟 0.5: 正在偵測玩家區域...")

    RiotApiService.fetchRegion(token, idToken, object : RiotApiService.ApiCallback {
        override fun onSuccess(result: String) {
            updateLog("✅ 區域偵測完成: ${RiotApiService.currentRegion}")
            // 區域OK後，開始跑 UserInfo -> Entitlement -> Store
            startUserFlow(token, updateLog)
        }

        override fun onError(error: String) {
            updateLog("⚠️ 區域偵測失敗 ($error)，將使用預設值: ${RiotApiService.currentRegion}")
            startUserFlow(token, updateLog)
        }
    })
}

// 剩下的流程跟原本一樣
fun startUserFlow(token: String, updateLog: (String) -> Unit) {
    updateLog("步驟 1: 正在取得 PUUID...")

    RiotApiService.fetchUserInfo(token, object : RiotApiService.ApiCallback {
        override fun onSuccess(result: String) {
            try {
                val jsonObject = JSONObject(result)
                val puuid = jsonObject.getString("sub")
                val gameName = jsonObject.getJSONObject("acct").getString("game_name")

                updateLog("✅ 抓到玩家: $gameName")
                updateLog("步驟 2: 正在取得 Entitlement...")

                RiotApiService.fetchEntitlement(token, object : RiotApiService.ApiCallback {
                    override fun onSuccess(entResult: String) {
                        try {
                            val entJson = JSONObject(entResult)
                            val entToken = entJson.getString("entitlements_token")

                            updateLog("✅ Entitlement 取得成功")
                            updateLog("步驟 3: 正在查詢商店 (${RiotApiService.currentRegion})...")

                            RiotApiService.fetchStorefront(token, entToken, puuid, object : RiotApiService.ApiCallback {
                                override fun onSuccess(storeResult: String) {
                                    updateLog("🎉🎉🎉 商店資料載入完成！")
                                    updateLog(storeResult)
                                }
                                override fun onError(error: String) {
                                    updateLog("❌ 商店失敗: $error")
                                }

                            })
                        } catch (e: Exception) { updateLog("❌ 解析 Entitlement 失敗: ${e.message}") }
                    }
                    override fun onError(error: String) { updateLog("❌ Entitlement API 失敗: $error") }
                })
            } catch (e: Exception) { updateLog("❌ 解析 UserInfo 失敗: ${e.message}") }
        }
        override fun onError(error: String) { updateLog("❌ UserInfo API 失敗: $error") }
    })
}