package com.pabl0sk1.jarvis

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Cliente HTTP compartido (cerebros, herramientas y voz). */
val HTTP: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .build()
