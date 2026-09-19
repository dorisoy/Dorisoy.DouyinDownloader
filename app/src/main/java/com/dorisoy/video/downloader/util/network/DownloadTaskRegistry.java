package com.dorisoy.video.downloader.util.network;

import com.dorisoy.video.downloader.bean.Video;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 在进程生命周期内向下载页面公开当前任务。 */
public final class DownloadTaskRegistry {
    private static final Map<String, Task> TASKS = new LinkedHashMap<>();

    private DownloadTaskRegistry() {
    }

    public static synchronized void put(String id, Video video, Downloader downloader) {
        TASKS.put(id, new Task(id, video, downloader));
    }

    public static synchronized List<Task> getTasks() {
        return new ArrayList<>(TASKS.values());
    }

    public static synchronized Task get(String id) {
        return TASKS.get(id);
    }

    public static final class Task {
        private final String id;
        private final Video video;
        private final Downloader downloader;

        Task(String id, Video video, Downloader downloader) {
            this.id = id;
            this.video = video;
            this.downloader = downloader;
        }

        public String getId() {
            return id;
        }

        public Video getVideo() {
            return video;
        }

        public Downloader getDownloader() {
            return downloader;
        }
    }
}
