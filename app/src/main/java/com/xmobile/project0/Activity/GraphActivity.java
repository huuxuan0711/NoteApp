package com.xmobile.project0.Activity;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;
import com.xmobile.project0.DAO.NoteDao;
import com.xmobile.project0.DAO.NoteLinkDao;
import com.xmobile.project0.Database.NoteDatabase;
import com.xmobile.project0.Entities.Note;
import com.xmobile.project0.Entities.NoteLink;
import com.xmobile.project0.R;
import com.xmobile.project0.databinding.ActivityGraphBinding;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class GraphActivity extends BaseActivity {
    ActivityGraphBinding binding;
    WebView webView;

    private int centerNoteId;
    private boolean isDepthByHop = true; // true = Hop-based, false = Time-based
    private boolean viewFullGraph = true;
    private CompositeDisposable compositeDisposable = new CompositeDisposable();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityGraphBinding.inflate(getLayoutInflater());
        initControl();
        setContentView(binding.getRoot());
    }

    private void initControl() {
        setUpWebView();
        
        binding.imgBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
//                startActivity(new Intent(GraphActivity.this, MainActivity.class));
                finish();
            }
        });

        isDepthByHop = getIntent().getBooleanExtra("depth_by_hop", true);
        if (isDepthByHop) {
            binding.btnModeDepth.setText(getString(R.string.time_mode));
        }else {
            binding.btnModeDepth.setText(getString(R.string.hop_mode));
        }
        binding.btnModeDepth.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeMode();
            }
        });
    }

    private void changeMode() {
        Intent intent = new Intent(GraphActivity.this, GraphActivity.class);
        intent.putExtra("depth_by_hop", !isDepthByHop);
        startActivity(intent);
        finish();
    }

    private void setUpWebView(){
        webView = binding.webView;
        binding.webView.getSettings().setJavaScriptEnabled(true);
        binding.webView.setWebChromeClient(new WebChromeClient());
        binding.webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                String bgColor;
                int currentMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
                if (currentMode == Configuration.UI_MODE_NIGHT_YES){
                    bgColor = "#202020";
                    binding.webView.setBackgroundColor(Color.parseColor(bgColor));
                }
                loadGraphData();
            }
        });

        isDepthByHop = getIntent().getBooleanExtra("depth_by_hop", true);
        binding.webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge3");
        binding.webView.loadUrl("file:///android_asset/graph.html");
    }

    private String hashColor(String str) {
        int hash = 0;
        for (int i = 0; i < str.length(); i++) {
            hash = str.charAt(i) + ((hash << 5) - hash);
        }

        StringBuilder color = new StringBuilder("#");
        for (int i = 0; i < 3; i++) {
            int value = (hash >> (i * 8)) & 0xFF;
            String hex = Integer.toHexString(value);
            if (hex.length() == 1) {
                color.append('0');
            }
            color.append(hex);
        }

        return color.toString();
    }

    private void loadGraphData() {
        NoteDao noteDao = NoteDatabase.getDatabase(this).noteDao();
        NoteLinkDao linkDao = NoteDatabase.getDatabase(this).noteLinkDao();

        compositeDisposable.add(
                Single.zip(
                                noteDao.getAllNotesWithPos(),
                                linkDao.getAllNoteLinks(),
                                Pair::new
                        )
                        .subscribeOn(Schedulers.io())
                        .observeOn(Schedulers.computation())
                        .map(pair -> {
                            List<Note> allNotes = pair.first;
                            List<NoteLink> allLinks = pair.second;

                            // Tính degree (số liên kết của một note)
                            Map<Integer, Integer> degreeMap = new HashMap<>();
                            for (NoteLink link : allLinks) {
                                degreeMap.put(link.getIdFrom(), degreeMap.getOrDefault(link.getIdFrom(), 0) + 1);
                                degreeMap.put(link.getIdTo(), degreeMap.getOrDefault(link.getIdTo(), 0) + 1);
                            }

                            // Tìm centerNoteId (note có nhiều liên kết nhất)
                            centerNoteId = -1;
                            int maxDegree = -1;
                            for (Map.Entry<Integer, Integer> entry : degreeMap.entrySet()) {
                                if (entry.getValue() > maxDegree) {
                                    maxDegree = entry.getValue();
                                    centerNoteId = entry.getKey();
                                }
                            }

                            Map<String, Object> result = new HashMap<>();
                            result.put("allNotes", allNotes);
                            result.put("allLinks", allLinks);
                            result.put("degreeMap", degreeMap);

                            return result;
                        })
                        .flatMap(map -> {
                            List<Note> allNotes = (List<Note>) map.get("allNotes");
                            List<NoteLink> allLinks = (List<NoteLink>) map.get("allLinks");
                            Map<Integer, Integer> degreeMap = (Map<Integer, Integer>) map.get("degreeMap");

                            if (viewFullGraph || centerNoteId == -1) {
                                return Single.fromCallable(() -> buildGraphDataWithoutCenter(allNotes, allLinks, degreeMap));
                            } else {
                                return noteDao.getNoteWithId(centerNoteId)
                                        .defaultIfEmpty(null)
                                        .map(centerNote -> buildGraphDataWithCenter(centerNote, allNotes, allLinks, degreeMap));
                            }
                        })
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(pair -> {
                            String json = pair.first;
                            List<Note> allNotes = pair.second;

                            binding.webView.evaluateJavascript("loadGraph(" + json + ")", null);
                            LinearLayout legendLayout = findViewById(R.id.folderLegend);
                            legendLayout.removeAllViews();

                            Set<String> folderSet = new HashSet<>();
                            for (Note n : allNotes) {
                                folderSet.add(n.getFolderName());
                            }

                            for (String folder : folderSet) {
                                TextView tv = new TextView(this);
                                tv.setLayoutParams(new ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                ));
                                tv.setText(folder);
                                tv.setBackgroundColor(Color.parseColor(hashColor(folder)));
                                tv.setTextColor(getResources().getColor(R.color.lightGray));
                                tv.setPadding(10, 5, 10, 5);
                                legendLayout.addView(tv);
                            }
                        }, throwable -> {
                            Log.e("RxJava", "Lỗi khi tải dữ liệu đồ thị", throwable);
                            Toast.makeText(this, "Lỗi khi tải dữ liệu đồ thị: " + throwable.getMessage(), Toast.LENGTH_LONG).show();
                        })
        );
    }

    private Pair<String, List<Note>> buildGraphDataWithoutCenter(
            List<Note> allNotes,
            List<NoteLink> allLinks,
            Map<Integer, Integer> degreeMap
    ) {
        Map<Integer, Integer> depthMap = new HashMap<>();
        for (Note n : allNotes) depthMap.put(n.getId(), 0); //không có centerNote, mặc định depth = 0

        return buildFinalGraph(allNotes, allLinks, degreeMap, depthMap);
    }

    private Pair<String, List<Note>> buildGraphDataWithCenter(
            Note centerNote,
            List<Note> allNotes,
            List<NoteLink> allLinks,
            Map<Integer, Integer> degreeMap
    ) {
        Map<Integer, Integer> depthMap = new HashMap<>();
        Set<Integer> visited = new HashSet<>();

        if (isDepthByHop) { //tính toán độ sâu của từng note dựa trên độ sâu liên kết so với centerNote
            depthMap.put(centerNoteId, 0);
            visited.add(centerNoteId);
            Queue<Integer> queue = new LinkedList<>();
            queue.add(centerNoteId);

            while (!queue.isEmpty()) {
                int currentId = queue.poll();
                int currentDepth = depthMap.get(currentId);
                if (currentDepth >= 3) continue; //độ sâu tối đa là 3

                for (NoteLink link : allLinks) {
                    if (link.getIdFrom() == currentId) {
                        int targetId = link.getIdTo();
                        if (!visited.contains(targetId)) {
                            depthMap.put(targetId, currentDepth + 1);
                            visited.add(targetId);
                            queue.add(targetId);
                        }
                    }
                }
            }
        } else { //tính toán độ sâu của từng note dựa trên thời gian giữa centerNote và note
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm, dd/MM/yyyy", Locale.getDefault());
            try {
                Date centerDate = sdf.parse(centerNote.getDateTime());
                for (Note n : allNotes) {
                    Date noteDate = sdf.parse(n.getDateTime());
                    if (centerDate != null && noteDate != null) {
                        long diff = Math.abs(centerDate.getTime() - noteDate.getTime());
                        int depth = (int) (diff / (1000 * 60 * 60 * 24));
                        if (depth <= 3) {
                            depthMap.put(n.getId(), depth);
                        }
                    }
                }
            } catch (ParseException e) {
                e.printStackTrace();
            }
        }

        return buildFinalGraph(allNotes, allLinks, degreeMap, depthMap);
    }

    private Pair<String, List<Note>> buildFinalGraph(
            List<Note> allNotes,
            List<NoteLink> allLinks,
            Map<Integer, Integer> degreeMap,
            Map<Integer, Integer> depthMap
    ) {
        List<Map<String, Object>> notes = new ArrayList<>();
        List<Map<String, Object>> links = new ArrayList<>();

        for (Note note : allNotes) { // gửi tất cả note
            Map<String, Object> obj = new HashMap<>();
            obj.put("id", note.getId());
            obj.put("title", note.getTitle());
            obj.put("folder", note.getFolderName());
            obj.put("depth", depthMap.getOrDefault(note.getId(), 10)); // depth mặc định 10 nếu ngoài depthMap
            obj.put("degree", degreeMap.getOrDefault(note.getId(), 1));
            notes.add(obj);
        }

        for (NoteLink link : allLinks) {
            links.add(Map.of(
                    "from", link.getIdFrom(),
                    "to", link.getIdTo()
            ));
        }

        Map<String, Object> fullData = new HashMap<>();
        fullData.put("centerNoteId", centerNoteId);
        fullData.put("notes", notes);
        fullData.put("links", links);
        fullData.put("viewFullGraph", viewFullGraph);

        return new Pair<>(new Gson().toJson(fullData), allNotes);
    }

    private class AndroidBridge {
        @JavascriptInterface
        public void onNodeClicked(int noteId) {
            runOnUiThread(() -> {
                // Mở ghi chú tương ứng
                Intent intent = new Intent(GraphActivity.this, NoteActivity.class);
                intent.putExtra("idNote", noteId);
                intent.putExtra("isUpdate", true);
                Log.d("GraphActivity", "" + noteId);
                startActivity(intent);
                finish();
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadGraphData();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        webView.loadUrl("about:blank");
        webView.stopLoading();
        webView.setWebChromeClient(null);
        webView.setWebViewClient(null);
        webView.clearHistory();
        webView.clearCache(true);
        webView.removeAllViews();
        webView.destroy();
        webView = null;
        binding = null;
        compositeDisposable.clear();
    }
}

//cytoscape graph + javascript làm tính năng đồ thị liên kết note
//lỗi richeditor chưa lấy được content ngay