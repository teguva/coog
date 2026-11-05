package tv.coog.app.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Shared OkHttp stack for CoogApi + Coil (one keep-alive pool). */
object CoogHttp {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
