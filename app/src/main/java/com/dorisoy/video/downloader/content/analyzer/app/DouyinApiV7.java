package com.dorisoy.video.downloader.content.analyzer.app;

import android.content.Context;
import android.util.Log;

import com.dorisoy.video.downloader.R;
import com.dorisoy.video.downloader.bean.app.DouyinUser;
import com.dorisoy.video.downloader.bean.app.DouyinVideo;
import com.dorisoy.video.downloader.contract.VideoParser;
import com.dorisoy.video.downloader.core.contract.AbstractSingleton;
import com.dorisoy.video.downloader.core.exception.URLInvalidException;
import com.dorisoy.video.downloader.exception.VideoException;
import com.dorisoy.video.downloader.util.Helpers;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 经典移动端 API 解析器：
 * 1. 解析分享链接：从分享文案提取短链，跟随重定向拿到含视频唯一 ID（aweme_id）的最终地址，再用正则提取 ID；
 * 2. 模拟移动端 UA 请求接口：用手机 User-Agent 伪装成真实设备，访问抖音移动端 iteminfo API 获取视频详情 JSON；
 * 3. 参数替换去水印：接口返回的播放地址含 playwm（wm = WaterMark），替换为 play 即得无水印直链。
 */
public class DouyinApiV7 extends VideoParser {
    private static final String TAG = DouyinApiV7.class.getSimpleName();

    /** 抖音移动端视频详情接口，item_ids 传 aweme_id 即可返回干净 JSON。 */
    private static final String[] ITEM_INFO_APIS = new String[]{
            "https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=",
            "https://aweme.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=",
    };

    public DouyinApiV7(Context context) throws AbstractSingleton.SingletonException {
        super(context);
    }

    public static DouyinApiV7 getInstance(Context context) {
        try {
            return AbstractSingleton.getInstance(DouyinApiV7.class, new Class<?>[]{Context.class}, new Object[]{context});
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public DouyinVideo get(String str) throws Throwable {
        String url = Helpers.stripUrl(str);
        if (url == null)
            throw new URLInvalidException(this.getString(R.string.exception_invalid_url));

        // 1. 解析分享链接 -> 视频唯一 ID
        String awemeId = resolveAwemeId(url);
        if (StringUtils.isEmpty(awemeId))
            throw new VideoException(this.getString(R.string.exception_html));

        // 2. 模拟移动端 UA 请求接口，获取视频详情 JSON
        Throwable lastError = null;
        for (String api : ITEM_INFO_APIS) {
            try {
                String body = getJson(api + awemeId);
                DouyinVideo video = parseItemInfo(url, body);
                if (video != null && !video.isEmpty())
                    return video;
            } catch (Throwable e) {
                lastError = e;
                Log.w(TAG, "iteminfo api failed: " + e.getMessage());
            }
        }

        if (lastError instanceof VideoException)
            throw lastError;
        throw new VideoException(this.getString(R.string.exception_html));
    }

    /** 跟随短链重定向，并从最终地址 / 原文中提取纯数字的视频唯一 ID。 */
    private String resolveAwemeId(String url) {
        String id = matchAwemeId(url);
        if (!StringUtils.isEmpty(id))
            return id;

        // 短链需要跟随重定向拿到含 aweme_id 的真实地址
        String finalUrl = redirectUrl(url, true);
        id = matchAwemeId(finalUrl);
        return id;
    }

    private String matchAwemeId(String url) {
        if (StringUtils.isEmpty(url))
            return null;
        Matcher m = Pattern.compile("(?:video|note|share/video|modal)/(\\d{10,})").matcher(url);
        if (m.find())
            return m.group(1);
        m = Pattern.compile("(?:aweme_id|item_ids|modal_id)=(\\d{10,})").matcher(url);
        if (m.find())
            return m.group(1);
        m = Pattern.compile("/(\\d{15,})").matcher(url);
        if (m.find())
            return m.group(1);
        return null;
    }

    /** 3. 解析 iteminfo JSON，并把带水印的 playwm 直链替换为无水印的 play。 */
    private DouyinVideo parseItemInfo(String originalUrl, String body) throws VideoException, JSONException {
        JSONObject root = new JSONObject(ensureJson(body));
        JSONArray items = root.optJSONArray("item_list");
        if (items == null || items.length() == 0)
            return null;

        JSONObject item = items.optJSONObject(0);
        if (item == null)
            return null;

        JSONObject videoObj = item.optJSONObject("video");
        if (videoObj == null)
            return null;

        DouyinVideo video = new DouyinVideo();
        video.setOriginalUrl(originalUrl);
        video.setAweme_id(item.optString("aweme_id"));

        // play_addr.uri 即 video_id，用于拼装 snssdk 无水印直链
        JSONObject playAddr = videoObj.optJSONObject("play_addr");
        String vid = playAddr == null ? "" : playAddr.optString("uri");
        String playUrl = firstUrl(playAddr);
        // 去水印：playwm -> play
        playUrl = removeWatermark(playUrl);

        video.setId(StringUtils.isEmpty(vid) ? video.getAweme_id() : vid);
        video.setUrl(playUrl);
        video.setWidth(videoObj.optInt("width"));
        video.setHeight(videoObj.optInt("height"));
        video.setCoverUrl(firstUrl(videoObj.optJSONObject("origin_cover"), videoObj.optJSONObject("cover")));
        video.setDynamicCoverUrl(firstUrl(videoObj.optJSONObject("dynamic_cover")));

        String desc = item.optString("desc");
        video.setTitle(desc);
        video.setContent(desc);

        JSONObject author = item.optJSONObject("author");
        if (author != null) {
            DouyinUser user = new DouyinUser();
            user.setId(author.optString("uid"));
            user.setNickname(author.optString("nickname"));
            user.setAvatarUrl(firstUrl(author.optJSONObject("avatar_larger"), author.optJSONObject("avatar_thumb")));
            video.setUser(user);
        }

        return video;
    }

    private String removeWatermark(String url) {
        if (StringUtils.isEmpty(url))
            return "";
        return url.replace("playwm", "play").replace("\\u002F", "/").replace("\\/", "/");
    }

    /** 从若干候选对象中取第一个非空的 url_list 首元素。 */
    private String firstUrl(JSONObject... objs) {
        for (JSONObject obj : objs) {
            if (obj == null)
                continue;
            JSONArray list = obj.optJSONArray("url_list");
            if (list != null && list.length() > 0) {
                String u = list.optString(0);
                if (!StringUtils.isEmpty(u))
                    return u.replace("\\u002F", "/").replace("\\/", "/");
            }
        }
        return "";
    }

    /** 用移动端 UA + Referer 请求 JSON 接口，伪装成真实手机设备。 */
    private String getJson(String url) throws IOException, VideoException {
        Request request = new Request.Builder()
                .header("User-Agent", Helpers.getPhoneUa())
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", "https://www.iesdouyin.com/")
                .url(url)
                .build();

        Response response = new OkHttpClient().newCall(request).execute();
        String body = "";
        if (response.body() != null) {
            body = response.body().string();
            response.body().close();
        }
        if (StringUtils.isEmpty(body))
            throw new VideoException(this.getString(R.string.exception_http));
        return body;
    }
}
