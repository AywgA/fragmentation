# 兼容模组机制深度解析与武器耐久-MOA适配

## 一、桌面「兼容」文件夹中的 JAR 真实身份

| 项目 | 实际情况 |
| --- | --- |
| JAR 文件名 | `tacz_tactical_breaching-forge-1.20.1-1.0.4.jar` |
| 文件来源 | SmartStreamLabs 的 **[TaCZ] Tactical Breaching** 模组 |
| 该 JAR 真实功能 | 现实化突破、玻璃破坏、子弹壳掉落、枪口烟雾、破门、闪光弹、烟雾弹等 |
| **该 JAR 是否包含耐久度机制** | **否** |

用户描述的「可自定义武器耐久度（百分比）」的模组实际上是 **Corrinedev 的 TACZ: Durability** 模组（CurseForge 项目 ID 1065328，Modrinth slug `tacz-durability`），GitHub 仓库为 `Corrinedev/tacz-durability`。该模组内部包名 `com.corrinedev.gundurability.*`，已经在 `TaczMoaHandler.java` 原有反射逻辑中作为目标。

## 二、TACZ: Durability 关键源码解析

### 1) 耐久度存储（NBT）
- 当前耐久：`Durability`（整数，初始值 = `MAXDURABILITY`，默认 2000）
- 枪械 ID：`GunId`（字符串，例如 `tacz:db_short`）
- 卡壳状态：`Jammed`（布尔）
- 耐久度在 `TaczEvents#onShootEvent` 中按 `GunFireEvent` 服务端事件扣减，单发扣 1，BURST 扣 3，并叠加生物群系 / 单枪修饰器。

### 2) 卡壳（JAM）机制
- 当 `JAMCHANCE > 0` 且枪不在 `unjammableList` 中，每次射击执行
  `Mth.nextInt(rand, -1, durability / JAMCHANCE) == 0` 检测卡壳。
- 默认 `JAMCHANCE = 15`，耐久越低，上限越小，卡壳概率越高。

### 3) **MOA / 不准确度公式（核心适配点）**
源码位置：`com.corrinedev.gundurability.mixin.AccuracyMixin`

```java
@ModifyVariable(method = "shootOnce", at = @At("STORE"),
                name = "inaccuracy", ordinal = 0)
public float shootOnce(float inaccuracy) {
    float inaccuracyF = inaccuracy * (
        ((float) Math.abs(
            shooter.getMainHandItem().getOrCreateTag().getInt("Durability")
          - Config.getDurability(shooter.getMainHandItem()
                                  .getOrCreateTag().getString("GunId")))
          / Config.INACCURACYRATE.get()) + 1
    );
    return inaccuracyF;
}
```

化简后：
```
inaccuracyMultiplier = (maxDurability - currentDurability) / INACCURACYRATE + 1
```

### 4) 默认配置（来自 `Config.java`）
| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `MAXDURABILITY` | 2000 | 全局最大耐久 |
| `INACCURACYRATE` | 500 | 不准确度除数（越大越平缓） |
| `JAMCHANCE` | 15 | 卡壳随机上限分母 |
| `GUNSBREAK` | false | 耐久为 0 时是否直接销毁枪 |
| `BIOMEMODIFIERS` | desert×1.5, river×2, plains×0.8 | 生物群系耐久损耗倍率 |
| `DURABILITY_LIST` | `tacz:db_short:1500/2/100`, `tacz:db_long:1800/1/100` | 单枪独立耐久 / 卡壳倍率 / 卡壳时间 |
| `GUN_LIST` (unjammableList) | `tacz:db_short`, `tacz:db_long` | 不会卡壳的枪 |

### 5) 默认配置下的耐久-MOA 曲线
| 耐久 % | currentDurability | (max - cur) / 500 | multiplier |
| --- | --- | --- | --- |
| 100% | 2000 | 0 / 500 = 0 | **1.0×** |
| 75%  | 1500 | 500 / 500 = 1 | **2.0×** |
| 50%  | 1000 | 1000 / 500 = 2 | **3.0×** |
| 25%  | 500  | 1500 / 500 = 3 | **4.0×** |
| 0%   | 0    | 2000 / 500 = 4 | **5.0×** |

### 6) Tooltip 显示
源码：`TooltipMixin#renderText`，在 `BASE_INFO` 段插入：
```
Durability: <aqua>当前</aqua>/<gray>/</gray><aqua>最大</aqua>
```

## 三、本次对 Fragmentation_mechanism 的适配

