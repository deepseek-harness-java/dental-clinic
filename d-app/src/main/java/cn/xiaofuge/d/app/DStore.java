package cn.xiaofuge.d.app;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** 牙科诊所数据中心：医生排班/患者档案/挂号/就诊记录/价目与医保/统计 */
@Component
public class DStore {

    static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 医生：姓名/职称/方向/号价 */
    static final Map<String, Object[]> DOCTORS = new LinkedHashMap<>();
    static {
        DOCTORS.put("D01", new Object[]{"周明远", "主任医师", "种植/复杂拔牙", 60.0});
        DOCTORS.put("D02", new Object[]{"林晓芸", "副主任医师", "正畸/隐形矫正", 50.0});
        DOCTORS.put("D03", new Object[]{"赵启铭", "主治医师", "补牙/根管治疗", 40.0});
        DOCTORS.put("D04", new Object[]{"孙倩", "主治医师", "儿童牙科/涂氟", 40.0});
        DOCTORS.put("D05", new Object[]{"吴亚男", "执业医师", "洁牙/牙周护理", 30.0});
    }

    /** 治疗项目价目：名称/价格/医保比例（0=自费）/耗时分钟 */
    static final Map<String, Object[]> PRICES = new LinkedHashMap<>();
    static {
        PRICES.put("超声波洁牙", new Object[]{180.0, 0.50, 30});
        PRICES.put("树脂补牙", new Object[]{350.0, 0.40, 40});
        PRICES.put("根管治疗", new Object[]{1200.0, 0.30, 90});
        PRICES.put("拔智齿", new Object[]{800.0, 0.30, 60});
        PRICES.put("种植牙", new Object[]{8800.0, 0.0, 120});
        PRICES.put("隐形矫正", new Object[]{28000.0, 0.0, 60});
        PRICES.put("儿童涂氟", new Object[]{150.0, 0.60, 20});
    }

    public static class Doctor {
        public String id; public String name; public String title; public String specialty;
        public double fee; public String slot; public int capacity; public int booked; public String status; // 出诊/停诊
    }

    public static class Patient {
        public String id; public String name; public String phone; public int age;
        public String insurance; // 医保类型：职工/居民/自费
        public String lastVisit; public String note; public int visits;
    }

    public static class Appt {
        public String id; public String patient; public String doctor; public String time;
        public String item; public String status; // 已预约/已完成/已取消
    }

    public final List<Doctor> doctors = new ArrayList<>();
    public final List<Patient> patients = new ArrayList<>();
    public final List<Appt> appts = new ArrayList<>();
    private int apptSeq = 6001;

    public DStore() { seed(); }

    private void seed() {
        doctors.add(d("D01", "上午 09:00-12:00", 12, 12, "出诊"));
        doctors.add(d("D02", "全天 09:00-17:30", 16, 11, "出诊"));
        doctors.add(d("D03", "下午 13:30-18:00", 14, 9, "出诊"));
        doctors.add(d("D04", "上午 09:00-12:00", 10, 4, "出诊"));
        doctors.add(d("D05", "全天 09:00-17:30", 20, 15, "停诊"));

        patients.add(pt("P01", "陈国栋", "138****2001", 45, "职工医保", "09-20 种植牙一期", "骨结合良好，二期 10 月复诊", 6));
        patients.add(pt("P02", "刘桂芳", "139****2002", 32, "职工医保", "09-22 隐形矫正复诊", "第 8 副牙套，贴合度良好", 9));
        patients.add(pt("P03", "赵小虎", "137****2003", 8, "居民医保", "09-18 儿童涂氟", "乳牙龋齿 1 颗，建议 10 月补", 4));
        patients.add(pt("P04", "钱伟", "136****2004", 28, "自费", "09-23 拔智齿", "左下阻生齿已拔，术后无异常", 2));
        patients.add(pt("P05", "郑淑华", "135****2005", 58, "居民医保", "09-15 根管治疗", "第二阶段完成，10 月做牙冠", 7));

        appts.add(a("陈国栋", "周明远", "明天 09:30", "种植牙二期", "已预约"));
        appts.add(a("刘桂芳", "林晓芸", "明天 14:00", "隐形矫正复诊", "已预约"));
        appts.add(a("赵小虎", "孙倩", "周六 10:00", "乳牙补牙", "已预约"));
    }

