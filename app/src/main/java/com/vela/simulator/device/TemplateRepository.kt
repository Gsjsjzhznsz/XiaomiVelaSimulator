package com.vela.simulator.device

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 模板仓库：
 * 1) 内置 15 款官方设备模板（assets/templates/ 下按机型命名的 JSON）
 * 2) 用户自定义模板（files/templates/ 下），可增删改
 */
class TemplateRepository(private val context: Context) {

    @Serializable
    data class TemplateMeta(val source: String, val builtIn: Boolean, val fileName: String)

    private val customDir: File
        get() = File(context.filesDir, "templates").apply { mkdirs() }

    /** 全部模板（内置 + 自定义），自定义优先展示在前 */
    fun loadAll(): List<Pair<DeviceTemplate, TemplateMeta>> {
        val builtin = context.assets.list("templates").orEmpty()
            .filter { it.endsWith(".json") }
            .mapNotNull { fn ->
                runCatching {
                    DeviceTemplate.fromJson(context.assets.open("templates/$fn").bufferedReader().readText())
                        ?.let { it to TemplateMeta("内置模板", true, fn) }
                }.getOrNull()
            }
        val custom = customDir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { f ->
                runCatching {
                    DeviceTemplate.fromJson(f.readText())?.let { it to TemplateMeta("自定义模板", false, f.name) }
                }.getOrNull()
            }
        return custom + builtin
    }

    fun builtinOnly(): List<Pair<DeviceTemplate, TemplateMeta>> = loadAll().filter { it.second.builtIn }

    fun saveCustom(t: DeviceTemplate): Result<File> = runCatching {
        customDir.mkdirs()
        val f = File(customDir, "${t.id}.json")
        f.writeText(t.toJson())
        f
    }

    /** 复制一份模板并重命名 id，用于"以此为蓝本自定义" */
    fun duplicateAsCustom(t: DeviceTemplate, newIdSuffix: String = "-custom"): Result<DeviceTemplate> = runCatching {
        val copy = t.copy(
            id = t.id + newIdSuffix,
            name = t.name + "（自定义）",
        )
        saveCustom(copy).getOrThrow()
        copy
    }

    fun deleteCustom(id: String): Boolean =
        File(customDir, "$id.json").takeIf { it.exists() }?.delete() == true

    fun findCustomFile(id: String): File? = File(customDir, "$id.json").takeIf { it.exists() }

    companion object {
        val jsonPretty = Json { prettyPrint = true; encodeDefaults = true }
    }
}
