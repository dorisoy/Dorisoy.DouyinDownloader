package com.dorisoy.video.downloader.layout.fragment;

import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.dorisoy.video.downloader.R;
import com.dorisoy.video.downloader.util.network.DownloadTaskRegistry;

import java.util.List;

public class DownloadTaskFragment extends Fragment {
    private DownloadTaskRecyclerViewAdapter adapter;
    private TextView emptyView;
    private final Handler handler = new Handler();
    private final Runnable refreshTasks = new Runnable() {
        @Override
        public void run() {
            if (adapter != null) {
                List<DownloadTaskRegistry.Task> tasks = DownloadTaskRegistry.getTasks();
                adapter.setTasks(tasks);
                if (emptyView != null)
                    emptyView.setVisibility(tasks.isEmpty() ? View.VISIBLE : View.GONE);
                handler.postDelayed(this, 1000);
            }
        }
    };

    public static DownloadTaskFragment newInstance() {
        return new DownloadTaskFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_download_task_list, container, false);
        RecyclerView recyclerView = view.findViewById(R.id.download_task_list);
        emptyView = view.findViewById(R.id.download_task_empty);
        recyclerView.setLayoutManager(new LinearLayoutManager(view.getContext()));
        adapter = new DownloadTaskRecyclerViewAdapter();
        recyclerView.setAdapter(adapter);
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.removeCallbacks(refreshTasks);
        handler.post(refreshTasks);
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(refreshTasks);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(refreshTasks);
        adapter = null;
        emptyView = null;
        super.onDestroyView();
    }
}