    private Doctor d(String id, String slot, int cap, int booked, String status) {
        Object[] info = DOCTORS.get(id);
        Doctor x = new Doctor(); x.id = id; x.name = (String) info[0]; x.title = (String) info[1];
        x.specialty = (String) info[2]; x.fee = (Double) info[3]; x.slot = slot;
        x.capacity = cap; x.booked = booked; x.status = status; return x;
    }

    private Patient pt(String id, String name, String phone, int age, String ins, String last, String note, int visits) {
        Patient x = new Patient(); x.id = id; x.name = name; x.phone = phone; x.age = age;
        x.insurance = ins; x.lastVisit = last; x.note = note; x.visits = visits; return x;
    }

    private Appt a(String patient, String doctor, String time, String item, String status) {
        Appt x = new Appt(); x.id = "A" + apptSeq++; x.patient = patient; x.doctor = doctor;
        x.time = time; x.item = item; x.status = status; return x;
    }

    /** 医生列表与排班 */
    public List<Doctor> doctorList() { return doctors; }

    /** 挂号：停诊拦截 + 满号拦截 + 重复挂号拦截 */
    public synchronized Map<String, Object> book(String patientName, String doctorId, String item) {
        Doctor doc = doctors.stream().filter(x -> x.id.equals(doctorId)).findFirst().orElse(null);
        if (doc == null) return Map.of("ok", false, "msg", "医生 " + doctorId + " 不存在，可选 D01-D05");
        Patient p = patients.stream().filter(x -> x.name.equals(patientName)).findFirst().orElse(null);
        if (p == null) return Map.of("ok", false, "msg", "患者 " + patientName + " 未建档，请先持身份证到前台建档");
        if ("停诊".equals(doc.status)) return Map.of("ok", false, "msg", "「" + doc.name + "」明日停诊（" + doc.specialty + "），可改约其他医生或改日再来");
        if (appts.stream().anyMatch(x -> x.patient.equals(patientName) && x.doctor.equals(doc.name) && "已预约".equals(x.status)))
            return Map.of("ok", false, "msg", "您已有该医生的待就诊号，勿重复挂号，如需改期请先取消");
        if (doc.booked >= doc.capacity) return Map.of("ok", false, "msg", "「" + doc.name + "」明日号源已满（" + doc.booked + "/" + doc.capacity + "），可约候补或选择其他医生");
        doc.booked++;
        Appt ap = a(patientName, doc.name, doc.slot.split(" ")[0] + " " + doc.slot.split(" ")[1].split("-")[0], item == null ? doc.specialty : item, "已预约");
        appts.add(0, ap);
        p.visits++;
        return Map.of("ok", true, "apptId", ap.id, "patient", patientName, "doctor", doc.name, "title", doc.title,
                "time", "明日 " + doc.slot, "regFee", doc.fee,
                "msg", "挂号成功，号源余 " + (doc.capacity - doc.booked) + " 个，就诊时报取号码 " + ap.id);
    }

    /** 就诊记录 */
    public Map<String, Object> records(String patientName) {
        Patient p = patients.stream().filter(x -> x.name.equals(patientName)).findFirst().orElse(null);
        if (p == null) return Map.of("ok", false, "msg", "患者 " + patientName + " 未建档");
        List<Map<String, Object>> my = appts.stream().filter(x -> x.patient.equals(patientName))
                .map(x -> { Map<String, Object> m = new LinkedHashMap<String, Object>();
                    m.put("id", x.id); m.put("doctor", x.doctor); m.put("time", x.time);
                    m.put("item", x.item); m.put("status", x.status); return m; })
                .collect(Collectors.toList());
        return Map.of("ok", true, "patient", p.name, "age", p.age, "insurance", p.insurance,
                "lastVisit", p.lastVisit, "note", p.note, "visits", p.visits, "appts", my);
    }