### 1) `BallisticsMath.java` 新增方法
```java
public static double gundurabilityMoaMultiplier(
    int currentDurability,
    int maxDurability,
    int inaccuracyRate
)
```
精确镜像 gundurability 公式：
- 输入 `currentDurability = tag.getInt("Durability")`
- 输入 `maxDurability = Config.getDurability(gunId)` 反射结果
- 输入 `inaccuracyRate = Config.INACCURACYRATE` 反射结果
- 防御性处理：`safeMax<=0` 返回 1.0；`safeRate<=0` 用 1 兜底。
- 输出始终 ≥ 1.0，保证不会让 MOA 反而减小。

### 2) `TaczMoaHandler.java` 关键改动
| 改动 | 说明 |
| --- | --- |
| 新增常量 | `GUN_ID_KEY="GunId"`、`GUN_INACCURACY_RATE_FIELD="INACCURACYRATE"`、`GUNDURABILITY_DEFAULT_MAX_DURABILITY=2000`、`GUNDURABILITY_DEFAULT_INACCURACY_RATE=500` |
| 新增字段 | `cachedInaccuracyRate`、`inaccuracyRateReflectionInitialized` |
| 新增方法 `computeDurabilityMoaMultiplier(stack, gunId)` | 检测到 `Durability` NBT + gundurability 反射可用 → 调用 `gundurabilityMoaMultiplier`；否则回退到原 `durabilityMoaMultiplier(durabilityRatio)` 曲线 |
| 新增方法 `getInaccuracyRate()` | 反射读取 `com.corrinedev.gundurability.config.Config.INACCURACYRATE` 静态字段，调用其 `get()` 方法取值；失败则用默认 500 |
| 修改 `getMaximumGunDurability` | 兜底链最后追加 `GUNDURABILITY_DEFAULT_MAX_DURABILITY=2000`，与 gundurability 默认一致 |
| 修改主 `onEntityJoinLevel` | 用 `computeDurabilityMoaMultiplier` 取代直接调用，移除 `durabilityRatio` 变量 |
| 修改腰射分支 | 从 `Math.max(ammoAdjustedMoa, randomHipFireMoa())` 改为 `Math.max(ammoAdjustedMoa, randomHipFireMoa() * moaMultiplier)`，与 gundurability mixin 在 `shootOnce` 上不分 ADS/腰射一致 |
| 修改日志 | 删除 `durability=XX%` 字段（变量已不存在），保留 `multiplier` 字段 |
| 修改 `matchesGunId` | 用 `GUN_ID_KEY` 常量替换字面量 `"GunId"` |

### 3) 行为差异与意图
| 场景 | gundurability 原版 | 本次适配后 Fragmentation |
| --- | --- | --- |
| gundurability 已安装 + 枪上有 Durability NBT | `inaccuracy * (max-cur)/INACCURACYRATE + 1`，全模式生效 | **完全一致**，使用相同公式与反射读到的 `INACCURACYRATE` |
| gundurability 未安装 + 枪是 vanilla 可损坏物品 | 不影响 MOA（mixin 未触发） | 回退到原 `durabilityMoaMultiplier` 非线性曲线（满耐 1.0× → 半耐 1.2× → 空耐 4.0×），仍保留耐久惩罚 |
| 腰射（非瞄准）射击 | 同样应用耐久倍率 | 现在也应用耐久倍率（与原版一致） |

## 四、运行时反射安全
- 所有反射一次性初始化 + 缓存（`inaccuracyRateReflectionInitialized`、`durabilityReflectionInitialized`、`reflectionInitialized`）
- 反射失败时使用默认值（INACCURACYRATE=500、maxDurability=2000），不会抛异常
- 客户端 / 服务端双端安全：`onEntityJoinLevel` 在 `level().isClientSide` 时直接 return，所有反射仅在服务端执行

## 五、用户构建步骤
1. （可选）将桌面 `兼容` 文件夹中实际替换为 `tacz-durability` 模组 JAR（当前放的 `tacz_tactical_breaching` 不含耐久度逻辑，但仍可作为 TACZ 的扩展共存）
2. 进入 `C:\mods\Fragmentation_mechanism`
3. 运行 `do_build.bat`（输出到 `build_output.log`）或 `build_mod.bat`
4. 构建产物：`build\libs\fragmentation-6.2.0.jar`（dev）/ `build\reobfJar\output.jar`（reobf）

## 六、待用户确认事项
- 是否需要把 `MAXDURABILITY` 也通过反射读取并暴露到 GunConfig，让运营端可调？
- 是否需要在 Tooltip 中复刻 gundurability 的 `Durability: cur/max` 显示？目前 `TooltipEventHandler.java` 已有自有耐久显示，未做融合。
