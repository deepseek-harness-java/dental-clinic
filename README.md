# dental-clinic · AI 牙科诊所助手台（DSH Java Native 插件场景案例 P63）

> 基于 **deepseek-harness-java（DSH）Java Native 插件机制** 的牙科诊所场景案例：5 名医生排班（主任医师/副主任医师/主治/执业，含停诊 1 人）+ 患者档案（职工医保/居民医保/自费三类人群）+ 挂号预约（停诊拦截 + 满号拦截 + 重复挂号拦截 + 取号）+ 就诊记录查询（最近就诊/医嘱备注/历史挂号）+ 价目与医保支付估算（项目医保比例 × 人群系数）+ 取消挂号释放号源 + 运营统计，通过 `dental-copilot` 插件接入 AI 助手，支持自然语言查排班、挂号、查记录、算费用、看统计。

![总览](docs/images/01-overview.png)

## 一、项目组成

| 模块 | 说明 |
|------|------|
| `d-app` | Spring Boot 3.2 应用（端口 **18102**），牙科诊所 REST API 与前端页面 |
| `d-plugin` | DSH Java Native 插件（`dental-copilot`），打包 6 个 AI 工具 |

业务数据：5 名医生（种植/正畸/补牙根管/儿童牙科/洁牙牙周，含停诊 1 人）、7 项治疗价目（超声波洁牙 180 → 隐形矫正 28000，医保比例 0-60%）、5 名患者（职工医保 2 / 居民医保 2 / 自费 1）、3 笔初始挂号。

## 二、插件工具（6 个）

| 工具 | 说明 |
|------|------|
| `doctor_list` | 医生排班：姓名/职称/擅长/出诊时段/余号/停诊状态；停诊给替代医生建议 |
| `book` | 挂号：复述医生时段与挂号费二次确认后取号（A 前缀）；停诊/满号/重复挂号拦截 |
| `records` | 就诊记录：最近就诊/医嘱备注/医保类型/历史挂号列表 |
| `estimate` | 费用估算：项目价 × 项目医保比例 × 人群系数（职工 100%/居民 80%/自费 0%），种植牙/隐形矫正为自费项目须主动说明 |
| `cancel` | 取消挂号：仅已预约状态可取消，取消后号源释放 |
| `stats` | 运营统计：出诊人数/号源满员率/患者医保构成/待就诊数/回访与改约建议 |

## 三、REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/doctors` | 医生排班列表 |
| POST | `/api/book` | 挂号 `{patient,doctorId,item}` |
| GET | `/api/records?patient=` | 就诊记录 |
| POST | `/api/estimate` | 费用估算 `{patient,item}` |
| POST | `/api/cancel` | 取消挂号 `{apptId}` |
| GET | `/api/appts` | 挂号单列表 |
| GET | `/api/stats` | 运营统计 |
| POST | `/api/assistant/stream` | AI 助手 SSE（透传 DSH） |

## 四、快速开始

```bash
mvn clean package -DskipTests
java -Dserver.port=18102 -jar d-app/target/d-app-1.0.0-SNAPSHOT.jar

bash install_plugin.sh d-plugin/target/d-plugin-1.0.0-SNAPSHOT.jar \
  dental-copilot 1.0.0-SNAPSHOT d-plugin-1.0.0-SNAPSHOT.jar "AI 牙科诊所助手"

open http://127.0.0.1:18102/
```

## 五、端到端验证

```bash
bash agent_stream.sh 127.0.0.1:8090 dental-copilot "查一下明天各医生的排班和号源情况，周明远那边还能挂上吗？"
bash agent_stream.sh 127.0.0.1:8090 dental-copilot "给钱伟挂周明远医生明天的号，项目写复杂拔牙"
bash agent_stream.sh 127.0.0.1:8090 dental-copilot "那改挂赵启铭吧，另外查一下陈国栋的就诊记录"
bash agent_stream.sh 127.0.0.1:8090 dental-copilot "赵小虎要补牙，他有居民医保，医保能报多少？再看看诊所整体运营数据"
bash agent_stream.sh 127.0.0.1:8090 dental-copilot "取消 A6004 的挂号，帮钱伟估算拔智齿的费用"
```

6 个工具全部验证通过。验证截图：

| 截图 | 内容 |
|------|------|
| ![AI 排班查询](docs/images/02-ai-schedule.png) | AI 列 5 医生明日排班，指出周明远 12/12 已满给候补/改期建议 |
| ![AI 挂号+就诊记录](docs/images/03-ai-book.png) | AI 为钱伟改挂赵启铭成功（取号码 A6005），并报陈国栋种植牙复诊安排 |
| ![AI 医保估算+统计](docs/images/04-ai-stats.png) | AI 按口径算出居民医保补牙医保付 ¥112/个人付 ¥238，报号源满员率与回访建议 |

## 六、技术要点

- **挂号拦截链**：`book()` 依次校验医生存在 → 患者建档 → 停诊状态 → 重复挂号（同人同医生已预约）→ 满号（booked ≥ capacity）；通过后 booked+1、生成取号码、患者就诊次数 +1。
- **医保估算公式**：`covered = price × itemRate × personFactor`，人群系数职工 1.0 / 居民 0.8 / 自费 0.0；种植牙、隐形矫正 itemRate=0 天然自费；估算必须写清计算口径。
- **取消释放**：`cancel()` 校验状态机（仅已预约可取消），取消后医生 booked 减 1 释放号源，防超约。
- **结论约束**：挂号前必须复述医生/时段/挂号费；取号必报单号与余号；停诊/满号/未建档给处理建议（换医生/候补/建档）；费用必报医保支付与个人支付两笔。
- **超时修复**：SSE 代理配置 `spring.mvc.async.request-timeout: 180s`，避免长回答 503。

## 七、目录结构

```
dental-clinic/
├── pom.xml                  # 父 pom（maven.compiler.parameters=true）
├── d-app/                   # Spring Boot 应用 (18102)
│   └── src/main/java/cn/xiaofuge/d/app/
│       ├── DentalApplication.java
│       ├── DStore.java        # 医生排班/患者/挂号/价目医保/统计
│       ├── DController.java   # REST API
│       └── AssistantController.java # SSE 透传 DSH
├── d-plugin/                # DSH 插件 (dental-copilot)
│   └── src/main/
│       ├── java/.../DentalPlugin.java  # 6 工具
│       └── resources/META-INF/        # plugin.yaml + SPI
└── docs/
    ├── 使用说明.md
    └── images/              # 验证截图 ×4
```
