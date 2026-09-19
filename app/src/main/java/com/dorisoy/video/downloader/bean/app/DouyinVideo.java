package com.dorisoy.video.downloader.bean.app;

import com.dorisoy.video.downloader.bean.Video;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class DouyinVideo extends Video {

    protected String dynamicCoverUrl = null;
    protected String aweme_id = "0";

    public String getDynamicCoverUrl() {
        return dynamicCoverUrl;
    }

    public void setDynamicCoverUrl(String animateCoverUrl) {
        this.dynamicCoverUrl = animateCoverUrl;
    }

    public String getAweme_id() {
        return aweme_id;
    }

    public void setAweme_id(String aweme_id) {
        this.aweme_id = aweme_id;
    }

    public List<String> getCandidateUrls() {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (!StringUtils.isEmpty(url))
            urls.add(url.replace("playwm", "play"));

        String[] hosts = {"https://aweme.snssdk.com", "https://api.amemv.com"};
        String[] ratios = {"1080p", "720p"};
        for (String ratio : ratios) {
            for (String host : hosts) {
                for (int line = 0; line <= 1; line++) {
                    urls.add(host + "/aweme/v1/play/?video_id=" + id
                            + "&line=" + line + "&ratio=" + ratio
                            + "&media_type=4&vr_type=0&test_cdn=None&improve_bitrate=1");
                }
            }
        }
        return new ArrayList<>(urls);
    }

    @Override
    public String getUrl() {
        List<String> urls = getCandidateUrls();
        return urls.isEmpty() ? "" : urls.get(0);
    }

}
