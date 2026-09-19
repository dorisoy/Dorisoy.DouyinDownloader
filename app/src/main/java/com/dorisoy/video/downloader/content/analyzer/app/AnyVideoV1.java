package com.dorisoy.video.downloader.content.analyzer.app;

import android.content.Context;

import com.dorisoy.video.downloader.R;
import com.dorisoy.video.downloader.bean.app.AnyUser;
import com.dorisoy.video.downloader.bean.app.AnyVideo;
import com.dorisoy.video.downloader.contract.VideoParser;
import com.dorisoy.video.downloader.core.contract.AbstractSingleton;
import com.dorisoy.video.downloader.core.contract.Jsonable;
import com.dorisoy.video.downloader.core.exception.URLInvalidException;
import com.dorisoy.video.downloader.core.security.Encrypt;
import com.dorisoy.video.downloader.exception.VideoException;
import com.dorisoy.video.downloader.util.Helpers;

import org.apache.commons.lang3.tuple.Pair;

import java.net.URLEncoder;

public class AnyVideoV1 extends VideoParser {

    public AnyVideoV1(Context context) throws AbstractSingleton.SingletonException {
        super(context);
    }

    public static AnyVideoV1 getInstance(Context context) {
        try {
            return AbstractSingleton.getInstance(AnyVideoV1.class, new Class<?>[]{Context.class}, new Object[]{context});
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public AnyVideo get(String str) throws Throwable {
        String originalUrl = Helpers.stripUrl(str);
        if (originalUrl == null)
            throw new URLInvalidException(this.getString(R.string.exception_invalid_url));

        String url = originalUrl;
        if (originalUrl.contains("v.ixigua.com")) {
             url = redirectUrl(originalUrl, false);
        }

        Pair<String, String> response = httpGet("https://tenapi.cn/video/?url=" + URLEncoder.encode(url, "utf-8"), false);

        return parseVideo(originalUrl, response.getValue());
    }

    private AnyVideo parseVideo(String url, String html) throws Throwable {

        record record = Jsonable.fromJson(record.class, ensureJson(html));

        if (record == null || record.code != 200 || record.url == null || record.url.isEmpty()) {
            throw new VideoException(record == null || record.msg == null ?
                    this.getString(R.string.exception_invalid_video) : record.msg);
        }
        AnyVideo video = new AnyVideo();
        video.setId(Encrypt.MD5(url));

        video.setOriginalUrl(url);
        video.setUrl(record.url);
        video.setTitle(record.title);
        video.setCoverUrl(record.cover);

        AnyUser user = new AnyUser();
        video.setUser(user);

        return video;
    }

    public static class record {
        public int code;
        public String msg;
        public String title;
        public String cover;
        public String url;
        public String music;
    }
}