    /** 费用估算：价目 × 医保比例 */
    public synchronized Map<String, Object> estimate(String patientName, String item) {
        Object[] rule = PRICES.get(item);
        if (rule == null) return Map.of("ok", false, "msg", "项目 " + item + " 不在价目表，可选：" + String.join("/", PRICES.keySet()));
        double price = (Double) rule[0]; double rate = (Double) rule[1]; int minutes = (Integer) rule[2];
        Patient p = patients.stream().filter(x -> x.name.equals(patientName)).findFirst().orElse(null);
        String ins = p == null ? "自费" : p.insurance;
        // 医保实际支付比例：项目医保比例 × 人群系数（职工 1.0 / 居民 0.8 / 自费 0）
        double factor = "职工医保".equals(ins) ? 1.0 : ("居民医保".equals(ins) ? 0.8 : 0.0);
        double covered = Math.round(price * rate * factor * 100.0) / 100.0;
        double self = price - covered;
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("ok", true); r.put("item", item); r.put("price", price);
        r.put("insurance", ins); r.put("itemInsRate", rate * 100 + "%");
        r.put("personFactor", factor == 1.0 ? "100%" : (factor == 0.8 ? "80%" : "0%（自费）"));
        r.put("covered", covered); r.put("selfPay", self); r.put("duration", minutes + " 分钟");
        r.put("msg", item + " ¥" + price + "，" + ins + "预估医保支付 ¥" + covered + "、个人支付 ¥" + self
                + (rate == 0 ? "（该项目为自费项目，不参与医保）" : ""));
        return r;
    }

    /** 取消挂号 */
    public synchronized Map<String, Object> cancel(String apptId) {
        Appt ap = appts.stream().filter(x -> x.id.equals(apptId)).findFirst().orElse(null);
        if (ap == null) return Map.of("ok", false, "msg", "挂号单 " + apptId + " 不存在");
        if (!"已预约".equals(ap.status)) return Map.of("ok", false, "msg", "该单状态为「" + ap.status + "」，无法取消");
        ap.status = "已取消";
        doctors.stream().filter(x -> x.name.equals(ap.doctor)).findFirst().ifPresent(x -> x.booked = Math.max(0, x.booked - 1));
        return Map.of("ok", true, "apptId", ap.id, "msg", "已取消 " + ap.doctor + " " + ap.time + " 的号，号源已释放");
    }

    /** 运营统计 */
    public Map<String, Object> stats() {
        Map<String, Object> docStats = new LinkedHashMap<String, Object>();
        for (Doctor doc : doctors) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("booked", doc.booked); m.put("capacity", doc.capacity);
            m.put("fillRate", Math.round(doc.booked * 1000.0 / doc.capacity) / 10.0);
            m.put("slot", doc.slot); m.put("status", doc.status);
            docStats.put(doc.name + "·" + doc.specialty, m);
        }
        Map<String, Long> byIns = patients.stream().collect(Collectors.groupingBy(x -> x.insurance, Collectors.counting()));
        long pending = appts.stream().filter(x -> "已预约".equals(x.status)).count();
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("doctors", doctors.size());
        r.put("onDuty", doctors.stream().filter(x -> "出诊".equals(x.status)).count());
        r.put("schedules", docStats);
        r.put("patients", patients.size());
        r.put("byInsurance", byIns);
        r.put("pendingAppts", pending);
        r.put("advice", "吴亚男停诊（洁牙/牙周）可引导至赵启铭；周明远上午号已约满，可引导约林晓芸或改约下午；郑淑华 10 月需复查做牙冠，建议主动回访");
        return r;
    }
}
