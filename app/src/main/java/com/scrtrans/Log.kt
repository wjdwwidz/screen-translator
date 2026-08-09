package com.scrtrans

import android.util.Log

const val TAG = "ScrTrans"

fun logd(msg: String) = Log.d(TAG, msg)
fun logi(msg: String) = Log.i(TAG, msg)
fun logw(msg: String, t: Throwable? = null) = Log.w(TAG, msg, t)
fun loge(msg: String, t: Throwable? = null) = Log.e(TAG, msg, t)
