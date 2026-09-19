package com.fly.video.downloader.content.analyzer.app;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.fly.video.downloader.R;
import com.fly.video.downloader.bean.app.DouyinVideo;
import com.fly.video.downloader.contract.VideoParser;
import com.fly.video.downloader.core.contract.AbstractSingleton;
import com.fly.video.downloader.core.exception.URLInvalidException;
import com.fly.video.downloader.exception.VideoException;
import com.fly.video.downloader.util.Helpers;

import org.apache.commons.lang3.StringUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 终极方案：真机 WebView 拦截。
 *
 * 抖音所有公开 JSON 接口（iteminfo / SSR #RENDER_DATA）如今都需要 a_bogus / msToken 签名 + Cookie
 * 才能通过风控，纯静态 HTTP 请求无法伪造。本解析器改用隐藏 WebView 加载抖音页面，让抖音自身的 JS
 * 引擎去完成签名、过风控并起播，再通过 shouldInterceptRequest 拦截真正的视频流（douyinvod.com 的 mp4）
 * 直链。这是客户端唯一能长期稳定绕过风控的方式。
 */
public class DouyinWebViewParser extends VideoParser {
    private static final String TAG = DouyinWebViewParser.class.getSimpleName();
    private static final long TIMEOUT_SECONDS = 25;

    public DouyinWebViewParser(Context context) throws AbstractSingleton.SingletonException {
        super(context);
    }

    public static DouyinWebViewParser getInstance(Context context) {
        try {
            return AbstractSingleton.getInstance(DouyinWebViewParser.class, new Class<?>[]{Context.class}, new Object[]{context});
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public DouyinVideo get(String str) throws Throwable {
        final String url = Helpers.stripUrl(str);
        if (url == null)
            throw new URLInvalidException(this.getString(R.string.exception_invalid_url));

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> videoUrl = new AtomicReference<>(null);
        final AtomicReference<String> pageTitle = new AtomicReference<>("");
        final WebView[] holder = new WebView[1];

        final Handler main = new Handler(Looper.getMainLooper());
        main.post(new Runnable() {
            @SuppressLint("SetJavaScriptEnabled")
            @Override
            public void run() {
                try {
                    WebView web = new WebView(context.getApplicationContext());
                    holder[0] = web;

                    WebSettings settings = web.getSettings();
                    settings.setJavaScriptEnabled(true);
                    settings.setDomStorageEnabled(true);
                    settings.setUserAgentString(Helpers.getPhoneUa());
                    settings.setLoadsImagesAutomatically(false);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN)
                        settings.setMediaPlaybackRequiresUserGesture(false);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

                    web.setWebViewClient(new InterceptClient(videoUrl, pageTitle, latch));
                    web.loadUrl(url);
                } catch (Throwable e) {
                    Log.e(TAG, "webview init failed: " + e.getMessage(), e);
                    latch.countDown();
                }
            }
        });

        boolean finished = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        main.post(new Runnable() {
            @Override
            public void run() {
                if (holder[0] != null) {
                    try {
                        holder[0].stopLoading();
                        holder[0].loadUrl("about:blank");
                        holder[0].destroy();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });

        String captured = videoUrl.get();
        if (!finished || StringUtils.isEmpty(captured))
            throw new VideoException(this.getString(R.string.exception_html));

        DouyinVideo video = new DouyinVideo();
        String awemeId = extractAwemeId(url);
        video.setAweme_id(awemeId == null ? "" : awemeId);
        // id 需非空（isEmpty 依据 id 判断），无 aweme_id 时用时间戳兜底
        video.setId(StringUtils.isEmpty(awemeId) ? "dy" + System.currentTimeMillis() : awemeId);
        video.setUrl(captured);
        video.setOriginalUrl(url);
        String title = pageTitle.get();
        video.setTitle(StringUtils.isEmpty(title) ? "抖音视频" : title.replace(" - 抖音", "").trim());
        video.setContent(video.getTitle());

        return video;
    }

    private String extractAwemeId(String url) {
        if (StringUtils.isEmpty(url))
            return null;
        Matcher m = Pattern.compile("(?:video|note|share/video|modal)/(\\d{10,})").matcher(url);
        if (m.find())
            return m.group(1);
        m = Pattern.compile("(?:aweme_id|modal_id)=(\\d{10,})").matcher(url);
        if (m.find())
            return m.group(1);
        return null;
    }

    /** 判断某个网络请求是否为抖音视频流。 */
    private static boolean isVideoStream(String u) {
        if (StringUtils.isEmpty(u))
            return false;
        String low = u.toLowerCase();
        if (low.contains("douyinvod.com") || low.contains("douyincdn.com/obj") || low.contains("/video/tos/"))
            return true;
        if (low.contains("aweme/v1/play") || low.contains("media_type=4"))
            return true;
        return low.contains(".mp4") && low.startsWith("http");
    }

    private static class InterceptClient extends WebViewClient {
        private final AtomicReference<String> videoUrl;
        private final AtomicReference<String> pageTitle;
        private final CountDownLatch latch;

        InterceptClient(AtomicReference<String> videoUrl, AtomicReference<String> pageTitle, CountDownLatch latch) {
            this.videoUrl = videoUrl;
            this.pageTitle = pageTitle;
            this.latch = latch;
        }

        private void capture(String u) {
            if (videoUrl.get() == null && isVideoStream(u)) {
                videoUrl.set(u);
                latch.countDown();
            }
        }

        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (request != null && request.getUrl() != null)
                capture(request.getUrl().toString());
            return super.shouldInterceptRequest(view, request);
        }

        @SuppressWarnings("deprecation")
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            capture(url);
            return super.shouldInterceptRequest(view, url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            if (view.getTitle() != null)
                pageTitle.set(view.getTitle());
            // 主动触发起播，促使视频流请求尽快发出（部分机型不自动播放）
            String js = "(function(){var v=document.querySelector('video');if(v){v.muted=true;var p=v.play();}})();";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
                view.evaluateJavascript(js, null);
            else
                view.loadUrl("javascript:" + js);
        }
    }
}
