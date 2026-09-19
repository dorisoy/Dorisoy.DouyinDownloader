package com.dorisoy.video.downloader.content.analyzer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

import com.dorisoy.video.downloader.R;
import com.dorisoy.video.downloader.bean.Video;
import com.dorisoy.video.downloader.content.analyzer.app.AnyVideoV1;
import com.dorisoy.video.downloader.content.analyzer.app.DouyinApiV7;
import com.dorisoy.video.downloader.content.analyzer.app.DouyinV4;
import com.dorisoy.video.downloader.content.analyzer.app.DouyinV5;
import com.dorisoy.video.downloader.content.analyzer.app.DouyinV6;
import com.dorisoy.video.downloader.content.analyzer.app.DouyinWebViewParser;
import com.dorisoy.video.downloader.contract.VideoParser;
import com.dorisoy.video.downloader.core.exception.URLInvalidException;
import com.dorisoy.video.downloader.core.os.AsyncTaskResult;
import com.dorisoy.video.downloader.exception.VideoException;
import com.dorisoy.video.downloader.util.Helpers;

import java.util.ArrayList;
import java.util.List;

public class AnalyzerTask extends AsyncTask<String, Integer, AsyncTaskResult<Video>>  {
    private static final String TAG = AnalyzerTask.class.getSimpleName();

    @SuppressLint("StaticFieldLeak")
    private Context context;
    private AnalyzeListener listener;

    public AnalyzerTask(Context context, AnalyzeListener listener)
    {
        this.context = context;
        this.listener = listener;
    }

    @Override
    protected AsyncTaskResult<Video> doInBackground(String ...params) {
        String str = params[0];
        List<VideoParser> parsers = new ArrayList<>();

        if (Helpers.containsVideoUrl(context, str)) {
            // 终极方案：静态接口已普遍被风控，优先用真机 WebView 拦截视频流
            parsers.add(DouyinWebViewParser.getInstance(this.context));
            parsers.add(DouyinApiV7.getInstance(this.context));
            parsers.add(DouyinV6.getInstance(this.context));
            parsers.add(DouyinV5.getInstance(this.context));
            parsers.add(DouyinV4.getInstance(this.context));
            parsers.add(AnyVideoV1.getInstance(this.context));
        }

        try {
            if (parsers.isEmpty())
                throw new URLInvalidException(this.context.getString(R.string.exception_invalid_url));

            Video video = null;
            Throwable lastError = null;

            for (VideoParser parser : parsers) {
                if (parser == null)
                    continue;

                try {
                    video = parser.get(str);
                    if (video != null && !video.isEmpty())
                        break;
                    video = null;
                } catch (Throwable e) {
                    lastError = e;
                    Log.w(TAG, parser.getClass().getSimpleName() + " 解析失败: " + e.getMessage());
                }
            }

            if (video == null || video.isEmpty()) {
                String detail = lastError != null && lastError.getMessage() != null ? lastError.getMessage() : "";
                throw new VideoException(this.context.getString(R.string.exception_all_parsers_failed, detail));
            }

            return new AsyncTaskResult<>(video);

        } catch (Throwable e) {
            return new AsyncTaskResult<>(e);
        }

    }

    @Override
    protected void onCancelled() {
        super.onCancelled();
        context = null;
    }

    @Override
    protected void onPostExecute(AsyncTaskResult<Video> result) {
        super.onPostExecute(result);

        if (isCancelled())
            listener.onAnalyzeCanceled();
        else if (result.getError() != null)
            listener.onAnalyzeError(result.getError());
        else
            listener.onAnalyzed(result.getResult());

        context = null;

    }

    public interface AnalyzeListener {
        void onAnalyzed(Video video);
        void onAnalyzeCanceled();
        void onAnalyzeError(Throwable e);
    }
}
