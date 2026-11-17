package np.com.manjultamrakar.peephole.okhttp

import okhttp3.Interceptor
import okhttp3.Response

/** No-op variant for release builds. */
class PeepholeInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}
