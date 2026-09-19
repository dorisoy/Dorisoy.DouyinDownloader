package com.fly.video.downloader.layout.fragment;

import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.fly.video.downloader.GlideApp;
import com.fly.video.downloader.R;
import com.fly.video.downloader.util.network.DownloadTaskRegistry;
import com.fly.video.downloader.util.network.Downloader;

import java.util.ArrayList;
import java.util.List;

public class DownloadTaskRecyclerViewAdapter extends RecyclerView.Adapter<DownloadTaskRecyclerViewAdapter.ViewHolder> {
    private List<DownloadTaskRegistry.Task> tasks = new ArrayList<>();

    public void setTasks(List<DownloadTaskRegistry.Task> tasks) {
        this.tasks = tasks;
        notifyDataSetChanged();
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.fragment_download_task_item, parent, false));
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        final DownloadTaskRegistry.Task task = tasks.get(position);
        final Downloader downloader = task.getDownloader();
        holder.title.setText(task.getVideo().getTitle());
        holder.status.setText(statusText(downloader));

        String coverUrl = task.getVideo().getCoverUrl();
        if (coverUrl != null && !coverUrl.isEmpty()) {
            GlideApp.with(holder.cover)
                    .load(coverUrl)
                    .placeholder(R.mipmap.ic_launcher)
                    .error(R.mipmap.ic_launcher)
                    .centerCrop()
                    .into(holder.cover);
        } else {
            holder.cover.setImageResource(R.mipmap.ic_launcher);
        }

        long total = downloader.getTotal();
        long loaded = downloader.getLoaded();
        holder.progress.setIndeterminate(total <= 0 && downloader.isDownloading());
        holder.progress.setProgress(total <= 0 ? 0 : (int) (loaded * 100 / total));
        holder.action.setVisibility(downloader.isDownloaded() ? View.GONE : View.VISIBLE);
        holder.action.setText(downloader.isError() || downloader.isCanceled() ? R.string.retry : R.string.cancel);
        holder.action.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    if (downloader.isError() || downloader.isCanceled()) {
                        downloader.retry();
                        downloader.start();
                    } else {
                        downloader.cancel();
                    }
                    notifyDataSetChanged();
                } catch (Exception ignored) {
                }
            }
        });
    }

    private String statusText(Downloader downloader) {
        if (downloader.isDownloaded()) return "已完成";
        if (downloader.isError()) return "下载失败，可重试";
        if (downloader.isCanceled()) return "已取消，可重试";
        if (downloader.isDownloading()) return "下载中 " + formatSize(downloader.getLoaded()) + " / " + formatSize(downloader.getTotal());
        return "等待下载";
    }

    private String formatSize(long bytes) {
        if (bytes <= 0) return "--";
        return String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / 1024f / 1024f);
    }

    @Override
    public int getItemCount() {
        return tasks.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView cover;
        final TextView title;
        final TextView status;
        final ProgressBar progress;
        final Button action;

        ViewHolder(View itemView) {
            super(itemView);
            cover = itemView.findViewById(R.id.download_task_cover);
            title = itemView.findViewById(R.id.download_task_title);
            status = itemView.findViewById(R.id.download_task_status);
            progress = itemView.findViewById(R.id.download_task_progress);
            action = itemView.findViewById(R.id.download_task_action);
        }
    }
}
