package com.ssafy.ddubi

import com.navercorp.nid.NidOAuth
import com.navercorp.nid.oauth.util.NidOAuthCallback
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private companion object {
        const val NAVER_LOGIN_CHANNEL = "com.ssafy.ddubi/naver_login"
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            NAVER_LOGIN_CHANNEL,
        ).setMethodCallHandler { call, result ->
            if (call.method != "login") {
                result.notImplemented()
                return@setMethodCallHandler
            }

            if (BuildConfig.NAVER_CLIENT_ID.isBlank() || BuildConfig.NAVER_CLIENT_SECRET.isBlank()) {
                result.error("NOT_CONFIGURED", "Naver login is not configured.", null)
                return@setMethodCallHandler
            }

            NidOAuth.initialize(
                applicationContext,
                BuildConfig.NAVER_CLIENT_ID,
                BuildConfig.NAVER_CLIENT_SECRET,
                BuildConfig.NAVER_CLIENT_NAME,
            )
            NidOAuth.setLogEnabled(false)
            NidOAuth.requestLogin(
                this,
                object : NidOAuthCallback {
                    override fun onSuccess() {
                        val accessToken = NidOAuth.getAccessToken()
                        if (accessToken.isNullOrBlank()) {
                            result.error("EMPTY_TOKEN", "Naver access token is empty.", null)
                        } else {
                            result.success(accessToken)
                        }
                    }

                    override fun onFailure(errorCode: String, errorDesc: String) {
                        val code = if (errorCode == "CLIENT_USER_CANCEL") "CANCELED" else "LOGIN_FAILED"
                        result.error(code, "Naver login failed.", null)
                    }
                },
            )
        }
    }
}
