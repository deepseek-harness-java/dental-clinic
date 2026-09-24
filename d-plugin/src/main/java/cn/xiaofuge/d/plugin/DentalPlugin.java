package cn.xiaofuge.d.plugin;

import cn.xiaofuge.deepseek.harness.domain.model.entity.AbstractTool;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext;
import cn.xiaofuge.deepseek.harness.domain.spi.AbstractHarnessPlugin;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginContext;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginHookResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** AI 牙科诊所助手插件：把 dental-clinic REST API 注册为 DSH Agent 工具 */
public class DentalPlugin extends AbstractHarnessPlugin {

    public static final String PLUGIN_ID = "dental-copilot";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    public DentalPlugin() { super(PLUGIN_ID); }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new DoctorListTool(),
                new BookTool(),
                new RecordsTool(),
                new EstimateTool(),
                new CancelTool(),
                new StatsTool());
    }

    @Override
    public void configure(PluginContext context) {
        super.configure(context);
        context.registerSystemPrompt("dental-capabilities", 20, """
                ## AI 牙科诊所助手（诊所前台运营 · 2026-09-25）
                - 查医生排班 → doctor_list（姓名/职称/方向/时段/余号/停诊状态；停诊给替代建议）
                - 挂号 → book（patient/doctorId/item：报挂号费与取号码；停诊/满号/重复挂号会被拦截并给建议；
                  挂号前必须复述医生、时段、挂号费请用户确认）
                - 查就诊记录 → records（patient：最近就诊/医嘱备注/历史挂号）
                - 费用估算 → estimate（patient/item：报项目价格、医保类型、医保支付与个人支付金额；
                  种植牙/隐形矫正为自费项目须主动说明）
                - 取消挂号 → cancel（apptId：取消后号源释放）
                - 问运营 → stats（出诊人数/号源满员率/患者医保构成/待就诊数/回访建议）
                - 回答要求：
                  1) 挂号前必须复述要素（医生/时段/挂号费）请用户确认
                  2) 挂号结果必报取号码与余号；取消必报释放结果
                  3) 停诊/满号/未建档必须给处理建议（换医生/候补/到前台建档）
                  4) 费用估算必须写清计算口径（项目价 × 项目医保比例 × 人群系数）
                  5) 数据来自工具返回，禁止编造医生与价目数据
                """);
        context.registerHook("PRE_TOOL_USE", (toolName, payloadJson) -> {
            if (toolName != null && toolName.startsWith("plugin__" + PLUGIN_ID + "__")) {
                return PluginHookResult.context("audit: dental tool call.");
            }
            return null;
        });
    }

    private String get(String path, Map<String, Object> args) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl(args) + path)).GET().build());
    }

    private String post(String path, String jsonBody, Map<String, Object> args) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl(args) + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build());
    }

    private String baseUrl(Map<String, Object> args) {
        Object override = args == null ? null : args.get("appBaseUrl");
        return override == null || String.valueOf(override).isBlank()
                ? System.getenv().getOrDefault("DENTAL_APP_BASE_URL", "http://127.0.0.1:18102")
                : String.valueOf(override);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return "{\"error\":true,\"status\":" + resp.statusCode() + "}";
            return resp.body();
        } catch (Exception e) {
            return "{\"error\":true,\"message\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    private String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private String json(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private class DoctorListTool extends AbstractTool {
        @Override public String name() { return "doctor_list"; }
        @Override public String description() {
            return "医生排班列表：姓名/职称/擅长方向/出诊时段/余号/停诊状态。挂号前必查；停诊给替代医生建议。";
        }
        @Override public Map<String, Object> parameters() { return objectSchema().build(); }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            return ok(get("/api/doctors", args));
        }
    }

    private class BookTool extends AbstractTool {
        @Override public String name() { return "book"; }
        @Override public String description() {
            return "挂号预约：patient（患者姓名）/doctorId（D01-D05）/item（就诊项目）必填。"
                    + "停诊/满号/重复挂号会被拦截。必须先复述医生时段与挂号费经用户确认后才能调用。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("patient", stringSchema("患者姓名"))
                    .prop("doctorId", stringSchema("医生编号 D01-D05"))
                    .prop("item", stringSchema("就诊项目，如 种植牙二期 / 补牙"))
                    .required("patient", "doctorId")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return false; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String body = "{\"patient\":\"" + json(str(args, "patient"))
                    + "\",\"doctorId\":\"" + json(str(args, "doctorId"))
                    + "\",\"item\":\"" + json(str(args, "item")) + "\"}";
            return ok(post("/api/book", body, args));
        }
    }

    private class RecordsTool extends AbstractTool {
        @Override public String name() { return "records"; }
        @Override public String description() {
            return "就诊记录查询：patient（患者姓名）必填。返回最近就诊/医嘱备注/医保类型/历史挂号列表。"
                    + "何时必须调用：复诊提醒、问上次看牙情况。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("patient", stringSchema("患者姓名"))
                    .required("patient")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            return ok(get("/api/records?patient=" + java.net.URLEncoder.encode(str(args, "patient"), StandardCharsets.UTF_8), args));
        }
    }

    private class EstimateTool extends AbstractTool {
        @Override public String name() { return "estimate"; }
        @Override public String description() {
            return "费用估算：patient（患者姓名）/item（项目名）必填。返回项目价格/医保类型/医保支付/个人支付。"
                    + "价目：超声波洁牙180/树脂补牙350/根管治疗1200/拔智齿800/种植牙8800/隐形矫正28000/儿童涂氟150。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("patient", stringSchema("患者姓名"))
                    .prop("item", stringSchema("项目名：超声波洁牙 / 树脂补牙 / 根管治疗 / 拔智齿 / 种植牙 / 隐形矫正 / 儿童涂氟"))
                    .required("patient", "item")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String body = "{\"patient\":\"" + json(str(args, "patient"))
                    + "\",\"item\":\"" + json(str(args, "item")) + "\"}";
            return ok(post("/api/estimate", body, args));
        }
    }

    private class CancelTool extends AbstractTool {
        @Override public String name() { return "cancel"; }
        @Override public String description() {
            return "取消挂号：apptId（挂号单号，A 开头）必填。取消后号源释放。仅已预约状态可取消。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("apptId", stringSchema("挂号单号"))
                    .required("apptId")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return false; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String body = "{\"apptId\":\"" + json(str(args, "apptId")) + "\"}";
            return ok(post("/api/cancel", body, args));
        }
    }

    private class StatsTool extends AbstractTool {
        @Override public String name() { return "stats"; }
        @Override public String description() {
            return "运营统计：出诊医生数/号源满员率/患者医保构成/待就诊数/回访与改约建议。"
                    + "何时必须调用：问今天运营、问号源情况。";
        }
        @Override public Map<String, Object> parameters() { return objectSchema().build(); }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            return ok(get("/api/stats", args));
        }
    }
}
