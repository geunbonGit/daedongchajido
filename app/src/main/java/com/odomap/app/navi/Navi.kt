package com.odomap.app.navi

import android.content.Context
import android.util.Log
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener

/**
 * Pleos 내비에 길 안내를 맡긴다(NaviHelper 3.0.5 requestRoute). 처음 부를 때 서비스에 붙고, 붙지 못하면(내비가 없는 기기) onResult(false).
 * 결과는 첫 응답(경로 시작 / 오류)으로 알린다.
 */
class Navi(context: Context) {
    private val app = context.applicationContext
    private var helper: NaviHelper? = null
    private var ready = false
    private var pending: (() -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    private val listener =
        object : NaviHelperEventListener {
            override fun onError(errorCode: NaviErrorCode) {
                Log.w(TAG, "navi error $errorCode")
                val msg =
                    when (errorCode) {
                        NaviErrorCode.CAUSE_SEARCH_ROUTE_OVER_DISTANCE -> "너무 멀어서 길을 찾지 못했어요"
                        NaviErrorCode.CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE -> "너무 가까워서 길 안내를 하지 않아요"
                        else -> "길을 찾지 못했어요"
                    }
                onError?.invoke(msg)
            }
        }

    /** (lat, lng)로 길 안내. onResult(true) = 내비에 보냄, false = 내비가 없음 */
    fun route(lat: Double, lng: Double, name: String, address: String, onResult: (Boolean) -> Unit, onFail: (String) -> Unit) {
        onError = onFail
        val send = {
            runCatching {
                helper!!.requestRoute(
                    RouteInfo(
                        latitude = lat,
                        longitude = lng,
                        displayLatitude = lat,
                        displayLongitude = lng,
                        poiName = name,
                        poiId = "",
                        address = address,
                        poiSubId = "",
                        routeOption = RouteOption.RECOMMENDED,
                        poiProvider = "",
                        isMobileRequest = false,
                    ),
                )
            }.onFailure { Log.w(TAG, "requestRoute failed", it) }.isSuccess
        }
        if (ready && helper != null) {
            onResult(send())
            return
        }
        try {
            val h = helper ?: NaviHelper(app).also { helper = it }
            pending = { onResult(send()) }
            h.initialize(
                {
                    ready = true
                    runCatching { h.addListener(listener) }
                    pending?.invoke()
                    pending = null
                },
                { t ->
                    Log.w(TAG, "navi helper unavailable", t)
                    pending = null
                    helper = null
                    onResult(false)
                },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "navi helper unavailable", t)
            helper = null
            onResult(false)
        }
    }

    fun release() {
        runCatching {
            helper?.removeListener(listener)
            helper?.release()
        }
        helper = null
        ready = false
    }

    companion object {
        private const val TAG = "Navi"
    }
}
