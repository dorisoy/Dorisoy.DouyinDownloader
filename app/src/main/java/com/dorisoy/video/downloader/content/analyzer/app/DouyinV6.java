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
import org.apache.commons.lang3.tuple.Pair;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;

import java.net.URLDecoder;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多策略解析器：
 * 1. 直接请求分享页/短链（OkHttp 自动跟随重定向），从 SSR 数据（#RENDER_DATA 或 _ROUTER_DATA）中深度搜索视频节点；
 * 2. SSR 无数据时回退到正则提取 playApi / play_addr / video_id；
 * 3. 仍失败时依次尝试 iesdouyin 分享页与 www.douyin.com 详情页的同类策略。
 */
public class DouyinV6 extends VideoParser {
    private static final String TAG = DouyinV6.class.getSimpleName();

    public DouyinV6(Context context) throws AbstractSingleton.SingletonException {
        super(context);
    }

    public static DouyinV6 getInstance(Context context) {
        try {
            return AbstractSingleton.getInstance(DouyinV6.class, new Class<?>[]{Context.class}, new Object[]{context});
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

        Pair<String, String> response = httpGet(url, true);
        String finalUrl = response.getKey();
        String html = response.getValue();

        DouyinVideo video = parseVideo(finalUrl, html);

        String awemeId = extractAwemeId(finalUrl, html);
        if ((video == null || video.isEmpty()) && awemeId != null) {
            try {
                Pair<String, String> share = httpGet("https://www.iesdouyin.com/share/video/" + awemeId + "/", true);
                video = parseVideo(share.getKey(), share.getValue());
            } catch (Throwable e) {
                Log.w(TAG, "share page failed: " + e.getMessage());
            }
        }
        if ((video == null || video.isEmpty()) && awemeId != null) {
            try {
                Pair<String, String> pc = httpGet("https://www.douyin.com/video/" + awemeId, false);
                video = parseVideo(pc.getKey(), pc.getValue());
            } catch (Throwable e) {
                Log.w(TAG, "pc page failed: " + e.getMessage());
            }
        }

        if (video == null || video.isEmpty())
            throw new VideoException(this.getString(R.string.exception_html));

        return video;
    }

    private DouyinVideo parseVideo(String url, String html) {
        DouyinVideo video = parseFromSsr(url, html);
        if (video == null || video.isEmpty())
            video = parseFromRegex(url, html);
        return (video != null && !video.isEmpty()) ? video : null;
    }

    private DouyinVideo parseFromSsr(String url, String html) {
        try {
            String jsonStr = Jsoup.parse(html).select("#RENDER_DATA").html();
            if (!StringUtils.isEmpty(jsonStr))
                jsonStr = URLDecoder.decode(jsonStr, "UTF-8");

            if (StringUtils.isEmpty(jsonStr)) {
                Matcher m = Pattern.compile("_ROUTER_DATA\\s*=\\s*(\\{.+?\\})\\s*;?\\s*</script>", Pattern.DOTALL).matcher(html);
                if (m.find())
                    jsonStr = m.group(1);
            }

            if (StringUtils.isEmpty(jsonStr))
                return null;

            String trimmed = jsonStr.trim();
            if (!trimmed.startsWith("{") && !trimmed.startsWith("["))
                return null;

            JSONObject node = findVideoNode(new JSONObject(trimmed), 0);
            return node == null ? null : buildVideo(url, node);
        } catch (Throwable e) {
            Log.w(TAG, "parseFromSsr: " + e.getMessage());
            return null;
        }
    }

    private JSONObject findVideoNode(Object node, int depth) {
        if (node == null || depth > 12)
            return null;

        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            if (isVideoNode(obj))
                return obj;

            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                JSONObject found = findVideoNode(obj.opt(keys.next()), depth + 1);
                if (found != null)
                    return found;
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject found = findVideoNode(arr.opt(i), depth + 1);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private boolean isVideoNode(JSONObject obj) {
        return (obj.has("video") && (obj.has("author") || obj.has("authorInfo")))
                || (obj.has("aweme") && obj.has("awemeId"));
    }

    private DouyinVideo buildVideo(String url, JSONObject obj) {
        JSONObject detail = obj;
        if (obj.has("aweme")) {
            JSONObject aweme = obj.optJSONObject("aweme");
            detail = aweme == null ? null : aweme.optJSONObject("detail");
            if (detail == null)
                return null;
        }

        JSONObject videoObj = detail.optJSONObject("video");
        String playUrl = firstUrl(videoObj, "play_addr", "playAddr");
        if (StringUtils.isEmpty(playUrl))
            playUrl = unescape(videoObj == null ? "" : videoObj.optString("playApi"));
        String vid = videoObj == null ? "" : videoObj.optString("vid");
        String awemeId = detail.optString("aweme_id", detail.optString("awemeId"));

        if (StringUtils.isEmpty(vid) && StringUtils.isEmpty(playUrl) && StringUtils.isEmpty(awemeId))
            return null;

        DouyinVideo video = new DouyinVideo();
        video.setAweme_id(awemeId);
        video.setId(StringUtils.isEmpty(vid) ? awemeId : vid);
        video.setUrl(playUrl.replaceAll("playwm", "play"));
        video.setOriginalUrl(url);
        video.setCoverUrl(firstUrlOrString(videoObj, "cover", "origin_cover", "originCover"));
        video.setDynamicCoverUrl(firstUrlOrString(videoObj, "dynamic_cover", "dynamicCover"));
        video.setTitle(detail.optString("desc"));
        video.setContent(video.getTitle());
        video.setWidth(videoObj == null ? 0 : videoObj.optInt("width"));
        video.setHeight(videoObj == null ? 0 : videoObj.optInt("height"));

        JSONObject author = detail.optJSONObject("author");
        if (author == null)
            author = detail.optJSONObject("authorInfo");
        if (author != null) {
            DouyinUser user = new DouyinUser();
            user.setId(author.optString("uid"));
            user.setNickname(author.optString("nickname"));
            user.setAvatarUrl(firstUrlOrString(author, "avatar_larger", "avatarUri"));
            video.setUser(user);
        }

        return video;
    }

    private DouyinVideo parseFromRegex(String url, String html) {
        String playUrl = firstMatch(html, "\"playApi\"\\s*:\\s*\"([^\"]+)\"");
        if (StringUtils.isEmpty(playUrl))
            playUrl = firstMatch(html, "\"play_addr\"\\s*:\\s*\\{[^\\}]*\"url_list\"\\s*:\\s*\\[\\s*\"([^\"]+)\"");
        playUrl = unescape(playUrl);

        String vid = firstMatch(html, "\"vid\"\\s*:\\s*\"([^\"]+)\"");
        if (StringUtils.isEmpty(vid))
            vid = firstMatch(html, "video_id=([0-9a-zA-Z_-]+)");

        if (StringUtils.isEmpty(playUrl) && StringUtils.isEmpty(vid))
            return null;

        String awemeId = extractAwemeId(url, html);

        DouyinVideo video = new DouyinVideo();
        video.setId(StringUtils.isEmpty(vid) ? awemeId : vid);
        video.setAweme_id(awemeId == null ? "" : awemeId);
        video.setUrl(playUrl.replaceAll("playwm", "play"));
        video.setOriginalUrl(url);

        String desc = unescape(firstMatch(html, "\"desc\"\\s*:\\s*\"([^\"]*)\""));
        video.setTitle(desc);
        video.setContent(desc);

        String cover = firstMatch(html, "\"originCover\"\\s*:\\s*\"([^\"]+)\"");
        if (StringUtils.isEmpty(cover))
            cover = firstMatch(html, "\"cover\"\\s*:\\s*\\{[^\\}]*\"url_list\"\\s*:\\s*\\[\\s*\"([^\"]+)\"");
        video.setCoverUrl(unescape(cover));

        DouyinUser user = new DouyinUser();
        user.setNickname(unescape(firstMatch(html, "\"nickname\"\\s*:\\s*\"([^\"]*)\"")));
        video.setUser(user);

        return video;
    }

    private String extractAwemeId(String url, String html) {
        Matcher m = Pattern.compile("(?:share/)?video/(\\d{10,})").matcher(url);
        if (m.find())
            return m.group(1);
        m = Pattern.compile("aweme_id=(\\d{10,})").matcher(url);
        if (m.find())
            return m.group(1);
        m = Pattern.compile("/modal/(\\d{10,})").matcher(url);
        if (m.find())
            return m.group(1);
        m = Pattern.compile("\"aweme_id\"\\s*:\\s*\"(\\d{10,})\"").matcher(html);
        if (m.find())
            return m.group(1);
        return null;
    }

    private String firstUrl(JSONObject obj, String... keys) {
        if (obj == null)
            return "";
        for (String key : keys) {
            Object child = obj.opt(key);
            if (child instanceof JSONObject) {
                JSONArray list = ((JSONObject) child).optJSONArray("url_list");
                if (list != null && list.length() > 0)
                    return unescape(list.optString(0));
            } else if (child instanceof JSONArray) {
                JSONArray list = (JSONArray) child;
                if (list.length() > 0)
                    return unescape(list.optString(0));
            }
        }
        return "";
    }

    private String firstUrlOrString(JSONObject obj, String... keys) {
        String url = firstUrl(obj, keys);
        if (!StringUtils.isEmpty(url))
            return url;
        if (obj == null)
            return "";
        for (String key : keys) {
            String value = unescape(obj.optString(key));
            if (!StringUtils.isEmpty(value))
                return value;
        }
        return "";
    }

    private String firstMatch(String html, String regex) {
        Matcher m = Pattern.compile(regex).matcher(html);
        return m.find() ? m.group(1) : "";
    }

    private String unescape(String s) {
        return s == null ? "" : s.replace("\\/", "/");
    }
}
