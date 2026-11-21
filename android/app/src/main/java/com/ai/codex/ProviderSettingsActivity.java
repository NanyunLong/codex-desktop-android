package com.ai.codex;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API 服务商配置（内置版 CC Switch）。
 * 直接读写 Linux 桌面端家目录下的 .codex/config.toml 与 provider-switch.json。
 * 切换逻辑对齐 cc-switch-android 的 Codex 投影：model_provider + [model_providers.<id>]，
 * API Key 经 experimental_bearer_token 注入，不触碰 auth.json（保留官方登录）。
 */
public final class ProviderSettingsActivity extends androidx.appcompat.app.AppCompatActivity {

    private LinearLayout content;
    private List<JSONObject> providers = new ArrayList<>();
    private Path codexDir;
    private Path configFile;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        render();
    }

    @Override public void onResume() { super.onResume(); render(); }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (22 * getResources().getDisplayMetrics().density);
        content.setPadding(pad, pad, pad, pad);
        scroll.addView(content);
        setContentView(scroll);

        try {
            codexDir = CodexStoragePaths.codexHome(this);
            configFile = codexDir.resolve("config.toml");
        } catch (Exception error) {
            text("API 服务商配置", 25);
            text("无法定位 .codex 目录：" + error.getMessage(), 16);
            text("请先启动 Codex 桌面一次，完成运行环境与 .codex 目录初始化后再回来。", 16);
            button("返回", () -> finish());
            return;
        }

        loadProviders();
        String activeId = readActiveProviderId();

        text("API 服务商配置", 25);
        text("配置写入 .codex/config.toml，切换后重启 Codex 桌面生效。修改前会自动备份 config.toml。", 15);
        text("当前生效：" + (activeId == null ? "（未由本页切换）" : displayName(activeId)), 18);

        for (int i = 0; i < providers.size(); i++) {
            JSONObject p = providers.get(i);
            String id = p.optString("id", "p" + i);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            int pad2 = (int) (12 * getResources().getDisplayMetrics().density);
            card.setPadding(pad2, pad2, pad2, pad2);
            card.setBackgroundColor(0x14118888);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.setMargins(0, (int) (8 * getResources().getDisplayMetrics().density), 0, 0);
            card.setLayoutParams(cardLp);

            TextView title = new TextView(this);
            title.setText(("".equals(id) || id.equals(activeId) ? "✓ " : "") + p.optString("name", "未命名")
                    + "   ·   " + p.optString("model", "") + "   ·   " + p.optString("wireApi", "responses"));
            title.setTextSize(17);
            card.addView(title);
            TextView sub = new TextView(this);
            sub.setText(p.optString("baseUrl", ""));
            sub.setTextSize(14);
            card.addView(sub);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(smallButton("启用", () -> activate(i)));
            row.addView(smallButton("编辑", () -> editDialog(i, null)));
            row.addView(smallButton("删除", () -> delete(i)));
            card.addView(row);
            content.addView(card);
        }

        if (providers.isEmpty()) text("还没有服务商，点下面添加。", 16);
        button("添加服务商", () -> editDialog(-1, null));
        button("返回 Codex", () -> finish());
    }

    // ---------- UI helpers（风格对齐 AndroidToolsActivity） ----------

    private void text(String s, float size) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(size);
        tv.setPadding(0, (int) (6 * getResources().getDisplayMetrics().density), 0, (int) (6 * getResources().getDisplayMetrics().density));
        content.addView(tv);
    }

    private void button(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
        content.addView(b);
    }

    private Button smallButton(String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, (int) (8 * getResources().getDisplayMetrics().density), 0);
        b.setLayoutParams(lp);
        return b;
    }

    // ---------- 编辑对话框 ----------

    private void editDialog(int index, String presetToml) {
        JSONObject p = index >= 0 && index < providers.size() ? providers.get(index) : new JSONObject();
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        form.setPadding(pad, pad, pad, 0);

        EditText name = field(form, "名称（如：DeepSeek）", p.optString("name"));
        EditText url = field(form, "Base URL（含 /v1）", p.optString("baseUrl"));
        EditText key = field(form, "API Key", p.optString("apiKey"));
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText model = field(form, "模型（如：deepseek-chat）", p.optString("model"));

        TextView protoLabel = new TextView(this);
        protoLabel.setText("接口协议");
        protoLabel.setTextSize(14);
        form.addView(protoLabel);
        Spinner wire = new Spinner(this);
        wire.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"responses（官方/Responses 中转）", "chat（OpenAI 兼容 /v1/chat/completions）"}));
        wire.setSelection("chat".equals(p.optString("wireApi")) ? 1 : 0);
        form.addView(wire);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(index >= 0 ? "编辑服务商" : "添加服务商")
                .setView(form)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        JSONObject obj = index >= 0 ? providers.get(index) : new JSONObject();
                        obj.put("id", index >= 0 ? p.optString("id", genId()) : genId());
                        obj.put("name", name.getText().toString().trim());
                        obj.put("baseUrl", url.getText().toString().trim());
                        obj.put("apiKey", key.getText().toString().trim());
                        obj.put("model", model.getText().toString().trim());
                        obj.put("wireApi", wire.getSelectedItemPosition() == 1 ? "chat" : "responses");
                        if (index < 0) providers.add(obj);
                        saveProviders();
                        render();
                        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                    } catch (Exception error) {
                        Toast.makeText(this, "保存失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private EditText field(LinearLayout parent, String hint, String value) {
        TextView label = new TextView(this);
        label.setText(hint);
        label.setTextSize(14);
        parent.addView(label);
        EditText et = new EditText(this);
        et.setText(value);
        et.setTextSize(15);
        et.setSingleLine(true);
        parent.addView(et);
        return et;
    }

    // ---------- 数据持久化 ----------

    private Path storeFile() { return codexDir.resolve("provider-switch.json"); }

    private void loadProviders() {
        providers = new ArrayList<>();
        try {
            String text = new String(Files.readAllBytes(storeFile()), StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(text);
            for (int i = 0; i < arr.length(); i++) providers.add(arr.getJSONObject(i));
        } catch (Exception ignored) { }
    }

    private void saveProviders() {
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject p : providers) arr.put(p);
            Files.write(storeFile(), arr.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            Toast.makeText(this, "列表保存失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void delete(int index) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("删除服务商")
                .setMessage("确定删除「" + displayName(providers.get(index).optString("id")) + "」？只移除本页记录，不改 config.toml。")
                .setPositiveButton("删除", (d, w) -> {
                    providers.remove(index);
                    saveProviders();
                    render();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String displayName(String id) {
        for (JSONObject p : providers) if (id.equals(p.optString("id"))) return p.optString("name", id);
        return id;
    }

    private String genId() {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder("p");
        for (int i = 0; i < 7; i++) sb.append(alphabet.charAt((int) (Math.random() * alphabet.length())));
        return sb.toString();
    }

    // ---------- 切换：patch config.toml ----------

    private static final Pattern MODEL_PROVIDER_LINE = Pattern.compile("^\\s*model_provider\\s*=.*$", Pattern.MULTILINE);
    private static final Pattern MODEL_LINE = Pattern.compile("^\\s*model\\s*=.*$", Pattern.MULTILINE);
    private static final Pattern PROVIDER_SECTION = Pattern.compile("(?m)^\\s*\\[model_providers\\.[^\\]]+\\]\\s*$");

    private void activate(int index) {
        JSONObject p = providers.get(index);
        String id = p.optString("id");
        String wireApi = p.optString("wireApi", "responses");
        String baseUrl = p.optString("baseUrl", "").trim();
        String apiKey = p.optString("apiKey", "").trim();
        String model = p.optString("model", "").trim();
        if (baseUrl.isEmpty() && "official".equals(id)) { /* 官方允许空 */ }
        else if (baseUrl.isEmpty()) {
            Toast.makeText(this, "Base URL 不能为空", Toast.LENGTH_LONG).show();
            return;
        }
        if (model.isEmpty()) {
            Toast.makeText(this, "请先填写模型名称", Toast.LENGTH_LONG).show();
            return;
        }
        if (apiKey.isEmpty() && !"official".equals(id)) {
            Toast.makeText(this, "API Key 不能为空", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            String toml = configFile.exists()
                    ? new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8)
                    : "";
            Path backup = codexDir.resolve("config.toml.bak-" + System.currentTimeMillis());
            if (configFile.exists())
                Files.copy(configFile, backup, StandardCopyOption.REPLACE_EXISTING);
            String patched = patchToml(toml, id, p.optString("name", id), baseUrl, apiKey, model, wireApi);
            Files.write(configFile, patched.getBytes(StandardCharsets.UTF_8));
            saveProviders();
            Toast.makeText(this, "已切换到「" + p.optString("name") + "」，重启 Codex 桌面生效（备份：" + backup.getFileName() + "）", Toast.LENGTH_LONG).show();
            render();
        } catch (Exception error) {
            Toast.makeText(this, "切换失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String readActiveProviderId() {
        try {
            Matcher m = MODEL_PROVIDER_LINE.matcher(new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8));
            if (m.find()) {
                String line = m.group();
                int a = line.indexOf('"');
                int b = line.lastIndexOf('"');
                if (a >= 0 && b > a) return line.substring(a + 1, b);
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** 行级 TOML patch：model / model_provider 属顶层键，必须保持在第一个 section 之前；旧 [model_providers.*] 段整体删除后于文末重建。 */
    static String patchToml(String toml, String id, String name, String baseUrl, String apiKey, String model, String wireApi) {
        List<String> top = new ArrayList<>();    // 第一个 section 之前的顶层区
        List<String> rest = new ArrayList<>();   // 各 section（去掉旧 provider 段）
        String[] lines = toml.replace("\r\n", "\n").split("\n", -1);
        boolean inProviderSection = false;
        boolean inAnySection = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                if (PROVIDER_SECTION.matcher(line).find()) { inProviderSection = true; continue; }
                inProviderSection = false;
                inAnySection = true;
                rest.add(line);
                continue;
            }
            if (inProviderSection) continue;               // 丢弃旧 provider 段内容
            if (MODEL_LINE.matcher(line).matches()) continue;         // model 统一重插
            if (MODEL_PROVIDER_LINE.matcher(line).matches()) continue; // model_provider 统一重插
            (inAnySection ? rest : top).add(line);
        }
        while (!top.isEmpty() && top.get(top.size() - 1).trim().isEmpty()) top.remove(top.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (String line : top) sb.append(line).append('\n');
        if (sb.length() == 0) sb.append("# Generated by Codex API 服务商配置\n");
        sb.append("model = ").append(tomlString(model)).append('\n');
        sb.append("model_provider = ").append(tomlString(id)).append('\n');
        for (String line : rest) sb.append(line).append('\n');
        sb.append('\n');
        sb.append("[model_providers.").append(sanitizeId(id)).append("]\n");
        sb.append("name = ").append(tomlString(name)).append('\n');
        sb.append("base_url = ").append(tomlString(baseUrl)).append('\n');
        sb.append("wire_api = ").append(tomlString(wireApi)).append('\n');
        if (apiKey != null && !apiKey.isEmpty())
            sb.append("experimental_bearer_token = ").append(tomlString(apiKey)).append('\n');
        return sb.toString();
    }

    private static String tomlString(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    private static String sanitizeId(String id) {
        return id.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
